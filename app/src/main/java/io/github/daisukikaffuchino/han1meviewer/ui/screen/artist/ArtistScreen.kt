package io.github.daisukikaffuchino.han1meviewer.ui.screen.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.FollowedArtistStore
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistProfile
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.ui.component.LoadMoreFooter
import io.github.daisukikaffuchino.han1meviewer.ui.component.VideoCardItem
import io.github.daisukikaffuchino.han1meviewer.ui.component.appbar.HanimeScaffold
import io.github.daisukikaffuchino.han1meviewer.ui.screen.RetryableImage
import io.github.daisukikaffuchino.han1meviewer.ui.screen.rememberVideoGridColumns
import io.github.daisukikaffuchino.han1meviewer.ui.theme.SpacingNormal
import io.github.daisukikaffuchino.han1meviewer.ui.theme.VideoNormalCardMinWidth
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.ArtistUiState
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.ArtistViewModel
import io.github.daisukikaffuchino.utils.SonnerToast
import io.github.daisukikaffuchino.utils.VibrationUtil
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * **作者页** —— 26.6.3 新增。
 *
 * ## 它修的是什么
 *
 * 之前点作者一律 `SearchRoute(query = 名字)`，用户的原话是「点进对应的作者里面全都是视频」：
 * 看到的是一次全文搜索，既没有「这是谁」，也不保证结果属于他。这个页面把两件事补上：
 *
 * 1. **头部资料**：头像 / 名字 / 作品数 · 关注者数（Pornhub 还能补上封面与观看总量），
 *    以及一个**关注按钮** —— 关注只写本机（[FollowedArtistStore]），**不需要登录任何站点**。
 * 2. **只属于该作者的作品列表**，能拿到站点作者页的（Pornhub `/pornstar|/model`、nJAV
 *    `/actresses`）就真分页；拿不到的（Pornhub 的 `/users/…` 上传者）退回按名字搜索，
 *    此时头部仍然只显示我们确实知道的信息，不假装那些结果是「该作者的作品」。
 *
 * ## 为什么它和数据源没关系
 *
 * 页面上没有任何 `when (siteSource)`：取数分流全部在
 * [io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo.getArtistVideos] 里，
 * 这里只画 `ArtistUiState`。加新站点时这个文件不用改。
 *
 * @param artist 目标作者（由路由参数反序列化得到）
 * @param navigateBack 返回
 * @param onClickVideo 打开作品详情
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArtistScreen(
    artist: ArtistRef,
    navigateBack: () -> Unit,
    onClickVideo: (String) -> Unit,
    viewModel: ArtistViewModel = viewModel(),
) {
    // 同一个作者重复进来不重复拉取（bind 内部按 followKey 短路）。
    LaunchedEffect(artist.followKey) { viewModel.bind(artist) }

    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by SettingsRepository.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val gridState = rememberLazyGridState()

    // 关注态在**本机**：读设置流当 key，关注/取关后按钮立刻变。
    val isFollowed = remember(settings.followedArtistsJson, artist.followKey) {
        FollowedArtistStore.isFollowed(artist.followKey)
    }

    var isLoadingMore by remember { mutableStateOf(false) }
    LaunchedEffect(gridState, state.videos.size, state.canLoadMore) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .distinctUntilChanged()
            .collect { last ->
                if (!isLoadingMore && state.canLoadMore && last != null &&
                    last >= state.videos.size - 6
                ) {
                    isLoadingMore = true
                    viewModel.loadMore()
                }
            }
    }
    LaunchedEffect(state.state) {
        if (state.state !is PageLoadingState.Loading) isLoadingMore = false
    }

    val target = state.artist.takeIf { it.followKey.isNotEmpty() } ?: artist
    val profile = state.profile
    val displayName = profile?.name?.takeIf { it.isNotBlank() }
        ?: target.name.ifBlank { stringResource(R.string.artist_page_title) }
    val videoColumns = rememberVideoGridColumns()

    HanimeScaffold(title = displayName, onBack = navigateBack) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // ⭐ **永远先画网格**（= 先画资料头）。
            //
            // 资料头用的全是**本地已有**的数据（`ArtistRef` 里的名字/头像/作品数来自上一个页面），
            // 完全没必要等这 1.2 MB 的作者页回来才显示 —— 26.6.3 的第一版在这里只画了个加载圈，
            // 于是「刚点进来是一片空白」，而其实那一刻我们已经知道这是谁了。
            //
            // 网络状态（加载中/失败/没有作品）改由网格内部**在资料头下面**那块区域表达，
            // 见 [ArtistVideoGrid] 里的 [ArtistStatus]。
            ArtistVideoGrid(
                state = state,
                isFollowed = isFollowed,
                columns = videoColumns,
                gridState = gridState,
                isLoadingMore = isLoadingMore,
                onClickVideo = onClickVideo,
                onToggleFollow = { toggleFollow(scope, view, artist) },
                onRetry = viewModel::retry,
            )
        }
    }
}

/**
 * 关注 / 取关。
 *
 * **不检查登录态**是刻意的：这是本机关注（[FollowedArtistStore]），
 * hanime 那种「服务端订阅要登录」在这里根本不适用 —— 用户要的正是「登录不跟网站挂钩」。
 */
