package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import androidx.compose.runtime.Composable
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.logic.model.NjavActress
import io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavNetwork
import io.github.daisukikaffuchino.han1meviewer.ui.screen.actress.ActressGalleryScreen

/**
 * 女优一览 / 排行页路由（26.8.2 新增）。
 *
 * 这里只做一件事：把点中的 [NjavActress] 翻成作者页要的 [ArtistRef]。
 *
 * ## ⭐ 为什么要在这里就把头像带上
 *
 * nJAV 的**视频详情页给不出女优头像**（卡片里只有名字），所以从详情页进作者页时，
 * 头像要靠「拿名字回女优索引里翻」补上 —— 那是用户抱怨的「头像不能很快显示出来」。
 * 而**女优一览 / 排行页的卡片里本来就有头像**，所以从这一页点进去时把它一起带过去，
 * 作者页根本不需要再找。
 *
 * ## url 的形态必须与详情页给的一致
 *
 * [ArtistRef.followKey] 优先用 url 认身份，所以这里刻意用
 * [NjavNetwork.detailUrl]（= `https://njavtv.com/actresses/<编码名>`，**不带 `/cn/`**），
 * 和女优详情页里那个 `<a href>` 完全同形。写成带 `/cn/` 的话，
 * 「从视频页关注」与「从女优一览关注」会是两条不同的身份 → 同一人却显示成未关注。
 */
@Composable
fun ActressGalleryRouteScreen(
    navigateBack: () -> Unit,
    onNavigateToArtist: (ArtistRef) -> Unit,
) {
    ActressGalleryScreen(
        navigateBack = navigateBack,
        onClickActress = { actress -> onNavigateToArtist(actress.toArtistRef()) },
    )
}

/** 女优索引里的条目 → 作者页身份（头像 / 作品数都直接带过去）。 */
private fun NjavActress.toArtistRef(): ArtistRef = ArtistRef(
    name = name,
    avatar = avatarUrl,
    url = NjavNetwork.detailUrl(path),
    // 与 `ArtistViewModel` 补头像时写的那份文案保持一致（`5669 部影片`）。
    videoCount = videoCount?.let { "$it 部影片" }.orEmpty(),
    site = SiteSource.Njav.value,
)
