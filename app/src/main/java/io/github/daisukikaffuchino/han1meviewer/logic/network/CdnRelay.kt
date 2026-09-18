package io.github.daisukikaffuchino.han1meviewer.logic.network

import android.content.Context
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.utils.applicationContext
import io.github.daisukikaffuchino.utils.unsafeLazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 被封 CDN 的 TLS 中转（自建，跑在用户自己的美国 VPS 上）。
 *
 * ## 为什么「挂代理」救不了视频
 *
 * 这是本工程最容易误判的一处。`vdownload.hembed.com`（hanime 全部视频与封面）从大陆
 * 不可达，且**换了代理也一样** —— 原因不是代理没生效，而是**代理是明文隧道**：
 *
 * - SOCKS5 / HTTP 代理只搬运 TCP 字节，**不加密**；
 * - TLS 的 SNI 位于 ClientHello 的**明文**部分；
 * - 于是墙在「客户端 → 代理」这一段就能读到里面的 `vdownload.hembed.com`，直接 RST。
 *
 * 实测（2026-09-12，中国移动，经一台**能正常工作**的 SOCKS5 代理）：
 *
 * | 目标 | 结果 | 说明 |
 * |---|---|---|
 * | `https://hanime1.com/` | **200** | SNI 未被封 |
 * | `https://hanime1.me/` | **RST** | SNI 被封 |
 * | `https://vdownload.hembed.com/` | **RST** | SNI 被封 |
 *
 * 所以「详情页能开、视频 0:00 不动」与代理好坏无关，是**协议层**的问题。
 *
 * ## 解法：让中转站终结 TLS
 *
 * 客户端不谈 hembed，改为和**自己的服务器**用 TLS 通信（SNI 为空 IP 字面量，墙不拦），
 * 由服务器再去取视频：
 *
 * ```
 * 手机 ──TLS(SNI: 156.238.224.214)──▶ 自建中转 ──TLS(SNI: vdownload.hembed.com)──▶ hembed
 * ```
 *
 * 服务器在墙外，它的出站请求不受影响；墙这一侧只看得到「手机在和一个普通境外 IP 说 TLS」。
 *
 * ## 证书
 *
 * 中转用**自签证书**（[R.raw.relay_cert]，SAN 里带 `IP:156.238.224.214`，10 年有效）。
 * 自签不等于不安全：证书随 APK 内置，等于**把公钥钉死在应用里**，比系统 CA 更严 ——
 * 别人拿不到私钥就冒充不了这台中转。因此 [trustManager] 是「系统 CA + 内置证书」的组合：
 * 其它域名照旧走系统信任，只有这一个 IP 多认一张证书。
 *
 * ## 中转还会**改写 HLS 清单**（Pornhub 专有，但影响所有走中转的 HLS）
 *
 * Pornhub 的主清单里子清单是**相对路径**（`index-v1-a1.m3u8?validfrom=…`），
 * 子清单里的分片也是相对的（`seg-1-v1-a1.ts?…`）。而客户端是从
 * `https://<中转>/r/<secret>/<base64url(原URL)>` 取的清单 —— 这是一条**单段路径**，
 * 相对解析的结果是 `https://<中转>/r/<secret>/index-v1-a1.m3u8`，
 * 中转把 `index-v1-a1.m3u8` 当 base64 解，直接 400，**第二个请求就死**。
 *
 * hanime 没踩到是因为它给的是渐进式 `.mp4`（没有相对引用）；nJAV 的 surrit 清单
 * 恰好写的是绝对地址。所以这是**服务端**修掉的：中转见 `.m3u8` 就缓冲整份，
 * 把每个 URI 行（含 `URI="…"` 属性）用 `urljoin` 还原成**原站绝对地址**再返回。
 * 然后本类的 [CdnRelayInterceptor] 再把它们一个个改回中转 —— 正好闭环。
 *
 * ⚠️ 这条也说明：**不能用「本地直连能播」来推断手机能播**，
 * 相对清单在中转这条路上是必须显式处理的。
 *
 * ## 与 [interceptor.ImageRelayInterceptor] 的关系
 *
 * 两者都是「直连优先、失败才走中转」，但中转目的地不同：
 * 本类走**自己的服务器**（图片、视频都能过），`wsrv.nl` 只处理图片且会泄露 URL 给第三方。
 * [CdnRelayInterceptor] 装在更外层，正常情况下 `wsrv.nl` 那层根本不会被触发。
 */
