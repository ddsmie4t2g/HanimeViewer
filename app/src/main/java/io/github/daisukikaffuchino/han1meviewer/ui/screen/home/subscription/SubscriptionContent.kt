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
import io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource
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
 * ## 页面结构（26.7.3 起**按站点**分成三块）
 *
 * ```
 * hanime 订阅（N）        ← 已登录：读 hanime 服务端（实时）
 *                            未登录：读本机同步副本，下方注明来源
 * ────────────────────────────────────────
 * Pornhub 关注（N）       ← 本机关注（Pornhub 没有订阅接口）
 * ────────────────────────────────────────
 * nJAV 关注（N）          ← 本机关注（同上）
 * ────────────────────────────────────────
 * 订阅视频（N）+ 视频网格  ← 仅 hanime 登录后才有
 * ```
 *
 * ⚠️ 26.7.3 之前只有「关注的作者」+「hanime 订阅」两块，于是 Pornhub 与 nJAV 的人
 * **混在同一个横排里**，只能靠卡片角标区分 —— 用户的原话是「分布不清」。
 * 现在每个站点一块，标题里就写着是哪一家，角标也就没有必要了。
 *
 * 之所以还要保留「本机关注」这个概念：关注是「我记住这个人」这件纯本地的事，
 * 没有理由被任何一个站点的登录挡住，而且本机关注会同步到用户自建的账号。
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
            // ── ⭐ 作者按**站点**分成三块（26.7.3） ─────────────────────────────
            //
            // 以前只有「关注的作者」和「hanime 订阅」两块，于是 Pornhub 和 nJAV 的人
            // 混在同一个格子里，只能靠卡片上的小角标区分 —— 用户的原话是「分布不清」。
            // 现在三个站点各成一块，标题就写着是哪一家。
            val followedBySite = uiState.followed.groupBy { it.siteSource }
            val hanimeLocal = followedBySite[SiteSource.Hanime1].orEmpty()
            val pornhubLocal = followedBySite[SiteSource.Pornhub].orEmpty()
            val njavLocal = followedBySite[SiteSource.Njav].orEmpty()
            val everythingEmpty = uiState.followed.isEmpty() &&
                    (!uiState.isLoggedIn || uiState.artists.isEmpty())

            if (everythingEmpty) {
                item(span = { GridItemSpan(videoColumns) }) {
                    SectionHint(stringResource(R.string.subscription_followed_empty_hint))
                }
            }

            // ── 第一段：hanime ────────────────────────────────────────────────
            //
            // 登录 → 读 hanime 服务端（实时、以取关为准）；
            // 未登录 → 读本机那份同步副本（登录时自动存下来的，见 FollowedArtistStore）。
            val hanimeCards = if (uiState.isLoggedIn) {
                uiState.artists.map {
                    // 服务端订阅那一份只有名字 ⇒ 身份键给空，靠名字兜底。
                    ArtistCard(
                        it.artistName,
                        it.avatar,
                        unread = unreadOf(uiState.unread, "", it.artistName),
                    )
                }
            } else {
                hanimeLocal.map {
                    ArtistCard(
                        it.name,
                        it.avatar,
                        unread = unreadOf(uiState.unread, it.followKey, it.name),
                    )
                }
            }
            if (!everythingEmpty) {
                item(span = { GridItemSpan(videoColumns) }, key = "section-hanime") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ArtistSectionItem(
                            title = if (uiState.isLoggedIn) {
                                stringResource(
                                    R.string.subscription_server_artists_count,
                                    hanimeCards.size,
                                )
                            } else {
                                stringResource(
                                    R.string.subscription_hanime_local_count,
                                    hanimeCards.size,
                                )
                            },
                            cards = hanimeCards,
                            artistRows = artistRows,
                            artistColumns = artistColumns,
                            onClickArtist = { index ->
                                if (uiState.isLoggedIn) {
                                    uiState.artists.getOrNull(index)?.let {
                                        onEvent(SubscriptionEvent.OnClickArtist(it.artistName))
                                    }
                                } else {
                                    hanimeLocal.getOrNull(index)?.let {
                                        onEvent(SubscriptionEvent.OnClickFollowed(it))
                                    }
                                }
                            },
                            onLongClickArtist = { index ->
                                if (uiState.isLoggedIn) {
                                    uiState.artists.getOrNull(index)?.let {
                                        onEvent(SubscriptionEvent.OnLongClickArtist(it.artistName))
                                    }
                                } else {
                                    hanimeLocal.getOrNull(index)?.let {
                                        onEvent(SubscriptionEvent.OnLongClickFollowed(it))
                                    }
                                }
                            },
                        )
                        if (!uiState.isLoggedIn) {
                            // 说清楚这一块是哪儿来的，免得用户以为「没登录怎么也有订阅」。
                            SectionHint(stringResource(R.string.subscription_hanime_local_note))
                        }
                    }
                }
            }

            // ── 第二段 / 第三段：Pornhub、nJAV 各成一块 ─────────────────────────
            listOf(
                Triple("section-pornhub", SiteSource.Pornhub, pornhubLocal),
                Triple("section-njav", SiteSource.Njav, njavLocal),
            ).forEach { (key, site, list) ->
                if (list.isEmpty()) return@forEach
                item(span = { GridItemSpan(videoColumns) }, key = key) {
                    ArtistSectionItem(
                        title = stringResource(
                            when (site) {
                                SiteSource.Pornhub -> R.string.subscription_pornhub_artists_count
                                SiteSource.Njav -> R.string.subscription_njav_artists_count
                                SiteSource.Hanime1 -> R.string.subscription_followed_artists_count
                            },
                            list.size,
                        ),
                        cards = list.map {
                            ArtistCard(
                                it.name,
                                it.avatar,
                                unread = unreadOf(uiState.unread, it.followKey, it.name),
                            )
                        },
                        artistRows = artistRows,
                        artistColumns = artistColumns,
                        onClickArtist = { index ->
                            list.getOrNull(index)?.let {
                                onEvent(SubscriptionEvent.OnClickFollowed(it))
                            }
                        },
                        onLongClickArtist = { index ->
                            list.getOrNull(index)?.let {
                                onEvent(SubscriptionEvent.OnLongClickFollowed(it))
                            }
                        },
                    )
                }
            }

            // ── 第四段：hanime 订阅视频流（只有登录后才有） ───────────────────────
            if (uiState.isLoggedIn) {
                if (uiState.videos.isNotEmpty()) {
                    item(span = { GridItemSpan(videoColumns) }, key = "section-videos") {
                        Column(verticalArrangement = Arrangement.spacedBy(SpacingNormal)) {
                            HorizontalDivider(
                                modifier = Modifier.fillMaxWidth(),
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            Text(
                                text = stringResource(
                                    R.string.subscription_videos_count,
                                    uiState.videos.size,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
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
 * 一个站点的作者格子（标题 + 横向滚动的作者卡）。
 *
 * 抽出来是因为三个站点各要画一块，而它们的排版完全一样，只有「标题」和「点谁」不同。
 * 内部套 [AnimatedContent] 是为了列表增删时有个淡入淡出，不套也能用。
 */
@Composable
private fun ArtistSectionItem(
    title: String,
    cards: List<ArtistCard>,
    artistRows: Int,
    artistColumns: Int,
    onClickArtist: (Int) -> Unit,
    onLongClickArtist: (Int) -> Unit,
) {
    AnimatedContent(
        targetState = cards,
        label = "artist-animation-$title",
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }
    ) { artists ->
        ArtistListSection(
            cards = artists,
            title = title,
            artistRows = artistRows,
            artistColumns = artistColumns,
            onClickArtist = onClickArtist,
            onLongClickArtist = onLongClickArtist,
        )
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
    /** 新作数（9.0）。0 = 不画角标。 */
    val unread: Int = 0,
)

/**
 * 查某位作者的**未读新作数**（9.0）。
 *
 * 两把钥匙都要试：
 * - 本机关注拿得出身份键（`ArtistRef.followKey`）；
 * - hanime 服务端订阅那一份**只有名字和头像**，算不出身份键。
 *
 * `FollowedArtistStore.unreadLookup` 把两种键都放了进去；为 0 的作者不在表里，
 * 所以查不到就是 0（这也是为什么可以直接用 `?:`）。
 */
private fun unreadOf(unread: Map<String, Int>, followKey: String, name: String): Int =
    unread[followKey] ?: unread[name.trim().lowercase()] ?: 0

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
                                    unreadCount = card.unread,
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
