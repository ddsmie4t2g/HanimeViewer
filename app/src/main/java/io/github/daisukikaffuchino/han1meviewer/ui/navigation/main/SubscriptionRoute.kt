package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.getHanimeSearchShareText
import io.github.daisukikaffuchino.han1meviewer.getHanimeShareText
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.SubscriptionScreen
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.MySubscriptionsViewModel
import io.github.daisukikaffuchino.utils.rememberCopyTextToClipboard
import io.github.daisukikaffuchino.utils.SonnerToast

/**
 * 订阅页路由。
 *
 * ⭐ 26.6.5 起「关注的作者」**一律进作者页**（[ArtistRoute]）：站点判定与取数分流都收在
 * [ArtistRef.siteSource] 与 `NetworkRepo.getArtistVideos` 里 —— 这样「在 hanime 域名下点一个
 * Pornhub 关注的人」也会正确地进 Pornhub 的作者页，而不是跑去 hanime 搜索（26.6.3 的 404）。
 */
@Composable
fun SubscriptionRouteScreen(
    onBack: () -> Unit,
    onNavigateToSearch: (String?) -> Unit,
    onNavigateToArtist: (ArtistRef) -> Unit,
    onNavigateToVideo: (String) -> Unit,
) {
    val viewModel: MySubscriptionsViewModel = viewModel()
    val copyTextToClipboard = rememberCopyTextToClipboard()
    SubscriptionScreen(
        navigateBack = onBack,
        viewModel = viewModel,
        onClickArtist = { onNavigateToSearch(it) },
        onLongClickArtist = { artistName ->
            copyTextToClipboard(getHanimeSearchShareText(artistName))
            SonnerToast.success(R.string.copy_to_clipboard)
        },
        onClickFollowed = { artist -> onNavigateToArtist(artist) },
        onLongClickFollowed = { artist ->
            copyTextToClipboard(getHanimeSearchShareText(artist.name))
            SonnerToast.success(R.string.copy_to_clipboard)
        },
        onClickVideosItem = onNavigateToVideo,
        onLongClickVideosItem = { videoCode, title ->
            copyTextToClipboard(getHanimeShareText(title, videoCode))
            SonnerToast.success(R.string.copy_to_clipboard)
        },
    )
}