object CdnRelay {

    private const val TAG = "CdnRelay"

    /**
     * **内置**中转端点。写死在 APK 里（用户自建，不打算公开分发），
     * 通过「网络设置 → CDN 中转」开关控制是否启用。
     *
     * ⚠️ 9.0 起实际生效的端点由 [RelayNodeStore] 决定（可能在池里换了别的节点）；
     * 这两个常量只代表**内置节点**，也是「一个自建节点都没有」时的兜底。
     * 保留它们而不是删掉，是为了保证升级上来的用户连接目标**一个字节都不变**。
     *
     * ⚠️ **2026-09-13 换机**：中转 VPS 从 `186.241.94.98` 换成 `156.238.224.214`。
     * 这台机器是**编译进 APK** 的（不是运行期配置），所以换机必然是一次发版：
     * 只改服务器不换客户端 = 老客户端连的还是旧机；只换客户端不部署服务器 = 连不上。
     * 换机时**必须成对改**这里与 `res/raw/relay_cert.pem`（新证书的 SAN 要写新 IP，
     * 且公钥钉在 APK 里），改完照 skill `sni-relay-bypass` 的三段验证逐条跑一遍。
     */
    const val HOST = "156.238.224.214"
    const val PORT = 7443

    /**
     * 路径口令。中转只允许取 [BLOCKED_HOSTS] 里的域名，所以即使这个值泄露，
     * 别人最多也只能拿它当一个「hembed 专用代理」，无法当开放代理滥用。
     *
     * `internal` 而非 `private`：`RelayNodeStore` 要用它构造内置节点。
     */
    internal const val SECRET = "tGb5QULX7mDx71kW5wV68p3zhCzYxRRv"

    /** 路径前缀，与服务器 `/r/<secret>/<base64url>` 约定一致。 */
    private const val PATH_ROOT = "r"

    /**
     * 必须走中转的域名。
     *
     * 判据是「已被实测确认从大陆不可达」，不要凭猜测往里加：
     * 多写一个域名，只会在直连本来能通的用户那里白绕一趟境外服务器。
     */
    private val BLOCKED_HOSTS = setOf(
        "hembed.com",   // hanime 全部视频（vdownload.hembed.com）与全部封面图
        "fourhoi.com",  // nJAV 封面与女优头像
        "pornhub.com",  // Pornhub 站点本体（HTML / JSON API）
        "phncdn.com",   // Pornhub 全部封面与视频 CDN（pix-*.phncdn.com / ev-h.phncdn.com …）
    )

    /**
     * **不做直连尝试、直接中转**的域名。
     *
     * 其余域名走的是「直连优先、失败才中转」（见 [CdnRelayInterceptor]），因为海外用户
     * 直连反而更快。但 Pornhub 这两个域名不一样：实测 2026-09-13，本机（大陆）对
     * `www.pornhub.com` 与 `*.phncdn.com` **用真实 IP 直连也是立即 RST**
     * （0.1 s / 0.3 s，TLS 握手阶段就被打断），属于 SNI 阻断 ——
     * 也就是说直连**在任何大陆网络下都不会成功**。
     *
     * 那就没必要先撞一次墙：每撞一次白等约 0.1–2 s，而看一个视频要发上百个请求
     * （m3u8、每个分片、每张封面），累计就是几十秒的纯浪费。
     *
     * > ⚠️ 别把 `hembed.com` 之类也搬进来 —— 它们的直连**在海外是通的**，
     * > 强制中转只会让海外用户体验变差（多绕一趟美国）。
     */
    private val ALWAYS_RELAY_HOSTS = setOf(
        "pornhub.com",
        "phncdn.com",
    )

