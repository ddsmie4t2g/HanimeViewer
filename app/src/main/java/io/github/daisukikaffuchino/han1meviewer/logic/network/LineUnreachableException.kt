package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.han1meviewer.ui.player.PlaybackLoadErrorPolicy
import io.github.daisukikaffuchino.han1meviewer.util.toNetworkErrorMessageRes
import java.io.IOException

/**
 * 「这条线路现在没有出口」—— 目标域名直连已被实测判死，而自建 TLS 中转也不可用。
 *
 * ## 为什么需要一个专门的类型，而不是直接抛原始的 `SocketException`
 *
 * 因为**上层必须区分「等一等会好」和「等多久都不会好」**，而这两件事在下层长得一模一样：
 * 都是 `SocketException: Connection reset`。判错的代价是不对称的 ——
 *
 * | 上层 | 拿到原始 RST | 拿到本异常 |
 * |---|---|---|
 * | [PlaybackLoadErrorPolicy] | 归进「其它传输错误」，预算 15 次 ⇒ 每次都要**再付一次撞墙的 0.8–2.6 s**，合计 ≈ 100 s 的转圈 | 预算 2 次，而本异常是**本地立刻抛出**的（不发请求）⇒ ≈ 3 s 就给出结论 |
 * | [toNetworkErrorMessageRes] | 落进 `home_error_connection_reset`（「网络不稳定」），用户看不出该做什么 | 落进 `home_error_line_unreachable`，直接指出「要开代理」 |
 *
 * ⭐⭐ 26.9.19 的「一直加载转圈、视频转不出来」就是第一行那个组合造成的：hembed 直连
 * 100% 被 RST、自建中转已于 26.9.17 下线，于是每个请求都要真实地撞一次墙，
 * 而 15 次的预算把「一次失败」放大成了一分多钟的转圈。
 *
 * ## 判据是「客户端的既有结论」，不是这条异常本身
 *
 * 抛它的前提是 [CdnRelay.isKnownDead] 为真（直连已验死）**且** [CdnRelay.isRelayWorthTrying]
 * 为假（中转不可用）—— 两者都来自**已经观测过**的事实，所以它比原始异常更能说明
 * 「现在该做什么」。见 `CdnRelayInterceptor` 里那三处抛出点。
 *
 * ## ⚠️ 它不是「永久封禁」，拿到它不必重启 App
 *
 * [CdnRelay.isKnownDead] 的结论带 TTL（`CdnRelay.DIRECT_DEAD_TTL_MS`）：用户中途挂上
 * 代理 / VPN 之后，最多一个 TTL 就会有请求重新去验一次直连，通了立刻自动恢复 ——
 * 而 `PlaybackLoadErrorPolicy` 给的那 2 次重试正好覆盖这个窗口。
 *
 * @param host 目标域名（用于日志与文案定位，如 `vdownload.hembed.com`）。
 */
class LineUnreachableException(
    val host: String,
) : IOException("line-unreachable: $host")
