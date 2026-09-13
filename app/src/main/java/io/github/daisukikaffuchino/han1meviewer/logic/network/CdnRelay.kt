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
        "fourhoi.com",  // nJAV 封面
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