    /**
     * 走中转时**替客户端补的 `Referer`**，按被访问域名取。
     *
     * Pornhub 的视频 CDN 有一条反直觉的规则（实测）：**主清单与子清单不要 Referer，
     * 但 `.ts` 分片不带 Referer 会 404**（带上 → `200` + 真 TS）。
     *
     * 麻烦的是分片请求由播放器发出，播放器只会把**中转地址**当作自己的 Referer ——
     * 那个值对 Pornhub 毫无意义。所以这里把「该用哪个 Referer」当成中转 URL 的一个参数
     * 带过去（服务器侧 `?ref=`），由服务器在向上游取的时候填上。
     */
    private val RELAY_REFERERS = mapOf(
        "phncdn.com" to "https://www.pornhub.com/",
    )

    fun isRelayHost(host: String): Boolean {
        val lower = host.lowercase().removeSuffix(".")
        return BLOCKED_HOSTS.any { lower == it || lower.endsWith(".$it") }
    }

    /** 该域名是否**跳过直连**、直接中转。 */
    fun mustRelay(host: String): Boolean {
        val lower = host.lowercase().removeSuffix(".")
        return ALWAYS_RELAY_HOSTS.any { lower == it || lower.endsWith(".$it") }
    }

    /** 该域名走中转时要补的 Referer；没有就返回 null（服务器原样转发客户端的头）。 */
    fun refererFor(host: String): String? {
        val lower = host.lowercase().removeSuffix(".")
        return RELAY_REFERERS.entries
            .firstOrNull { lower == it.key || lower.endsWith("." + it.key) }
            ?.value
    }

    /**
     * 会话级的「直连必死」记忆。
     *
     * 直连失败一次要花掉一次 RST 的时间（实测 0.8–2.6 s）。播放一个视频会发出
     * 几十上百个 Range 请求，如果每个都先撞一次墙再中转，等于白白多等几分钟。
     * 这里按 host 记一次结论，本进程内后续请求直接走中转。
     *
     * 不落盘：网络环境会变（换 Wi-Fi、开/关代理），下一次启动重新探一次最稳。
     */
    private val directIsKnownDead = ConcurrentHashMap.newKeySet<String>()

    fun isKnownDead(host: String): Boolean = directIsKnownDead.contains(host.lowercase())

    fun markKnownDead(host: String) = directIsKnownDead.add(host.lowercase())

    /**
     * 「此刻正有请求在试探该 host 的直连」。
     *
     * ## 为什么需要它
     *
     * [directIsKnownDead] 只挡得住**已经失败过**的请求。而冷启动那一瞬间，
     * 首页/列表页会**同时**发出 20–30 个封面请求（同一批图都在 `vdownload.hembed.com`
     * 或 `fourhoi.com` 上）—— 那时还没有任何一次失败可以借鉴，于是 30 个请求
     * **一起**去撞同一堵墙，每个都白等 0.8–2.6 s。用户感知就是
     * 「一打开 App，封面转半天才出来」。
     *
     * 这里让同一 host 的并发请求里**只有第一个**去试直连，其余直接走中转。
     * 一次撞墙就够得出结论了 —— 直连能不能通是 host 级的事实，跟并发数无关。
     *
     * ## 为什么不是「一律中转」
     *
     * 挂了自己的代理 / VPN 的用户**直连是通的**（`hembed` 只在境内被墙），
     * 强制中转会把他们本来最快的那条路堵死。这个「只探一次」的闸门刚好要的是
     * 两全：省掉重复撞墙，又不剥夺「有人本来就能直连」这件事。
     *
     * ⚠️ 闸门只在**试探期间**关着，探完立刻放行，所以不存在「一旦走过中转就永远走中转」。
     * 万一某个线程异常退出导致闸门没复位，后果也只是该 host 这一次会话多绕中转
     * （图片照样出得来），不会黑图。
     */
    private val directProbeInFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * 尝试成为该 host 的**唯一**直连试探者。
     *
     * @return true = 由本次请求负责试直连（用完必须 [endDirectProbe]）；
     *         false = 已有别的请求在试，本次直接走中转。
     */
    fun tryBeginDirectProbe(host: String): Boolean = directProbeInFlight.add(host.lowercase())

