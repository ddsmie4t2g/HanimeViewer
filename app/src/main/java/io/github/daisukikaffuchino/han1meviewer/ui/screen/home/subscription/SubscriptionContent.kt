package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.subscription

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.logic.model.SubscriptionItem
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.ui.component.ArtistItem
import io.github.daisukikaffuchino.han1meviewer.ui.component.LoadMoreFooter
import io.github.daisukikaffuchino.han1meviewer.ui.component.VideoCardItem
import io.github.daisukikaffuchino.han1meviewer.ui.component.lazy.LazyVerticalGrid
import io.github.daisukikaffuchino.han1meviewer.ui.preview.fakeArtists
import io.github.daisukikaffuchino.han1meviewer.ui.preview.fakeVideos
import io.github.daisukikaffuchino.han1meviewer.ui.screen.rememberVideoGridColumns
import io.github.daisukikaffuchino.han1meviewer.ui.theme.ArtistIconSize
import io.github.daisukikaffuchino.han1meviewer.ui.theme.SpacingNormal
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 订阅页面 Content 层。纯 UI，不持有 ViewModel。
 *
 * ## 页面结构（26.6.3 起分成互不相干的两段）
 *
 * ```
 * 关注的作者（本机，三站通用，不需要登录）   ← 空的时候给一句「怎么关注」的提示
 * ────────────────────────────────────────
 * hanime 订阅（服务端，登录后才有）          ← 没登录时给一句说明，**不发任何请求**
 * ────────────────────────────────────────
 * 订阅视频流（服务端，登录后才有）
 * ```
 *
 * 之所以拆开：关注是纯本机的事（谁都不该被登录挡住），服务端订阅只存在于 hanime。
 * 合成一段画会让人分不清「这个作者为什么在这儿」，也解释不了「没登录为什么整页报错」。
 *
 * @param uiState 页面 UI 状态
 * @param onEvent 用户事件回调
 * @param gridState LazyGrid 滚动状态（UI 框架层）
 * @param artistRows 作者格子每个纵列放几个（用户可设，见 `subscriptionArtistRows`）
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SubscriptionContent(
    uiState: SubscriptionUiState,
    onEvent: (SubscriptionEvent) -> Unit,
    gridState: LazyGridState,
    artistRows: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val screenWidthPx = windowInfo.containerSize.width
    val screenWidthDp = with(density) { screenWidthPx.toDp() }
    val videoColumns = rememberVideoGridColumns()
    val artistColumns = maxOf(
        3,
        ((screenWidthDp + SpacingNormal) / (ArtistIconSize + SpacingNormal)).toInt()
    )
    var currentPage by remember { mutableIntStateOf(1) }
    val pageSize = 60

    LaunchedEffect(gridState, uiState.videos.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo }
            .map { it.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex != null &&
                    lastVisibleIndex >= uiState.videos.size - 4 &&
                    uiState.videos.size >= currentPage * pageSize &&
                    uiState.canLoadMore
                ) {
                    currentPage += 1
                    onEvent(SubscriptionEvent.OnLoadMore)
                }
            }
    }

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(videoColumns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = SpacingNormal),
            horizontalArrangement = Arrangement.spacedBy(SpacingNormal),
            verticalArrangement = Arrangement.spacedBy(SpacingNormal)
        ) {
            // ── 第一段：本机关注的作者 ────────────────────────────────────────
            item(span = { GridItemSpan(videoColumns) }) {
                if (uiState.followed.isEmpty()) {
                    SectionHint(stringResource(R.string.subscription_followed_empty_hint))
                } else {
                    AnimatedContent(
                        targetState = uiState.followed,
                        label = "followed-artist-animation",
                        transitionSpec = {
                            fadeIn(tween(300)) togetherWith fadeOut(tween(200))
                        }
                    ) { artists ->
                        ArtistListSection(
                            cards = artists.map {
                                ArtistCard(
                                    name = it.name,
                                    avatar = it.avatar,
                                    // 跨站显示的关注：每张卡标出站点，点进去才知道会去哪。
                                    badge = stringResource(it.siteLabelRes),
                                )
                            },
                            title = stringResource(
                                R.string.subscription_followed_artists_count,
                                artists.size,
                            ),
                            artistRows = artistRows,
                            artistColumns = artistColumns,
                            onClickArtist = { index ->
                                artists.getOrNull(index)?.let {
                                    onEvent(SubscriptionEvent.OnClickFollowed(it))
                                }
                            },
                            onLongClickArtist = { index ->
                                artists.getOrNull(index)?.let {
                                    onEvent(SubscriptionEvent.OnLongClickFollowed(it))
                                }
                            },
                        )
                    }
                }
            }

            // ── 第二段：hanime 服务端订阅 ─────────────────────────────────────
            item(span = { GridItemSpan(videoColumns) }) {
                if (!uiState.isLoggedIn) {
                    SectionHint(stringResource(R.string.subscription_login_hint))
                } else {
                    AnimatedContent(
                        targetState = uiState.artists,
                        label = "artist-animation",
                        transitionSpec = {
                            fadeIn(tween(300)) togetherWith fadeOut(tween(200))
                        }
                    ) { artists ->
                        ArtistListSection(
                            cards = artists.map { ArtistCard(it.artistName, it.avatar) },
                            title = stringResource(
                                R.string.subscription_server_artists_count,
                                artists.size,
                            ),
                            artistRows = artistRows,
                            artistColumns = artistColumns,
                            onClickArtist = { index ->
                                artists.getOrNull(index)?.let {
                                    onEvent(SubscriptionEvent.OnClickArtist(it.artistName))
                                }
                            },
                            onLongClickArtist = { index ->
                                artists.getOrNull(index)?.let {
                                    onEvent(SubscriptionEvent.OnLongClickArtist(it.artistName))
                                }
                            },
                        )
                    }
                }
            }

            if (uiState.isLoggedIn) {
                item(span = { GridItemSpan(videoColumns) }) {
                    HorizontalDivider(
                        modifier = Modifier.fillMaxWidth(),
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }

                items(
                    items = uiState.videos,
                    key = { it.videoCode }
                ) { video ->
                    Box(
                        modifier = Modifier.animateItem()
                    ) {
                        VideoCardItem(
                            videoItem = video,
                            onClickVideosItem = {
                                onEvent(
                                    SubscriptionEvent.OnClickVideo(video.videoCode)
                                )
                            },
                            onLongClickVideosItem = { _, _ -> },
                        )
                    }
                }
                if (uiState.videos.isNotEmpty()) {
                    item(span = { GridItemSpan(videoColumns) }) {
                        LoadMoreFooter(
                            state = PageLoadingState.Success(emptyList<String>()),
                            isLoadingMore = uiState.canLoadMore,
                            loadedPage = currentPage
                        )
                    }
                }
            }
        }
    }
}

