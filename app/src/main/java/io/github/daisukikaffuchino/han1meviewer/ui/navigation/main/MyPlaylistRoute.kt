package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.getHanimeShareText
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.myplaylist.PlaylistScreen
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.LocalPlayListViewModel
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.MyPlayListViewModel
import io.github.daisukikaffuchino.utils.rememberCopyTextToClipboard
import io.github.daisukikaffuchino.utils.SonnerToast

@Composable
fun MyPlaylistRouteScreen(
    onBack: () -> Unit,
    onNavigateToVideo: (String) -> Unit,
) {
    val isLoggedIn by SettingsRepository.loginStateFlow.collectAsStateWithLifecycle()
    val copyTextToClipboard = rememberCopyTextToClipboard()
    if (isLoggedIn) {
        val viewModel: MyPlayListViewModel = viewModel(key = "online_playlist")
        PlaylistScreen(
            viewModel = viewModel,
            navigateBack = onBack,
            onClickItem = onNavigateToVideo,
            onLongClickItem = { videoCode, title ->
                copyTextToClipboard(getHanimeShareText(title, videoCode))
                SonnerToast.success(R.string.copy_to_clipboard)
            },
        )
    } else {
        // ⭐ 9.0：`LocalPlayListViewModel` 现在带一个 `kind` 参数（默认 = 播放清单），
        // 所以显式给 initializer，不再依赖「全默认参数 ⇒ 合成无参构造 + 反射」那条路。
        val viewModel: LocalPlayListViewModel = viewModel(key = "local_playlist") {
            LocalPlayListViewModel()
        }
        PlaylistScreen(
            viewModel = viewModel,
            navigateBack = onBack,
            onClickItem = onNavigateToVideo,
            onLongClickItem = { videoCode, title ->
                copyTextToClipboard(getHanimeShareText(title, videoCode))
                SonnerToast.success(R.string.copy_to_clipboard)
            },
        )
    }
}