    /** 释放试探闸门。**必须放在 `finally` 里**，否则该 host 会一直被别人当作「正在探测」。 */
    fun endDirectProbe(host: String) {
        directProbeInFlight.remove(host.lowercase())
    }

    //<editor-fold desc="会话级「中转连接不上」记忆（26.9.18 引入）">

    /**
     * 上一次「中转在**连接层**失败」的时刻；`0` = 没有这个结论。
     *
     * ## 为什么必须有它
     *
     * 对 `pornhub.com` / `*.phncdn.com` 这类 `mustRelay` 域名，直连是 **SNI 阻断**（必死），
     * 中转是唯一的出路。而中转一旦下线（进程停了、IP 被临时封、端口被拒），
     * [CdnRelayInterceptor] 的**每一个**请求都会替这个域名走一遍
     * 「撞墙 0.1–0.3 s → 再绕一次连不上的中转（connect refused 秒回 / 不通则等满超时）」。
     *
     * 首页/详情页一次会并发十几个请求（Pornhub 首页是 10 个 JSON + 1 个 HTML），
     * 每个都白做两遍无用功 —— 用户看到的不是「报错」，而是**转圈然后白屏**。
     * 26.9.17 修的是「判据太宽导致误走中转」，这里补的是**另一个方向**：
     * 已经确定走不通了，就别让后面的请求再各撞一遍。
     *
     * ## 为什么是「机器级」而不是「按 host」
     *
     * 「中转通不通」是端点级事实，与被中转的目标域名无关。所以这里不像
     * [directIsKnownDead] 那样按 host 分开记 —— 一个布尔值就够。
     *
     * ## ⚠️ 为什么不用 `cachedReachable == false`
     *
     * 那个判据对失败**刻意宽容**（[RelayNodeStore] 里
     * `healthy = reachable || failures < FAILURE_THRESHOLD`），探活失败 1–2 次时仍返回
     * `true`。于是「中转已经下线」时它照样为真 ⇒ 请求继续白绕 —— 26.9.17 在
     * `mustRelay` 那里踩过同一个坑（见 [relayConfirmedReachable] 的注释）。
     *
     * ## ⚠️⚠️ 必须带 TTL：这个记忆**只能让体验变好，不能把路堵死**
     *
     * 「一次连接失败」推出「这台中转一直不可达」是归纳，**归纳会错**（中转重启、
     * IP 解封、网络切换）。所以这里不是永久黑名单：超过 [RELAY_UNREACHABLE_TTL_MS]
     * 就自动放行一次重试，成功即彻底解除。
     *
     * 没有 TTL 的后果是很实际的：用户在隧道里丢了一次连接 ⇒ 之后**每一次**请求都
     * 不再尝试中转 ⇒ 视频再也放不出来，而重启 App 才能恢复。这种「优化」比不做还糟。
     */
    private val relayUnreachableAt = AtomicLong(0L)

    /** 这个结论的有效期。到点自动放行一次重试。 */
    private const val RELAY_UNREACHABLE_TTL_MS = 120_000L

    /**
     * 中转是否**确定连接不上**（且结论还没过期）。
     *
     * `true` 时，[CdnRelayInterceptor] 会跳过「绕中转」这一步，直接抛直连的真实错误 ——
     * 既不白等，报错也能指向根因。
     *
     * ⚠️ 注意它**不阻断**「探活刚确认可达」那条路径（见 [relayConfirmedReachable]）：
     * 那条路是标记的**解除途径**，不是被标记影响的对象。
     */
    fun isRelayKnownUnreachable(): Boolean = relayUnreachableAt.get().let {
        it != 0L && System.currentTimeMillis() - it < RELAY_UNREACHABLE_TTL_MS
    }

