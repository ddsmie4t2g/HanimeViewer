package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.han1meviewer.logic.network.CdnRelay
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavNetwork
import io.github.daisukikaffuchino.han1meviewer.logic.ph.PhNetwork
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 按**当前数据源**决定播放 / 下载某个地址时要带的请求头。
 *
 * 在这之前这件事是三处各写各的：nJAV 那条链路直接调
 * [NjavNetwork.playbackHeadersFor]，hanime 那条干脆不带。加上第三个数据源之后
 * 再散着写就会变成「三个站点、三套写法」，将来任何一个站上了防盗链都要靠翻代码找。
 * 所以收成这一个入口 —— 新增站点只改这里。
 *
 * | 数据源 | 视频 CDN | 需要的头 |
 * |---|---|---|
 * | hanime | 各家直链 | 无 |
 * | nJAV | `surrit.com` / `fourhoi.com` | `Referer` + `Origin`（不带 → Cloudflare 403） |
 * | Pornhub | `*.phncdn.com` | 客户端**不带**（Referer 由自建中转补，见下） |
 *
 * ⚠️ **判定依据是 host，不是数据源**：各家的 `playbackHeadersFor` 内部都会先看
 * host 在不在自己的 CDN 名单里，不在就返回空表。所以即使数据源判断错了、
 * 把 hanime 的地址交给 nJAV 那套逻辑，结果也只是「没有头」而不是「乱加头」。
 *
 * ⚠️ Pornhub 是个例外，值得单独记一笔：它的 CDN 确实要 `Referer`
 * （`.ts` 分片不带会 404），但**中转可用时不能在这条链路上加** —— 那个请求会被换成
 * 自建中转地址，中转服务器才是真正与 CDN 对话的一方，它需要的 Referer 是**服务器侧**
 * 按中转 URL 的 `?ref=` 补的（见 [CdnRelay.refererFor]）。客户端这边加了也带不过去。
 * 所以中转可用时返回空表**是正确行为，不是漏做**。
 *
 * ⭐ 26.9.18 补上了另一半：**中转不可用时，客户端必须自己补**（见 [pornhubSegmentReferer]）。
 * 中转下线后请求走直连（或用户的代理），与 CDN 对话的就是客户端自己，那条 `?ref=` 通道
 * 整条消失 ⇒ `.ts` 分片 404。这解释了为什么「挂了代理还是 404」—— 缺的是请求头，不是线路。
 */
object PlaybackHeaders {

    fun forUrl(url: String): Map<String, String> = when {
        SettingsRepository.isNjavSite -> NjavNetwork.playbackHeadersFor(url)
        SettingsRepository.isPornhubSite ->
            PhNetwork.playbackHeadersFor(url).ifEmpty { pornhubSegmentReferer(url) }

        else -> emptyMap()
    }

    /**
     * 自建中转不可用时，给 Pornhub 的**媒体分片**补上它要的 `Referer`。
     *
     * 判据是 [CdnRelay.relayConfirmedReachable]（「刚被证明可达」），而不是
     * 「中转不可达」的否命题 —— 两者不是一回事：冷启动时探活还没跑，
     * 那时应该**保持老路径不动**（如果不巧中转是好的，加了头反而是新变量）。
     *
     * ⚠️ 只给 `.ts` / `.mp4` 加，**不给清单（`.m3u8`）加**：实测 Pornhub 的主清单与
     * 子清单**不要** Referer，而 `.ts` 分片不带会 404。这个区分是实测结论，
     * 别为了「统一」把清单也带上。
     */
    private fun pornhubSegmentReferer(url: String): Map<String, String> {
        if (runCatching { CdnRelay.relayConfirmedReachable }.getOrDefault(false)) return emptyMap()
        val parsed = url.toHttpUrlOrNull() ?: return emptyMap()
        val path = parsed.encodedPath.lowercase()
        if (!path.endsWith(".ts") && !path.endsWith(".mp4")) return emptyMap()
        val referer = CdnRelay.refererFor(parsed.host) ?: return emptyMap()
        return mapOf("Referer" to referer)
    }
}
