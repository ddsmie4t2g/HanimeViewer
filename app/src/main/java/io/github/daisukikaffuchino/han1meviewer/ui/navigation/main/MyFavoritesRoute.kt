package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.getHanimeShareText
import io.github.daisukikaffuchino.han1meviewer.logic.LocalListRepository
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.myplaylist.PlaylistScreen
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.LocalPlayListViewModel
import io.github.daisukikaffuchino.utils.SonnerToast
import io.github.daisukikaffuchino.utils.rememberCopyTextToClipboard

/**
 * **收藏夹**（9.0 新增）。
 *
 * ⭐ 为什么不像 [MyPlaylistRouteScreen] 那样按登录态分流：
 * 收藏夹是**本机**数据（和「关注」「稍后再看」一类），存在 Room 里，
 * 三个站点通用。挂到 hanime 账号下会变成「没登录就没有收藏夹」——
 * 而用户要的恰恰是「我自己的收藏，不该被某个站点的登录挡住」。
 *
 * ⭐ 复用 [PlaylistScreen]：新建 / 改名 / 删除 / 增删条目这套逻辑与播放清单
 * 一模一样，差的只是**查哪一类**（[LocalListRepository.FAVORITE_COLLECTION_KIND]）
 * 和几处文案。
 */
@Composable
fun MyFavoritesRouteScreen(
    onBack: () -> Unit,
    onNavigateToVideo: (String) -> Unit,
) {
    val copyTextToClipboard = rememberCopyTextToClipboard()
    val viewModel: LocalPlayListViewModel = viewModel(key = "local_favorites") {
        LocalPlayListViewModel(LocalListRepository.FAVORITE_COLLECTION_KIND)
    }
    PlaylistScreen(
        viewModel = viewModel,
        navigateBack = onBack,
        onClickItem = onNavigateToVideo,
        onLongClickItem = { videoCode, title ->
            copyTextToClipboard(getHanimeShareText(title, videoCode))
            SonnerToast.success(R.string.copy_to_clipboard)
        },
        titleRes = R.string.favorite_collections,
        createLabelRes = R.string.create_new_collection,
        emptyListRes = R.string.no_favorite_collection_hint,
    )
}