    /**
     * 记一次「中转连接层失败」。只由 [interceptor.CdnRelayInterceptor] 在
     * **连接类异常**（见 `isConnectionLevelFailure`）时调用 —— 不是任何 IOException：
     * 「中转活着但上游把连接掐了」属于次生现象，不该把中转判死。
     */
    fun markRelayUnreachable() {
        val prev = relayUnreachableAt.getAndSet(System.currentTimeMillis())
        if (prev == 0L || System.currentTimeMillis() - prev >= RELAY_UNREACHABLE_TTL_MS) {
            LogUtil.w(TAG, "中转连接不上：本会话 ${RELAY_UNREACHABLE_TTL_MS / 1000}s 内不再为被封域名绕它")
        }
    }

    /**
     * 中转**真的取回了响应**时解除标记。
     *
     * 用「实际请求成功」而不是「探活成功」作为解除信号：探活只证明端口和证书没问题，
     * 真实请求走通才说明这条路可用。两者都会解除（探活成功 ⇒ 后续 `mustRelay` 请求
     * 会真的走中转 ⇒ 成功即解除），这里选更硬的那个证据。
     */
    fun markRelayReachable() {
        if (relayUnreachableAt.getAndSet(0L) != 0L) {
            LogUtil.i(TAG, "中转已恢复可达，解除「连接不上」标记")
        }
    }

    /**
     * 「此刻还值得为中转花一次往返吗」—— 判断「要不要绕中转」的**统一入口**。
     *
     * 由两个独立结论合成，两者**缺一不可**：
     *
     * | 来源 | 语义 | 它单独用会漏什么 |
     * |---|---|---|
     * | [cachedReachable] `!= false` | 节点健康检查没把它判死 | 对失败刻意宽容（连败 < 3 仍为 `true`）⇒ 中转已下线也返回 `true` |
     * | [!isRelayKnownUnreachable] | 本会话没有**实际**连接失败过 | 只在真正看到失败之后才成立 ⇒ 冷启动时还是不知道 |
     *
     * 合起来的效果：冷启动按老行为试一次（可能白等一次），一旦撞上连接失败就
     * 立刻在 [RELAY_UNREACHABLE_TTL_MS] 内不再白费；中转回来（探活成功 + 真实请求走通）
     * 或被 TTL 放行后自动恢复。
     *
     * ⚠️ 调用方**不要**用它去挡「探活刚确认可达」那条路径（[relayConfirmedReachable]），
     * 那是解除标记的途径，挡了就会自己把自己锁住。
     */
    fun isRelayWorthTrying(): Boolean =
        !isRelayKnownUnreachable() &&
            runCatching { RelayNodeStore.cachedActiveReachable }.getOrNull() != false

    /**
     * 这个异常够不够格说明「**中转这台机器不可达**」。
     *
     * 判定范围刻意收窄到**连接建立阶段**：
     *
     * | 异常 | 含义 | 记？ |
     * |---|---|---|
     * | `ConnectException` | 端口拒绝（进程不在） | ✅ |
     * | `SocketTimeoutException` | connect 或 read 超时 | ✅（见下） |
     * | `SSLException` | TLS 握手失败（证书被换 / 被 RST） | ✅ |
     * | `NoRouteToHostException` / `UnknownHostException` | 路由/N 解析不通 | ✅ |
     * | 其它 `IOException`（`SocketException: Connection reset` 等） | 多半是**上游**把连接掐了，中转本身是活的 | ❌ |
     *
     * `SocketTimeoutException` 在这里放宽了（`readTimeout` 也算）—— 严格说 read 超时
     * 意味着「中转慢」而不是「不通」，拿它标记会误伤。但播放链路 `readTimeout` 是 30 s
     * 且中转是分块流式回传，连续 30 s 一块数据都没有，实际就等于「这条道废了」；
     * 而漏判的代价（每个请求白等一次 connect 超时）明显更大。权衡后含进来。
     */
    internal fun isConnectionLevelFailure(e: Throwable): Boolean = when (e) {
        is java.net.ConnectException,
        is java.net.SocketTimeoutException,
        is java.net.NoRouteToHostException,
        is java.net.UnknownHostException,
        is javax.net.ssl.SSLException -> true
        else -> false
    }

