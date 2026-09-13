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
 * ⭐ 26.6.3 起「关注的作者」不再统一跳搜索：有站点作者页的（Pornhub `/pornstar|/model`、
 * nJAV `/actresses`）直接进 [ArtistRoute]，其余（hanime 全部、Pornhub 的 `/users/…`）
 * 才退回按名字搜索 —— 判据是 [ArtistRef.hasArtistPage]，与详情页点作者走的是同一条规则。
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
        onClickFollowed = { artist ->
            if (artist.hasArtistPage) {
                onNavigateToArtist(artist)
            } else {
                onNavigateToSearch(artist.name)
            }
        },
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
