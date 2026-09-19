package io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor

import io.github.daisukikaffuchino.han1meviewer.logic.network.CdnRelay
import io.github.daisukikaffuchino.han1meviewer.logic.network.LineUnreachableException
import io.github.daisukikaffuchino.utils.LogUtil
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 把被封域名的请求改道到自建 TLS 中转。
 *
 * 覆盖两类目标（见 [CdnRelay.BLOCKED_HOSTS]）：
 *
 * | 域名 | 大陆直连 | 策略 |
 * |---|---|---|
 * | `vdownload.hembed.com`、`fourhoi.com` | 立即 RST | **直连优先，失败才中转**（海外用户直连更快） |
 * | `pornhub.com`、`*.phncdn.com` | 立即 RST（SNI 阻断，换 IP 也没用） | **一律中转**，不试直连（见 [CdnRelay.mustRelay]） |
 *
 * ## ⭐ 中转**自己也连不上**时的降级（26.9.18 引入）
 *
 * 上表假设「中转可用」。中转下线时（进程停了 / 端口被拒 / IP 被临时封），
 * 若不做处理，`mustRelay` 的域名会变成**每个请求都白做两遍无用功**
 * （撞墙 0.1–0.3 s → 再绕一次连不上的中转），首页十几个并发叠起来，
 * 用户看到的不是报错而是**转圈然后白屏**。
 *
 * 所以 [relay] 在**连接层失败**时调 [CdnRelay.markRelayUnreachable]，
 * 之后所有判据统一走 [CdnRelay.isRelayWorthTrying]（= 节点没被判死 **且** 本会话没撞过
 * 连接失败）：不值得试就**别再绕**，改走直连（挂代理 / VPN 的用户直连本来就能通）。
 *
 * ⚠️ 26.9.18 二次收紧：这个标记**不再随时间失效**。解除只有两条途径 ——
 * ① 探活成功（`CdnRelay.probe`）；② [relay] 真的取回响应（[CdnRelay.markRelayReachable]）。
 *
 * 为什么不能用时间解除：TTL 到期那一刻，同一批并发请求（Pornhub 首页十几张封面）
 * 会被**一起**放行去撞那台已经死掉的中转，于是每隔两分钟就来一次「整批封面集体
 * loadfailed」。改成「到点只排一次探活」之后，业务请求不再拿自己当探针。
 * 详见 `CdnRelay.RELAY_REVERIFY_AFTER_MS` 的注释。
 *
 * ## ⭐⭐ 26.9.19：「没有任何出路」要抛 [LineUnreachableException]，不能只是「走直连」
 *
 * 26.9.18 那套降级的落点是「直连」（`chain.proceed`）。中转已经下线时这等于
 * **把请求交给一条确定不通的路**，而它抛出的原始 RST 在上层看来是「传输被掐、
 * 重试就会好」—— 播放链路会重试 15 次，每一次都真实地再撞一次墙（0.8–2.6 s）
 * ⇒ **一分多钟的转圈**，而用户一个字提示都看不到。这就是 26.9.19 用户报的
 * 「能进视频界面，但一直加载转圈、视频转不出来」。
 *
 * 现在三处「确定没有出路」的落点统一改抛 [LineUnreachableException]：
 *
 * | 落点 | 条件 |
 * |---|---|
 * | [intercept] 里「该 host 直连已验死」那条分支 | [CdnRelay.isDeadEnd] |
 * | [directThenRelay] 收尾（直连刚失败、中转也不值得一试） | [CdnRelay.isDeadEnd] |
 *
 * 它是**本地立刻抛出**的，因此上层的 2 次重试几乎不花时间，3 秒内就能把
 * 「要开代理」这件事说清楚。
 *
 * ⚠️ 与之配套的是 [CdnRelay] 里新加的 `DIRECT_DEAD_TTL_MS`：「直连必死」的结论必须
 * 会过期，否则用户中途挂上代理也恢复不了（只剩重启 App 一条路）。两处改动是一体的，
 * 不要只留一处。
 *
 * ## 直连成功时要**撤销**「直连必死」的结论
 *
 * 用户可能中途挂上代理 / VPN，那一刻起直连本来是通的。所以 [directThenRelay] 在真的
 * 拿到成功响应时会调 [CdnRelay.markDirectAlive] 把该 host 从「已知死」里摘出来，
 * 否则它会永远被当作「直连没戏」，每次先白绕一趟中转。
 *
 * 背景与原理见 [CdnRelay] 的类注释 —— 一句话：这些域名在内地**连代理都救不了**，
 * 因为 TLS 的 SNI 是明文，墙在明文隧道里就能读到并 RST，只有「中转站终结 TLS」这一条路。
 *
 * ## 为什么 hembed 那类要「直连优先」
 *
 * | 用户位置 | 直连 | 结果 |
 * |---|---|---|
 * | 海外 / 港澳台 | 通 | 完全不碰中转，最快也最省流量 |
 * | 内地 | 立即 RST | 失败一次（0.8–2.6 s），随后本进程内记住结论 |
 *
 * 判定**只认异常（[IOException]）**，不认 4xx/5xx：`hembed` 在签名过期时会返回 403，
 * 那是「链接坏了」而不是「线路被墙了」，这种请求送去中转也一样是 403，
 * 并会平白把「直连线路已死」的错误结论写进缓存。
 *
 * 记忆是会话级的（见 [CdnRelay.isKnownDead]）：一个视频播放会发几十上百个 Range 请求，
 * 每次都先撞一次墙的话，白等的时间比下载本身还长。
 *
 * ## 只改 URL，不动其它
 *
 * 请求头（`Range`、`User-Agent`，以及 nJAV 那条链路的 `Referer`）原样带过去 ——
 * 视频的 `Range` 是播放器跳转/拖动的基础，少一个字节都可能表现为「拖不动」。
 * 唯一的例外是需要补 `Referer` 的域名（Pornhub CDN 的分片），那是**服务器侧**用
 * 中转 URL 上的 `?ref=` 参数补的，不在此处动客户端请求头。
 */
class CdnRelayInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host.lowercase()
        if (!CdnRelay.isRelayHost(host)) return chain.proceed(request)
        // 用户可在「网络设置 → CDN 中转」关掉。每次请求都读一次，改设置立即生效。
        if (!CdnRelay.enabled) return chain.proceed(request)

        // ⭐ 26.9.17 修「Pornhub 开代理首页白屏」：判据从「`cachedReachable != false`」收紧成
        //    「**刚刚证明过中转通**」（[CdnRelay.relayConfirmedReachable]）。
        //
        //    旧判据在下面两种情况下都不等于 false，于是照样强制改道：
        //      ① 冷启动时健康结论还没产生，或结论过期 10 分钟后 ⇒ `null`；
        //      ② 启动预热那次探活失败、但连败还没到阈值（`healthy = failures < 3`）⇒ `true`。
        //    结果：中转已经下线时，Pornhub 首页那 10 个 JSON + 1 个主页 HTML（**全部并发**）
        //    会被一起送去一个连不上的地址，而首页那边又把每节的异常吞成空列表 ⇒ 一片空白。
        //    可用户此刻开着系统 VPN / 代理，直连本来是**能通**的 —— 是这里没让他走。
        //
        //    中转活着时，预热那一趟探活就会把结论置为 true，一个请求都不会多绕；
        //    中转不在时，请求老实走直连，交给用户的代理去接。
        if (CdnRelay.mustRelay(host) && CdnRelay.relayConfirmedReachable) {
            return relay(chain, request, null)
        }

        if (CdnRelay.isKnownDead(host)) {
            // 已验证过直连必死。中转若也不值得一试，就是**一条路都不剩**了 ——
            // ⭐⭐ 26.9.19：这里抛 [LineUnreachableException] 而不是直连的原始 RST。
            //
            //    两者对用户是**天壤之别**：原始 RST（`SocketException: Connection reset`）
            //    在 `PlaybackLoadErrorPolicy` 眼里属于「传输被掐、重试就会好」，预算 15 次 ——
            //    而每一次重试都要真实地再撞一次墙（0.8–2.6 s），合计 ≈ 100 s 的转圈，
            //    用户看到的就是「一直加载、视频转不出来」。
            //
            //    本异常是**本地立刻抛出**的（不发任何请求），且带类型语义 ⇒ 预算降到 2 次、
            //    文案直接指向「要开代理」。代价从「一分多钟的白等」变成「三秒内说清」。
            //
            // ⚠️ 判据用 [CdnRelay.isDeadEnd]（= 直连已验死 **且** 中转不值得一试），
            //    两个条件都是已观测的事实；缺任何一个都说明还值得试，不能抛。
            if (CdnRelay.isDeadEnd(host)) throw LineUnreachableException(host)
            return relay(chain, request, null)
        }

        // ⭐ 26.9.17：Pornhub 系**不走下面那个「只放一个探子」的闸门**，理由是代价不对称。
        //    闸门是为 hembed 那类「直连失败要白等 0.8–2.6 s」的域名设计的；
        //    而 Pornhub 系的直连失败是**立即 RST**（实测 0.1–0.3 s），撞一次几乎不要钱，
        //    反倒是**漏判**很贵：冷启动时首页 10 个请求同时到达，闸门只放 1 个去试直连，
        //    另外 9 个被直接送进（可能已下线的）中转 —— 有代理的用户本来直连能通，却被这 9 次绕路毁掉。
        if (CdnRelay.mustRelay(host)) return directThenRelay(chain, request)

        // 同一 host 的并发请求里只让**第一个**去试直连（见 CdnRelay.tryBeginDirectProbe）。
        // 冷启动时首页那 20–30 张封面是同时发的，如果一个一个去撞墙，
        // 「一次 2 秒」会变成「30 次 2 秒」压在同一条网络上，用户看到的就是
        // 「一打开 App，封面转半天」。拿不到闸门的请求直接先走中转 ——
        // 反正直连能不能通是 host 级的事实，探一次就够了。
        if (!CdnRelay.tryBeginDirectProbe(host)) {
            // ⭐ 26.9.18：同上，中转不值得一试时直接走直连，别白绕。
            if (!CdnRelay.isRelayWorthTrying()) return chain.proceed(request)
            return relay(chain, request, null)
        }

        val direct = try {
            runCatching { chain.proceed(request) }
        } finally {
            // ⚠️ 必须在 finally 里释放：链上任何一环抛异常都要让下一个请求能再试，
            //    否则该 host 这一次会话就永远被当作「正在探测」，全走中转。
            CdnRelay.endDirectProbe(host)
        }
        return directThenRelay(chain, request, direct)
    }

    /**
     * 「直连优先、失败才中转」这条公共尾巴。
     *
     * [direct] 非空表示直连已经试过一次（主流程在闸门内试的），直接复用结果；
     * 为空则自己试一次。与主流程的唯一差别是**完全不碰
     * [CdnRelay.tryBeginDirectProbe] 闸门** —— 理由见 [intercept] 里的注释。
     */
    private fun directThenRelay(
        chain: Interceptor.Chain,
        request: Request,
        direct: Result<Response>? = null,
    ): Response {
        val attempt = direct ?: runCatching { chain.proceed(request) }
        attempt.getOrNull()?.let {
            if (it.isSuccessful) {
                // ⭐ 26.9.18：直连真的走通了 ⇒ 撤销「该 host 直连必死」的结论。
                //    用户完全可能中途挂上代理 / VPN，那一刻起直连本来是通的；
                //    不清除的话，该 host 会被永远当作「直连没戏」，每次先白绕一趟中转。
                CdnRelay.markDirectAlive(request.url.host.lowercase())
                return it
            }
        }

        // 走到这里只可能是「抛异常」。4xx/5xx 的场景在上面就已经返回了。
        attempt.getOrNull()?.close()
        val cause = attempt.exceptionOrNull()
        if (cause == null) return chain.proceed(request)

        CdnRelay.markKnownDead(request.url.host.lowercase())
        LogUtil.w(TAG, "直连失败，改走中转: ${request.url} (${cause.message})")
        // 中转也不值得一试 ⇒ 一条路都不剩了（直连刚被验死 + 中转不可用），
        // 抛 [LineUnreachableException] 而不是原始 `cause`。这不是「换个异常抛」那么轻：
        // 原始 `cause` 是 RST，上层会按「掐一下、重试就好」处理 15 次
        // （每次重试都要真实地再撞一次墙）；而本异常是本地立刻抛出的，
        // 预算 2 次、文案直接指向「要开代理」。详见 A 处那段注释。
        if (CdnRelay.isDeadEnd(request.url.host.lowercase())) {
            throw LineUnreachableException(request.url.host.lowercase())
        }
        return relay(chain, request, cause)
    }

    /**
     * 用中转地址重放同一请求。
     *
     * [cause] 非空表示这次重放是「直连失败后的兜底」，中转再失败时**抛直连的错** ——
     * 那才是根因，中转失败通常只是次生现象。
     */
    private fun relay(chain: Interceptor.Chain, request: Request, cause: Throwable?): Response {
        val ref = CdnRelay.refererFor(request.url.host)
        val forwarded = CdnRelay.relayUrl(request.url.toString(), ref)
            ?: return cause?.let { throw it } ?: chain.proceed(request)

        return try {
            val response = chain.proceed(request.newBuilder().url(forwarded).build())
            // ⭐ 26.9.18：真的把响应取回来了 = 这条路可用，解除会话级「中转连接不上」标记。
            CdnRelay.markRelayReachable()
            response
        } catch (e: IOException) {
            // 中转自己也可能失败。这里分两种，处理方式不同：
            //
            // ① **连接层失败**（端口拒绝 / 超时 / TLS 握手失败）⇒ 「中转这台机器不可达」
            //    是**机器级事实**，记进本会话，后面的请求不再各绕一遍。
            //    对 `mustRelay` 的域名尤其关键：它们直连必死、中转是唯一出路，
            //    不记就是首页十几个并发请求全部白等两遍（体感 = 转圈然后白屏）。
            // ② 其它 IOException（上游把连接掐了之类）⇒ 中转本身是活的，**不标记**。
            if (CdnRelay.isConnectionLevelFailure(e)) CdnRelay.markRelayUnreachable()
            // 不立刻把节点整个判死：一次网络抖动就把中转停用，代价（视频看不了）远大于收益。
            // 真死了的话复探会记下来。
            CdnRelay.scheduleReprobe()
            // 抛直连的错更能说明问题（中转失败通常是次生现象）。
            cause?.let { throw it }
            throw e
        }
    }

    private companion object {
        const val TAG = "CdnRelay"
    }
}