    //</editor-fold>

    /** 用户可在设置里关掉（隐私 / 自己的线路本来就能直连时没必要绕）。 */
    val enabled: Boolean
        get() = runCatching { SettingsRepository.allowCdnRelay }.getOrDefault(true)

    /**
     * 把原始被封 URL 包成中转 URL。
     *
     * 用 base64url 承载原始 URL，而不是塞进查询串，有两个原因：
     * 1. 服务器侧只需一次解码，不必再解析嵌套 URL；
     * 2. 原 URL 自带 `?secure=xxx==,123` 这种带 `=` 与 `,` 的签名，
     *    放进查询串极易被各层规范化坏掉，而这段签名是不能动的。
     *
     * base64url 的字母表是 `A-Za-z0-9-_`，不含 `/`，正好是**单个路径段**，
     * 也不会被 OkHttp 二次转义。
     *
     * ⚠️ 9.0 起端点由 [RelayNodeStore.activeNode] 决定。这里是**每个请求都会走**的热路径，
     * 所以 `activeNode()` 特意做了「只有内置节点时直接返回、读设置都不读」的短路。
     */
    fun relayUrl(original: String): String? = relayUrl(original, ref = null)

    /**
     * 同上，但额外要求服务器在向上游取的时候使用 [ref] 作为 `Referer`。
     *
     * 为什么需要它：Pornhub 的 `.ts` 分片**不带 Referer 会 404**，而分片请求是播放器发出的，
     * 它只知道自己请求的是中转地址，没法凭空造出一个 `https://www.pornhub.com/`。
     * 于是把 Referer 作为参数拼在中转 URL 的查询串上（服务器侧见 `?ref=`）。
     *
     * 传 null 时不带这个参数，服务器就原样转发客户端自己的 `Referer`（hembed / fourhoi 走这条）。
     */
    fun relayUrl(original: String, ref: String?): String? {
        // 先确认确实是个合法 URL：不然后面会带一个「永远取不到」的中转请求出去，
        // 报错还会显示成中转的问题，排查时容易被带偏。
        original.toHttpUrlOrNull() ?: return null
        val node = runCatching { RelayNodeStore.activeNode() }.getOrElse { RelayNodeStore.builtInNode }
        val encoded = android.util.Base64.encodeToString(
            original.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
        )
        return "${node.baseUrl}/$PATH_ROOT/${node.secret}/$encoded"
            .let { if (ref.isNullOrBlank()) it else "$it?ref=$ref" }
            .toHttpUrlOrNull()?.toString()
    }

    /**
     * 「系统 CA + 内置中转证书」的组合信任。
     *
     * 为什么能随便加：这张证书的 SAN 只有 `IP:156.238.224.214`，
     * 就算某个攻击者把它拿去别的域名，hostname 校验也过不了；
     * 反过来只要连的是这个 IP，能通过校验的证书就必须持有我们的私钥。
     */
    val trustManager: X509TrustManager by unsafeLazy { buildTrustManager(applicationContext) }

