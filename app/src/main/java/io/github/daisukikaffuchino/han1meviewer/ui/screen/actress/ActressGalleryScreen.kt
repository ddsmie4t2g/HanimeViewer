package io.github.daisukikaffuchino.han1meviewer.ui.screen.actress

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.NjavActress
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavNetwork
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.ui.component.ActressGridCard
import io.github.daisukikaffuchino.han1meviewer.ui.component.LoadMoreFooter
import io.github.daisukikaffuchino.han1meviewer.ui.component.appbar.HanimeScaffold
import io.github.daisukikaffuchino.han1meviewer.ui.component.content.EmptyContent
import io.github.daisukikaffuchino.han1meviewer.ui.screen.rememberRandomLoadingHint
import io.github.daisukikaffuchino.han1meviewer.ui.theme.SpacingNormal
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.ActressGalleryTab
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.ActressGalleryUiState
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.ActressGalleryViewModel
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * **女优一览 / 女优排行**（26.8.2 新增）。
 *
 * ## 为什么需要这一页
 *
 * 站点在首页导航里有一组「女优」：**女优一览**（`/cn/actresses`）和**女优排行**
 * （`/cn/actresses/ranking`）。App 里此前只有前者、而且只作为「高级搜索 → 女优」
 * 的一个筛选弹窗存在 —— 也就是用户说的：**没有女优一览，也没有女优排名**。
 * 这一页把两个都摆成正式的浏览入口。
 *
 * ## 两个标签的差别（都是站点自己的形态，不是我们编的）
 *
 * | | 女优一览 | 女优排行 |
 * |---|---|---|
 * | 地址 | `/cn/actresses` | `/cn/actresses/ranking` |
 * | 排序 | `?sort=videos`（影片）/ `?sort=debut`（出道） | 站点只给当月一份榜 |
 * | 条数 | 每页 24 人，**1400+ 页**，能一直往下翻 | 固定 100 条（其中 96 位有头像），没有翻页 |
 * | 卡片小字 | `5669 部影片 · 2008 出道` | `第 N 名` |
 *
 * ⚠️ 站点**没有女优名字检索**（`?q=` / `?keyword=` 实测都被忽略），所以上面的搜索框
 * 只在**已加载**的条目上过滤 —— 文案里如实写了这一点，别让用户以为是在搜全站。
 *
 * ⭐ 点一张卡片进的是**作者页**（[onClickActress]），而且把卡片里的头像一起带过去 ——
 * nJAV 的视频详情页给不出女优头像，从这一页进去就完全不需要那个「按名字回索引页找头像」
 * 的动作了（用户报的「头像不能很快显示出来」，这是最直接的一条通路）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ActressGalleryScreen(
    navigateBack: () -> Unit,
    onClickActress: (NjavActress) -> Unit,
    viewModel: ActressGalleryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val keyboard = LocalSoftwareKeyboardController.current

    // 滚到底自动续页：**只在一览标签、且没有过滤词时**。
    // 有过滤词时不自动续，否则「输入一个已加载列表里没有的名字」会一路翻到 1400 页 ——
    // 那种情况交给底部的「加载更多」按钮（与高级搜索里的女优选择器同一套取舍）。
    // ⚠️ `loadFailed` 也必须是 key：翻页失败时 `isLoadingMore` 会变回 false，
    // 如果不看这一位，滚到底的监听会立刻再次触发 → 再去请求 → 再失败，
    // 变成一个不看内容的请求死循环。失败后改由底部按钮手动重试。
    LaunchedEffect(
        state.tab,
        state.keyword,
        state.hasMore,
        state.isLoadingMore,
        state.loadFailed,
        state.actresses.size,
    ) {
        if (state.tab != ActressGalleryTab.Index) return@LaunchedEffect
        if (!state.hasMore || state.isLoadingMore || state.loadFailed) return@LaunchedEffect
        if (state.keyword.isNotBlank()) return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last ->
                if (last >= state.filtered.size - 6) viewModel.loadMore()
            }
    }

    HanimeScaffold(
        title = stringResource(R.string.actress_gallery_title),
        onBack = navigateBack,
        subtitle = {
            // 排行页把站点 H1 里的周期（`SEP 2026`）如实带出来 —— 这一份是「当月榜」，
            // 不说清楚会让人以为是历史累计。
            val period = state.period
            if (state.tab == ActressGalleryTab.Ranking && period.isNotBlank()) {
                Text(
                    text = stringResource(R.string.actress_ranking_period, period),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            PrimaryTabRow(selectedTabIndex = state.tab.ordinal) {
                Tab(
                    selected = state.tab == ActressGalleryTab.Index,
                    onClick = { viewModel.selectTab(ActressGalleryTab.Index) },
                    text = { Text(stringResource(R.string.actress_tab_index)) },
                )
                Tab(
                    selected = state.tab == ActressGalleryTab.Ranking,
                    onClick = { viewModel.selectTab(ActressGalleryTab.Ranking) },
                    text = { Text(stringResource(R.string.actress_tab_ranking)) },
                )
            }

            ActressFilterBar(
                state = state,
                onSortChange = viewModel::setSort,
                onKeywordChange = viewModel::setKeyword,
                onSearch = { keyboard?.hide() },
            )

            ActressGalleryContent(
                state = state,
                gridState = gridState,
                onRetry = viewModel::retry,
                onLoadMore = viewModel::loadMore,
                onRetryLoadMore = viewModel::retryLoadMore,
                onClickActress = onClickActress,
            )
        }
    }
}

/**
 * 筛选条：一览页有「影片 / 出道」排序，两个标签都有本地名字过滤框。
 *
 * 排行页**不显示排序**：站点那份榜的排序是站点定的（按月热度），给它加个排序开关
 * 只会让人以为能改。
 */
@Composable
private fun ActressFilterBar(
    state: ActressGalleryUiState,
    onSortChange: (String) -> Unit,
    onKeywordChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (state.tab == ActressGalleryTab.Index) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.actress_sort_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilterChip(
                    selected = state.sort == NjavNetwork.ACTRESS_SORT_VIDEOS,
                    onClick = { onSortChange(NjavNetwork.ACTRESS_SORT_VIDEOS) },
                    label = { Text(stringResource(R.string.actress_sort_videos)) },
                )
                FilterChip(
                    selected = state.sort == NjavNetwork.ACTRESS_SORT_DEBUT,
                    onClick = { onSortChange(NjavNetwork.ACTRESS_SORT_DEBUT) },
                    label = { Text(stringResource(R.string.actress_sort_debut)) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    BasicTextField(
                        value = state.keyword,
                        onValueChange = onKeywordChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 12.dp),
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (state.keyword.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.actress_search_hint),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
            }
        }
        Text(
            text = stringResource(R.string.actress_filter_local_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        )
    }
}

@Composable
private fun ActressGalleryContent(
    state: ActressGalleryUiState,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    onClickActress: (NjavActress) -> Unit,
) {
    val failed = state.state as? PageLoadingState.Error

    when {
        // 整页错误（列表还空着才会走到这里；翻页失败时列表照常显示）。
        failed != null && state.actresses.isEmpty() -> Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.weight(1f)) {
                EmptyContent(
                    hint = stringResource(R.string.actress_list_failed),
                    subHint = failed.throwable.message.orEmpty(),
                    picRes = R.drawable.h_chan_sad,
                )
            }
            Button(onClick = onRetry) {
                Text(stringResource(R.string.retry))
            }
            Spacer(Modifier.height(24.dp))
        }

        state.state is PageLoadingState.Loading && state.actresses.isEmpty() -> ActressLoadingHint()

        state.actresses.isEmpty() -> EmptyContent(
            hint = stringResource(R.string.actress_empty),
            picRes = R.drawable.h_chan_speechless,
        )

        else -> {
            // 过滤一次就够 —— 在 item 的 key / 点击回调里各算一遍是 O(n²) 的无谓开销。
            val filtered = state.filtered
            LazyVerticalGrid(
                columns = GridCells.Adaptive(92.dp),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(SpacingNormal),
                horizontalArrangement = Arrangement.spacedBy(SpacingNormal),
                verticalArrangement = Arrangement.spacedBy(SpacingNormal),
            ) {
                items(
                    count = filtered.size,
                    key = { filtered[it].path },
                ) { index ->
                    ActressGridCard(
                        actress = filtered[index],
                        onClick = { onClickActress(filtered[index]) },
                    )
                }

                // 过滤后一条都不剩：说清楚「是本地没匹配」而不是「站点没有这个人」，
                // 并给一个按钮让用户自己决定要不要继续往下捞。
                if (filtered.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp),
                        ) {
                            EmptyContent(
                                hint = stringResource(R.string.actress_no_match),
                                picRes = R.drawable.h_chan_speechless,
                            )
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    when {
                        // 翻页失败：自动续页已经被停掉，这里必须给一个**可点**的入口，
                        // 否则用户就卡在「列表有内容、但再也加载不出下一页」上。
                        state.loadFailed -> LoadMoreFooter(
                            state = PageLoadingState.Success(Unit),
                            isLoadingMore = state.isLoadingMore,
                            onLoadMore = onRetryLoadMore,
                        )

                        // 有过滤词才给手动按钮；否则滚到底已经自动续页了。
                        state.keyword.isNotBlank() && state.hasMore -> LoadMoreFooter(
                            state = PageLoadingState.Success(Unit),
                            isLoadingMore = state.isLoadingMore,
                            onLoadMore = onLoadMore,
                        )

                        state.tab == ActressGalleryTab.Index -> LoadMoreFooter(
                            state = state.state,
                            isLoadingMore = state.isLoadingMore,
                            loadedPage = null,
                        )

                        else -> Unit
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActressLoadingHint() {
    val hint = rememberRandomLoadingHint()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            LoadingIndicator()
            Text(
                text = hint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