/**
 * 段落说明（空关注 / 未登录时那两句）。
 *
 * 单独抽出来是因为它要被放在**网格里**（`GridItemSpan` 满行），
 * 直接塞 `Text` 会窄成一列。
 */
@Composable
private fun SectionHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

/**
 * 作者卡片要画的东西。
 *
 * 之所以不直接用 [SubscriptionItem]（只有名字 + 头像）：26.6.5 起「关注的作者」是跨站显示的，
 * 每张卡还要有一个**站点角标**，否则点进去之前根本看不出这是哪个站的人。
 */
private data class ArtistCard(
    val name: String,
    val avatar: String,
    val badge: String? = null,
)

/**
 * 已订阅 / 已关注作者的横向格子区域。
 *
 * @param onClickArtist 收到的是**下标**而不是名字：作者可能重名（跨站同名很常见），
 *   按下标回传才能让上层取回正确那一条（关注的作者要整份身份，不只是名字）。
 */
@Composable
private fun ArtistListSection(
    cards: List<ArtistCard>,
    title: String,
    artistRows: Int,
    artistColumns: Int,
    onClickArtist: (Int) -> Unit,
    onLongClickArtist: (Int) -> Unit,
) {
    val scrollState = rememberScrollState()
    val artistColumnCount = maxOf(1, (cards.size + artistRows - 1) / artistRows)
    val showArtistOverflowHint by remember(scrollState, artistColumnCount, artistColumns) {
        derivedStateOf {
            artistColumnCount > artistColumns && scrollState.value < scrollState.maxValue
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            AnimatedVisibility(visible = showArtistOverflowHint) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.swipe_more),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_forward),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(16.dp)
                    )
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingNormal),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(end = 28.dp, bottom = 8.dp)
            ) {
                repeat(artistColumnCount) { columnIndex ->
                    Column(
                        modifier = Modifier.width(ArtistIconSize),
                        verticalArrangement = Arrangement.spacedBy(SpacingNormal)
                    ) {
                        repeat(artistRows) { rowIndex ->
                            val itemIndex = columnIndex * artistRows + rowIndex
                            val card = cards.getOrNull(itemIndex)
                            if (card != null) {
                                ArtistItem(
                                    artist = SubscriptionItem(
                                        artistName = card.name,
                                        avatar = card.avatar,
                                    ),
                                    badgeText = card.badge,
                                    onClickArtist = { onClickArtist(itemIndex) },
                                    onLongClickArtist = { onLongClickArtist(itemIndex) },
                                )
                            } else {
                                Spacer(modifier = Modifier.width(ArtistIconSize))
                            }
                        }
                    }
                }
            }
            if (showArtistOverflowHint) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(32.dp)
                        .fillMaxHeight()
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.background,
                                )
                            )
                        )
                )
            }
        }
    }
}

@Preview(device = "spec:width=411dp,height=891dp", showBackground = true)
@Composable
private fun PreviewSubscriptionContent() {
    MaterialTheme {
        SubscriptionContent(
            uiState = SubscriptionUiState(
                followed = listOf(ArtistRef(name = "Tru Kait", url = "/pornstar/tru-kait")),
                artists = fakeArtists,
                videos = fakeVideos,
                isLoggedIn = true,
            ),
            onEvent = {},
            gridState = LazyGridState(),
            artistRows = 1,
        )
    }
}
