package io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor

import io.github.daisukikaffuchino.han1meviewer.logic.network.CdnRelay
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
            // 已验证过直连必死。中转若也被探活判死，就别再绕这一趟了 ——
            // 直接把**直连的原始错误**抛出去：报错指向真实原因，还省掉一次白等的超时。
            if (CdnRelay.cachedReachable == false) return chain.proceed(request)
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
            if (CdnRelay.cachedReachable == false) return chain.proceed(request)
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
        attempt.getOrNull()?.let { if (it.isSuccessful) return it }

        // 走到这里只可能是「抛异常」。4xx/5xx 的场景在上面就已经返回了。
        attempt.getOrNull()?.close()
        val cause = attempt.exceptionOrNull()
        if (cause == null) return chain.proceed(request)

        CdnRelay.markKnownDead(request.url.host.lowercase())
        LogUtil.w(TAG, "直连失败，改走中转: ${request.url} (${cause.message})")
        // 中转也被判死时别再白等一次超时 —— 抛直连的错误，那才是根因。
        if (CdnRelay.cachedReachable == false) throw cause
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
            chain.proceed(request.newBuilder().url(forwarded).build())
        } catch (e: IOException) {
            // 中转自己也可能失败。这里**只安排一次后台复探，不立刻把中转判死** ——
            // 一次网络抖动就把中转停用 5 分钟，代价（视频看不了）远大于收益。
            // 真死了的话复探会记下来，下一个请求就不再白绕。
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
