package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavNetwork
import io.github.daisukikaffuchino.han1meviewer.logic.ph.PhNetwork

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
 * （`.ts` 分片不带会 404），但**不能在这条链路上加** —— 那个请求会被换成自建中转地址，
 * 中转服务器才是真正与 CDN 对话的一方，它需要的 Referer 是**服务器侧**按中转 URL 的
 * `?ref=` 补的（见 [io.github.daisukikaffuchino.han1meviewer.logic.network.CdnRelay.refererFor]）。
 * 客户端这边加了也带不过去。所以这里返回空表**是正确行为，不是漏做**。
 */
object PlaybackHeaders {

    fun forUrl(url: String): Map<String, String> = when {
        SettingsRepository.isNjavSite -> NjavNetwork.playbackHeadersFor(url)
        SettingsRepository.isPornhubSite -> PhNetwork.playbackHeadersFor(url)
        else -> emptyMap()
    }
}