private fun toggleFollow(
    scope: kotlinx.coroutines.CoroutineScope,
    view: android.view.View,
    artist: ArtistRef,
) {
    VibrationUtil.performHapticFeedback(view)
    scope.launch {
        val followed = FollowedArtistStore.toggle(artist)
        SonnerToast.success(
            if (followed) R.string.artist_followed else R.string.artist_unfollow
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArtistVideoGrid(
    state: ArtistUiState,
    isFollowed: Boolean,
    columns: Int,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    isLoadingMore: Boolean,
    onClickVideo: (String) -> Unit,
    onToggleFollow: () -> Unit,
    onRetry: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(SpacingNormal),
        horizontalArrangement = Arrangement.spacedBy(SpacingNormal),
        verticalArrangement = Arrangement.spacedBy(SpacingNormal),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            ArtistHeader(
                artist = state.artist,
                profile = state.profile,
                isFollowed = isFollowed,
                onToggleFollow = onToggleFollow,
            )
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.artist_page_works),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (state.videos.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.artist_page_loaded_count, state.videos.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ⭐ 站点没有真作者页时（hanime 全部、Pornhub 的 /users 上传者），如实说明这份列表是怎么来的：
        // 它是**按名字搜索**的结果，可能混进同名作者。宁可说清楚，也不要点进来的人误以为
        // 「这就是该作者的全部作品」—— 用户一开始抱怨的就是「点进去全都是视频」。
        if (!state.artist.hasRealArtistPage) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.artist_page_search_result_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }

        // 一条作品都还没有：把「加载中 / 失败 / 没有作品」画在资料头**下面**这块区域。
        // 注意这里**不是**整页替身 —— 资料头在上面已经画出来了。
        if (state.videos.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ArtistStatus(state = state.state, onRetry = onRetry)
            }
            return@LazyVerticalGrid
        }

        items(state.videos.size, key = { state.videos[it].videoCode }) { index ->
            val video = state.videos[index]
            VideoCardItem(
                videoItem = video,
                isHorizontalCard = true,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                onClickVideosItem = { onClickVideo(video.videoCode) },
                onLongClickVideosItem = { _, _ -> },
            )
        }

        if (state.canLoadMore) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (isLoadingMore) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                } else {
                    LoadMoreFooter(
                        state = PageLoadingState.Success(emptyList<String>()),
                        isLoadingMore = false,
                    )
                }
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                // 已经翻到底：如果最后一次是**失败**（而不是真的没了），得说出来 ——
                // 否则「只有这么多」和「下一页没取到」在界面上长得一模一样。
                val failure = state.state as? PageLoadingState.Error
                if (failure != null) {
                    RetryRow(
                        message = stringResource(
                            R.string.load_failed_with_reason,
                            failure.throwable.message.orEmpty(),
                        ),
                        onRetry = onRetry,
                    )
                } else {
                    HorizontalDivider(
                        modifier = Modifier.padding(top = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

/**
 * 作品区的状态占位（资料头**下面**那一块）。
 *
 * 三种状态一眼可分：
 * - **加载中**：转圈；
 * - **失败**：原因 + 「重试」按钮（比只给一句报错有用得多）；
 * - **没有作品**：空态文案。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArtistStatus(
    state: PageLoadingState<*>,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is PageLoadingState.Loading -> LoadingIndicator()

            is PageLoadingState.Error -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.load_failed_with_reason,
                        state.throwable.message.orEmpty(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = onRetry) {
                    Text(text = stringResource(R.string.retry))
                }
            }

            is PageLoadingState.NoMoreData -> Text(
                text = stringResource(R.string.artist_page_no_videos),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            is PageLoadingState.Success -> Unit
        }
    }
}

/** 「加载更多失败」时挂在列表末尾的那一行（文案 + 重试）。 */
@Composable
private fun RetryRow(
    message: String,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        OutlinedButton(onClick = onRetry) {
            Text(text = stringResource(R.string.retry))
        }
    }
}

/**
 * 作者资料头。
 *
 * 有封面就画封面（Pornhub 给了 `#coverPictureDefault`），没有就只画头像那一行 ——
 * 不要为「看起来丰富」硬塞一张图，nJAV 的作者页确实没有封面。
 */
@Composable
private fun ArtistHeader(
    artist: ArtistRef,
    profile: ArtistProfile?,
    isFollowed: Boolean,
    onToggleFollow: () -> Unit,
) {
    val cover = profile?.coverUrl?.takeIf { it.isNotBlank() }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (cover != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                ) {
                    RetryableImage(
                        model = cover,
                        contentDescription = null,
                        modifier = Modifier.matchParentSize(),
                        placeholder = painterResource(R.drawable.h_chan_loading),
                        error = painterResource(R.drawable.h_chan_load_failed),
                        contentScale = ContentScale.Crop,
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))
                                )
                            )
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val avatar = profile?.avatarUrl?.takeIf { it.isNotBlank() } ?: artist.avatar
                RetryableImage(
                    model = avatar,
                    contentDescription = artist.name,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape),
                    placeholder = painterResource(R.drawable.h_chan_loading_small),
                    error = painterResource(R.drawable.h_chan_default_avatar),
                    contentScale = ContentScale.Crop,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile?.name?.takeIf { it.isNotBlank() }
                            ?: artist.name.ifBlank { stringResource(R.string.artist_page_title) },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val stats = listOfNotNull(
                        // 「87 Videos」这种站点原文案优先，别自己换算单位。
                        artist.videoCount.takeIf { it.isNotBlank() },
                        (profile?.subscriberCount?.takeIf { it.isNotBlank() }
                            ?: artist.subscriberCount.takeIf { it.isNotBlank() }),
                        profile?.viewCount?.takeIf { it.isNotBlank() }
                            ?.let { stringResource(R.string.artist_page_view_count, it) },
                    )
                    if (stats.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stats.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (artist.genre.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = artist.genre,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (isFollowed) {
                    OutlinedButton(
                        onClick = onToggleFollow,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(text = stringResource(R.string.artist_followed))
                    }
                } else {
                    Button(onClick = onToggleFollow) {
                        Text(text = stringResource(R.string.artist_follow))
                    }
                }
            }
        }
    }
}
