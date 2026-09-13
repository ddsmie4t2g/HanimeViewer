package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.han1meviewer.logic.hsex.HsexNetwork
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavNetwork

/**
 * 按**当前数据源**决定播放 / 下载某个地址时要带的请求头。
 *
 * 在这之前这件事是三处各写各的：nJAV 那条链路直接调
 * [NjavNetwork.playbackHeadersFor]，hanime 那条干脆不带。加上好色TV 之后
 * 再散着写就会变成「三个站点、三套写法」，将来任何一个站上了防盗链都要靠翻代码找。
 * 所以收成这一个入口 —— 新增站点只改这里。
 *
 * | 数据源 | 视频 CDN | 需要的头 |
 * |---|---|---|
 * | hanime | 各家直链 | 无 |
 * | nJAV | `surrit.com` / `fourhoi.com` | `Referer` + `Origin`（不带 → Cloudflare 403） |
 * | 好色TV | `*.hdcdn.online` | 无（实测裸请求 200） |
 *
 * ⚠️ **判定依据是 host，不是数据源**：各家的 `playbackHeadersFor` 内部都会先看
 * host 在不在自己的 CDN 名单里，不在就返回空表。所以即使数据源判断错了、
 * 把 hanime 的地址交给 nJAV 那套逻辑，结果也只是「没有头」而不是「乱加头」。
 */
object PlaybackHeaders {

    fun forUrl(url: String): Map<String, String> = when {
        SettingsRepository.isNjavSite -> NjavNetwork.playbackHeadersFor(url)
        SettingsRepository.isHsexSite -> HsexNetwork.playbackHeadersFor(url)
        else -> emptyMap()
    }
}
