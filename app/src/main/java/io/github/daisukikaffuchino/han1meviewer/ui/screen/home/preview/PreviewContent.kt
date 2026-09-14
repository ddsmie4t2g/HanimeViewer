package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.preview

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.exception.HanimeNotFoundException
import io.github.daisukikaffuchino.han1meviewer.logic.model.GetchuPreview
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimePreview
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import io.github.daisukikaffuchino.han1meviewer.logic.state.dataOrNull
import io.github.daisukikaffuchino.han1meviewer.pienization
import io.github.daisukikaffuchino.han1meviewer.ui.component.CardContainerSurface
import io.github.daisukikaffuchino.han1meviewer.ui.component.FilledTonalButton
import io.github.daisukikaffuchino.han1meviewer.ui.component.IconButton
import io.github.daisukikaffuchino.han1meviewer.ui.component.LoadMoreFooter
import io.github.daisukikaffuchino.han1meviewer.ui.component.VideoCardItem
import io.github.daisukikaffuchino.han1meviewer.ui.component.appbar.HanimePageSurface
import io.github.daisukikaffuchino.han1meviewer.ui.component.appbar.HanimeTopAppBar
import io.github.daisukikaffuchino.han1meviewer.ui.component.content.EmptyContent
import io.github.daisukikaffuchino.han1meviewer.ui.component.content.ErrorContent
import io.github.daisukikaffuchino.han1meviewer.ui.component.content.LoadingContent
import io.github.daisukikaffuchino.han1meviewer.ui.component.lazy.LazyColumn
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.preview.getchupreview.GetchuPreviewContent
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.preview.getchupreview.rememberGetchuImageLoader
import io.github.daisukikaffuchino.han1meviewer.ui.screen.rememberRandomLoadingHint
import io.github.daisukikaffuchino.han1meviewer.ui.component.HapticButton as Button
import io.github.daisukikaffuchino.han1meviewer.ui.component.HapticTextButton as TextButton

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PreviewContent(
    uiState: PreviewUiState,
    onEvent: (PreviewEvent) -> Unit,
    previewPagerState: PagerState,
    previewInfoList: List<HanimePreview.PreviewInfo>,
    modifier: Modifier = Modifier,
) {
    val loadingHint = rememberRandomLoadingHint()
    // 【月度归档】非 null 表示当前月份站方已停更，页面展示的是"按上市月份检索"的结果
    val archiveState = uiState.archiveState
    val archiveListState = rememberLazyListState()
    // Getchu 发售表的封面也在 www.getchu.com 上，同样必须走中转（见 rememberGetchuImageLoader）。
    val getchuImageLoader = rememberGetchuImageLoader()

    // 【月度归档】滚到底部附近时自动加载下一页。
    // 直接用滚动位置判断（而不是放在列表末尾 item 里），避免列表短时一路连锁把整月都拉完。
    // ViewModel 的 loadMoreArchive() 内部已做去重与状态保护，这里多触发几次也无害。
    // ⚠️ 只在「已上架」标签下才续页：发售表是整月一次性返回的，没有分页。
    if (archiveState != null && uiState.selectedTab == PreviewTab.Hanime) {
        LaunchedEffect(
            archiveListState,
            archiveState.loadedPages,
            archiveState.isLoadingMore,
            uiState.selectedTab,
        ) {
            snapshotFlow {
                val info = archiveListState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
            }
                .distinctUntilChanged()
                .collect { atBottom ->
                    if (atBottom) onEvent(PreviewEvent.OnLoadMoreArchive)
                }
        }
    }
    HanimePageSurface(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            HanimeTopAppBar(
                title = {
                    AnimatedContent(
                        targetState = uiState.currentDateLabel,
                        transitionSpec = {
                            val forward = uiState.monthAnimationDirection >= 0
                            (slideInVertically(
                                animationSpec = tween(320, easing = LinearOutSlowInEasing),
                                initialOffsetY = { height -> if (forward) height / 2 else -height / 2 }
                            ) + fadeIn(
                                animationSpec = tween(
                                    260,
                                    delayMillis = 40,
                                    easing = LinearOutSlowInEasing
                                )
                            )) togetherWith
                                    (slideOutVertically(
                                        animationSpec = tween(220, easing = FastOutLinearInEasing),
                                        targetOffsetY = { height -> if (forward) -height / 2 else height / 2 }
                                    ) + fadeOut(
                                        animationSpec = tween(170, easing = FastOutLinearInEasing)
                                    ))
                        },
                        label = "preview_month_title",
                    ) { animatedDateLabel ->
                        Text(stringResource(R.string.latest_hanime_list_monthly, animatedDateLabel))
                    }
                },
                onBack = { onEvent(PreviewEvent.OnBack) },
                actions = {
                    IconButton(onClick = {
                        onEvent(
                            PreviewEvent.OnOpenComment(
                                uiState.currentDateLabel,
                                uiState.routeState.currentDateCode
                            )
                        )
                    }) {
                        BadgedBox(
                            badge = {
                                if (uiState.commentCount > 0) {
                                    Badge(
                                        modifier = Modifier
                                            .defaultMinSize(minWidth = 20.dp)
                                    ) {
                                        Text(
                                            text = if (uiState.commentCount > 999) "999+" else uiState.commentCount.toString(),
                                            maxLines = 1,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_comment),
                                contentDescription = stringResource(R.string.comment),
                            )
                        }
                    }
                },
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                state = archiveListState,
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 「站方预告已停更 / 去看 Getchu 预告」提示卡常驻。
                // 站方自 2026-05 起停更新番预告，这张卡是进入 Getchu 预告页的唯一入口，
                // 无论当前月走的是站方预告模式还是「按上市月份检索」的归档模式都必须能看到，
                // 因此这里不再按 archiveState 做条件判断。
                item {
                    PreviewSourceNoticeCard(
                        onOpenWeb = { onEvent(PreviewEvent.OnOpenWebPreview) },
                        onOpenGetchu = { onEvent(PreviewEvent.OnOpenGetchuPreview) },
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    )
                }

                item {
                    AnimatedContent(
                        targetState = uiState.monthHeaderState,
                        contentKey = { it.dateCode },
                        transitionSpec = {
                            val forward = uiState.monthAnimationDirection >= 0
                            (slideInHorizontally(
                                animationSpec = tween(420, easing = LinearOutSlowInEasing),
                                initialOffsetX = { width -> if (forward) width else -width }
                            ) + fadeIn(
                                animationSpec = tween(
                                    320,
                                    delayMillis = 70,
                                    easing = LinearOutSlowInEasing
                                )
                            )) togetherWith
                                    (slideOutHorizontally(
                                        animationSpec = tween(260, easing = FastOutLinearInEasing),
                                        targetOffsetX = { width -> if (forward) -width else width }
                                    ) + fadeOut(
                                        animationSpec = tween(
                                            190,
                                            easing = FastOutLinearInEasing
                                        )
                                    ))
                        },
                        label = "preview_month_header",
                    ) { animatedHeaderState ->
                        PreviewHeaderSection(
                            headerImageUrl = animatedHeaderState.headerImageUrl,
                            prevLabel = animatedHeaderState.prevLabel,
                            nextLabel = animatedHeaderState.nextLabel,
                            canPrev = animatedHeaderState.canPrev,
                            canNext = animatedHeaderState.canNext,
                            onPrev = { onEvent(PreviewEvent.OnPrevMonth(animatedHeaderState.dateCode)) },
                            onNext = { onEvent(PreviewEvent.OnNextMonth(animatedHeaderState.dateCode)) },
                        )
                    }
                }

                if (archiveState != null) {
                    // ===== 【月度归档】 =====
                    // 站方预告停更月份：不再请求 /previews/{yyyyMM}，改为顶上两个标签：
                    //
                    //   [发售表] 该月**预定发售**的里番（getchu.com）
                    //   [已上架] 该月**已在 hanime 上线**的番剧（站内检索 date=yyyy 年 m 月）
                    //
                    // ⭐ 这两个列表的含义完全不同，而且经常一个有一个没有。实测 2026-09-14：
                    // Getchu 的 9 月发售表有 29 部（9/4、9/11、9/18、9/25、9/30 五组），
                    // 而 hanime 的 9 月里番**一部都没上架**（最新仍停在 8-28）。
                    // 以前只有「已上架」一个列表，于是用户看到空列表就以为「明明上了几部却没显示」。
                    item {
                        PreviewTabRow(
                            selectedTab = uiState.selectedTab,
                            onSelectTab = { onEvent(PreviewEvent.OnSelectTab(it)) },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }

                    if (uiState.selectedTab == PreviewTab.Getchu) {
                        // 发售表：一份整月列表（Getchu 自己按发售日分成若干组），没有分页。
                        // ⚠️ 这里刻意把分支**写平**而不是抽成 LazyListScope 扩展函数：
                        // 抽出去以后 `state.items` 会遮蔽 `LazyListScope.items(...)`，
                        // 编译器报一长串「receiver type mismatch」，牵连到调用点。
                        when {
                            uiState.getchuState.isGetchuLoading -> item {
                                LoadingContent(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    message = loadingHint,
                                )
                            }

                            uiState.getchuState.isGetchuError -> item {
                                ErrorContent(
                                    title = stringResource(R.string.preview_tab_getchu),
                                    message = stringResource(R.string.preview_getchu_failed),
                                    onRetry = { onEvent(PreviewEvent.OnRetryGetchu) },
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }

                            uiState.getchuState.getchuData?.groups.isNullOrEmpty() -> item {
                                EmptyContent(
                                    hint = stringResource(R.string.preview_getchu_empty),
                                    subHint = stringResource(R.string.preview_getchu_empty_hint),
                                )
                            }

                            else -> item {
                                GetchuPreviewContent(
                                    preview = uiState.getchuState.getchuData!!,
                                    onOpenDetail = { id ->
                                        onEvent(PreviewEvent.OnOpenGetchuDetail(id))
                                    },
                                    imageLoader = getchuImageLoader,
                                )
                            }
                        }
                    } else {
                        // 已上架：该月 1 日至月底在 hanime 上线的番剧（两列网格，滚到底续页）。
                        when {
                            archiveState.isLoading && !archiveState.hasItems -> item {
                                LoadingContent(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    message = loadingHint,
                                )
                            }

                            archiveState.isFatalError -> item {
                                ErrorContent(
                                    title = stringResource(R.string.hanime_list),
                                    message = stringResource(R.string.preview_archive_failed),
                                    onRetry = { onEvent(PreviewEvent.OnRetryArchive) },
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }

                            !archiveState.hasItems -> item {
                                EmptyContent(
                                    hint = stringResource(R.string.preview_archive_empty),
                                    subHint = stringResource(R.string.preview_archive_empty_hint),
                                )
                            }

                            else -> {
                                items(
                                    archiveState.items.chunked(2),
                                    key = { row -> row.first().videoCode },
                                ) { row ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        row.forEach { info ->
                                            VideoCardItem(
                                                modifier = Modifier.weight(1f),
                                                videoItem = info,
                                                isHorizontalCard = false,
                                                onClickVideosItem = { code ->
                                                    onEvent(PreviewEvent.OnOpenVideo(code))
                                                },
                                                onLongClickVideosItem = { _, _ -> },
                                            )
                                        }
                                        if (row.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }

                                item {
                                    LoadMoreFooter(
                                        state = archiveState.toFooterState(),
                                        // 只有真的翻过页才报页数；单页时显示「加载完毕！」就好，
                                        // 免得每次都在底下一本正经地写「共1页」。
                                        loadedPage = archiveState.loadedPages.takeIf { it > 1 },
                                        isLoadingMore = archiveState.isLoadingMore,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                } else when (uiState.displayState) {
                    is WebsiteState.Loading -> item {
                        LoadingContent(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            message = loadingHint
                        )
                    }

                    is WebsiteState.Error -> item {
                        val isPreviewEmpty =
                            uiState.displayState.throwable is HanimeNotFoundException
                        ErrorContent(
                            title = stringResource(R.string.hanime_list),
                            message = if (isPreviewEmpty) {
                                stringResource(R.string.preview_month_not_updated)
                            } else {
                                uiState.displayState.throwable.pienization.toString()
                            },
                            onRetry = if (isPreviewEmpty) null else {
                                { onEvent(PreviewEvent.OnRetryLoad) }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }

                    is WebsiteState.Success -> {
                        item {
                            PreviewTourRow(
                                latestHanime = uiState.displayState.info.latestHanime,
                                selectedIndex = uiState.routeState.selectedIndex,
                                onSelect = { onEvent(PreviewEvent.OnSelectTourItem(it)) },
                            )
                        }

                        item {
                            if (previewInfoList.isEmpty()) {
                                EmptyContent(
                                    hint = stringResource(R.string.empty_content),
                                    subHint = stringResource(R.string.new_anime_trailers)
                                )
                            } else {
                                HorizontalPager(
                                    state = previewPagerState,
                                    beyondViewportPageCount = 1,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 620.dp)
                                        .animateContentSize(),
                                    verticalAlignment = Alignment.Top,
                                ) { page ->
                                    PreviewInfoCard(
                                        previewInfo = previewInfoList[page],
                                        onOpenVideo = { code ->
                                            onEvent(PreviewEvent.OnOpenVideo(code))
                                        },
                                        onOpenImage = { index, imageUrls ->
                                            onEvent(PreviewEvent.OnOpenImage(index, imageUrls))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 日历页顶部的「发售表 / 已上架」标签行。
 *
 * 用「发售表」而不是「Getchu」做标签名：多数用户不知道 getchu 是什么，
 * 但一看就知道「发售表 = 还没出的、预定几号卖」。
 */
@Composable
private fun PreviewTabRow(
    selectedTab: PreviewTab,
    onSelectTab: (PreviewTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryTabRow(selectedTabIndex = selectedTab.ordinal, modifier = modifier) {
        Tab(
            selected = selectedTab == PreviewTab.Getchu,
            onClick = { onSelectTab(PreviewTab.Getchu) },
            text = { Text(stringResource(R.string.preview_tab_getchu)) },
        )
        Tab(
            selected = selectedTab == PreviewTab.Hanime,
            onClick = { onSelectTab(PreviewTab.Hanime) },
            text = { Text(stringResource(R.string.preview_tab_hanime)) },
        )
    }
}

/**
 * 「发售表」状态在 [PreviewUiState] 上的三个便捷判据。
 *
 * 把它们放在这里而不是写成一串 `is PageState.Loading && data == null`，
 * 是因为调用点在一个很深的 `LazyColumn` DSL 里，那里的可读性本来就差。
 */
private val PageState<GetchuPreview>.isGetchuLoading: Boolean
    get() = this is PageState.Loading && dataOrNull == null

private val PageState<GetchuPreview>.isGetchuError: Boolean
    get() = this is PageState.Error && dataOrNull == null

private val PageState<GetchuPreview>.getchuData: GetchuPreview?
    get() = dataOrNull
/**
 * 把【月度归档】的状态映射成 [LoadMoreFooter] 需要的分页状态。
 */
private fun PreviewArchiveUiState.toFooterState(): PageLoadingState<*> = when {
    isLoadingMore -> PageLoadingState.Loading
    hasError -> PageLoadingState.Error(IllegalStateException("archive load failed"))
    noMoreData -> PageLoadingState.NoMoreData
    else -> PageLoadingState.Success(Unit)
}

@Composable
private fun PreviewSourceNoticeCard(
    onOpenWeb: () -> Unit,
    onOpenGetchu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CardContainerSurface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.preview_discontinued_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenWeb) {
                    Text(stringResource(R.string.visit_web_version))
                }
                Button(onClick = onOpenGetchu) {
                    Text(stringResource(R.string.view_getchu_preview))
                }
            }
        }
    }
}

@Composable
private fun PreviewHeaderSection(
    headerImageUrl: String?,
    prevLabel: String,
    nextLabel: String,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth()
    ) {
        AsyncImage(
            model = headerImageUrl,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
                        )
                    )
                )
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onPrev,
                enabled = canPrev,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    painterResource(R.drawable.ic_chevron_left),
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(prevLabel)
            }

            FilledTonalButton(
                onClick = onNext,
                enabled = canNext,
                modifier = Modifier.weight(1f)
            ) {
                Text(nextLabel)
                Spacer(Modifier.width(8.dp))
                Icon(
                    painterResource(R.drawable.ic_chevron_right),
                    contentDescription = null
                )
            }
        }
    }
}