    val sslContext: SSLContext by unsafeLazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }
    }

    private fun buildTrustManager(context: Context): X509TrustManager {
        val system = defaultTrustManager()
        val relay = runCatching {
            // ⚠️ 先把 CR 去掉再解析。这个文件在 Windows 工作区里是 CRLF（git 的
            // core.autocrlf 会把入库的 LF 换成 CRLF），而 aapt2 是**原样**把
            // res/raw 拷进 APK 的 —— 于是运行期拿到的是带 `\r` 的 PEM。
            // 主流 JDK 的 PEM 解析能容忍，但这是一次性、不可观测的初始化，
            // 一旦在某些 ROM 的 JDK 实现上不容忍，表现会是「一开中转就崩」，
            // 排查成本远高于这里去掉两个字节。
            val pem = context.resources.openRawResource(R.raw.relay_cert)
                .use { it.readBytes() }
                .toString(Charsets.UTF_8)
                .replace("\r", "")
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(pem.byteInputStream()) as X509Certificate
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("cdnRelay", certificate)
            }
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore) }
                .trustManagers
                .filterIsInstance<X509TrustManager>()
                .firstOrNull()
        }.getOrNull()

        if (relay == null) {
            // 内置证书读不出来（资源被裁、编码被改）时降级成「只用系统 CA」：
            // 中转请求会因证书校验失败而报错，但 App 不会崩，报错也会明确指向中转。
            LogUtil.e(TAG, "内置中转证书加载失败，本次运行将无法使用 CDN 中转")
            return system
        }
        return CompositeTrustManager(system, relay)
    }

    private fun defaultTrustManager(): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

    /** 依次询问：任一信任链通过即放行。两者都拒绝才抛。 */
    private class CompositeTrustManager(
        private val first: X509TrustManager,
        private val second: X509TrustManager,
    ) : X509TrustManager {

        private val accepted = first.acceptedIssuers + second.acceptedIssuers

        private fun <T> tryBoth(block: (X509TrustManager) -> T): T {
            val firstError = runCatching { block(first) }
            if (firstError.isSuccess) return firstError.getOrThrow()
            return block(second)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
            tryBoth { it.checkClientTrusted(chain, authType) }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
            tryBoth { it.checkServerTrusted(chain, authType) }

        override fun getAcceptedIssuers(): Array<X509Certificate> = accepted
    }

    //<editor-fold desc="中转可达性探活（8.1 引入，9.0 移交给 RelayNodeStore）">

    /**
     * 「直连失败 → 走中转」这条路上有一个不明显的浪费：
     *
     * 如果**中转自己**就不可达（服务器挂了、IP 被临时封、当前网络根本出不去），
     * 那么每一次直连失败之后**还要再等一次中转超时**，才把错误抛给上层。
     * 播放一个视频会发几十上百个请求，这种「双倍等待」在用户眼里就是卡死。
     *
     * 8.1 起这里做一次**轻量探活**（`GET /ping`）并缓存结论；9.0 引入节点池后，
     * 缓存的粒度从「中转」变成「每个节点」，实现整体搬到了 [RelayNodeStore]：
     *
     * | [cachedReachable] | 行为 |
     * |---|---|
     * | `true` | 直连失败 → 正常走中转（与 8.0 一致） |
     * | `false` | 直连失败 → **不再绕中转**，直接抛出真实的直连错误 |
     * | `null`（还没探过 / 缓存过期） | 按未知处理，行为与 8.0 完全一致 |
     *
     * ⚠️ 刻意用 `/ping` 而不是拿真实视频地址探：探活只需要证明「服务器活着、证书对得上」，
     * 不该为了一次探活去 CDN 上取字节（白耗流量，还可能被 CDN 侧当成异常请求）。
     */
    private val probeScope by unsafeLazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    private val probeInFlight = AtomicBoolean(false)

    /** 失败复探的最小间隔，防止一堆请求同时失败时把探活打成风暴。 */
    private const val REPROBE_MIN_INTERVAL_MS = 30_000L

    /**
     * 探活专用 client：**只有它自己用**，所以超时可以给得很短，
     * 不像播放链路那个 client 为了长视频必须把 `callTimeout` 设成 0。
     *
     * 没有显式设 `proxySelector` —— OkHttp 默认走 `ProxySelector.getDefault()`，
     * 而 [io.github.daisukikaffuchino.han1meviewer.HanimeApplication] 已经在启动时把它
     * 换成了 [HProxySelector]，所以这里自动继承了用户配置的代理（线路本来就正常的用户不必额外绕）。
     *
     * `internal` 而非 `private`：节点池的健康检查复用它，免得再搭一套 TLS 配置
     * （两份配置一旦漂移，就会出现「探活说通、实际连不上」这种最难查的不一致）。
     */
    internal val probeClient by unsafeLazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS + 2_000L, TimeUnit.MILLISECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

    /** 探活超时。中转要么秒回要么就是不通，5 s 足够。 */
    private const val PROBE_TIMEOUT_MS = 5_000L

    /** 当前**生效节点**的可达性结论；`null` = 还没探过，或缓存已过期。 */
    val cachedReachable: Boolean?
        get() = runCatching { RelayNodeStore.cachedActiveReachable }.getOrNull()

    /**
     * 「中转**确定**可达」—— 只有**最近一次探活就成功**时才为 `true`。
     *
     * ## 为什么不能拿 [cachedReachable] 当这个判据
     *
     * [cachedReachable] 的语义是「这台机器还在优选池里」，它对失败是**刻意宽容**的：
     * [RelayNodeStore.check] 里那句
     * `healthy = reachable || failures < FAILURE_THRESHOLD`
     * 意味着探活失败 1–2 次时它**仍然返回 `true`**（为了不让一次网络抖动就把节点踢出优选）。
     * 这对「在多台机器里挑一台」是对的，对「要不要**跳过直连**、强制改道」却是致命的：
     *
     * | 时刻 | [cachedReachable] | 真实情况 |
     * |---|---|---|
     * | 冷启动，健康结论还是空的 | `null` | 完全不知道中转通不通 |
     * | 启动预热那次探活失败，连败 1 次 | **`true`** | 中转其实已经下线 |
     * | 健康结论过期（[RelayNodeStore.HEALTH_TTL_MS] 10 分钟） | `null` | 又回到「不知道」 |
     *
     * 26.9.17 之前，[CdnRelayInterceptor] 对 Pornhub 系的判据是「[cachedReachable] != false
     * ⇒ 强制走中转」，于是上面三种情况**全部**会把请求送去一个连不上的地址。
     *
     * 所以这里把判据收紧成「**刚证明过它通**」：探活成功会把 `consecutiveFailures` 归零，
     * 因此 `health.healthy && consecutiveFailures == 0` 恰好等价于「最近一次探活通过」。
     * 中转真的可用时预热那一趟会立刻把它置为 `true`，一个请求都不多绕。
     */
    val relayConfirmedReachable: Boolean
        get() = runCatching {
            RelayNodeStore.healthOf(RelayNodeStore.activeNode().id)
                ?.let { it.healthy && it.consecutiveFailures == 0 } == true
        }.getOrDefault(false)

    /** 真正打一次 `/ping`。失败不抛，只会得到 `false`。 */
    suspend fun probe(force: Boolean = false): Boolean =
        runCatching { RelayNodeStore.checkActive(force = force) }.getOrDefault(false)

    /**
     * 启动时预热一次，让第一个视频请求不必先等一次探活超时。
     * 失败无所谓 —— 探活只影响「失败后要不要多绕一趟」，不影响主流程。
     */
    fun warmUp() {
        probeScope.launch { runCatching { probe(force = true) } }
    }

    /**
     * 中转请求刚失败时调用：**给当前节点记一次失败**，并在需要时后台复探。
     *
     * 为什么不直接判死：后果是「一次网络抖动 → 中转被停用 5 分钟 → 视频彻底看不了」，
     * 代价远大于收益。所以失败计数要累积到阈值（见 [RelayNodeStore.reportFailure]），
     * 而且自动优选开着时会**自动换到下一台**，不需要用户干预。
     */
    fun scheduleReprobe() {
        runCatching { RelayNodeStore.reportFailure() }
        val now = System.currentTimeMillis()
        if (now - probeAt < REPROBE_MIN_INTERVAL_MS) return
        if (!probeInFlight.compareAndSet(false, true)) return
        // ⚠️ 26.9.17 补上：原来这一行是缺的，`probeAt` 从初始化后就再没被写过（恒为 0），
        //    于是 `now - probeAt` 永远远大于 30 s ⇒ **节流条件形同虚设**。
        //    后果：中转下线时，每个失败的请求都会再去排一次探活（每个探活是 3 × 5 s 的
        //    阻塞式 ping），失败请求一多就变成持续的网络风暴，还会占满 IO 线程池。
        probeAt = now
        probeScope.launch {
            try {
                probe(force = true)
            } finally {
                probeInFlight.set(false)
            }
        }
    }

    @Volatile
    private var probeAt = 0L

    //</editor-fold>
}
