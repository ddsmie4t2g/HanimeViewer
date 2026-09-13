package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.ui.component.content.EmptyContent
import io.github.daisukikaffuchino.han1meviewer.ui.screen.artist.ArtistScreen

/**
 * 作者页路由。
 *
 * 只做两件事：把路由里的 JSON 还原成 [ArtistRef]，再交给
 * [ArtistScreen]。[ArtistRef] 解不出来时画空态而不是崩 —— 这个字符串由导航库在
 * 进程重建后还原，属于「我们控制不了」的输入。
 */
@Composable
fun ArtistRouteScreen(
    route: ArtistRoute,
    onBack: () -> Unit,
    onNavigateToVideo: (String) -> Unit,
) {
    val artist = remember(route.artistJson) { ArtistRef.decodeOrNull(route.artistJson) }
    if (artist == null || artist.name.isBlank()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyContent(
                hint = stringResource(R.string.artist_page_broken_args),
                picRes = R.drawable.h_chan_sad,
            )
        }
        return
    }
    ArtistScreen(
        artist = artist,
        navigateBack = onBack,
        onClickVideo = onNavigateToVideo,
    )
}
