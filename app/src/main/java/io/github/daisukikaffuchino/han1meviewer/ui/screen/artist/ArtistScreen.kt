package io.github.daisukikaffuchino.han1meviewer.ui.screen.artist

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.daisukikaffuchino.han1meviewer.logic.account.AccountRepository
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistProfile
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.ui.component.LoadMoreFooter
import io.github.daisukikaffuchino.han1meviewer.ui.component.VideoCardItem
import io.github.daisukikaffuchino.han1meviewer.ui.component.appbar.HanimeScaffold
import io.github.daisukikaffuchino.han1meviewer.ui.component.ChoiceDialog
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


    val target = state.artist.takeIf { it.followKey.isNotEmpty() } ?: artist
    val profile = state.profile
    val displayName = profile?.name?.takeIf { it.isNotBlank() }
        ?: target.name.ifBlank { stringResource(R.string.artist_page_title) }
    // ⭐ 9.0：用户要求的排版 —— **2 列**，一页 12 条正好 6 行。
    // 26.8 是 3 列 × 4 行，但 3 列时每格封面被压得很窄，封面上的编号/标题看不清。
    // 与「按屏宽自适应」也刻意不同：列数一变，「一页 12 条 = 几行」就不确定了。
    val videoColumns = ArtistViewModel.COLUMNS

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
                onClickVideo = onClickVideo,
                onToggleFollow = { toggleFollow(scope, view, artist) },
                onRetry = viewModel::retry,
                onPrevPage = viewModel::prevPage,
                onNextPage = viewModel::nextPage,
                onGoToPage = viewModel::goToPage,
                onSortChange = viewModel::setSort,
                onFilterChange = viewModel::setFilter,
                onQueryChange = viewModel::setQuery,
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
        // 关注是「本机的事实」，但用户希望它跟着**自己的账号**走 —— 立刻上传一次，
        // 别等到下次登录/手动同步（换机时才发现没传上去是最难受的）。
        if (AccountRepository.isLoggedIn) AccountRepository.uploadLocal()
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
    onClickVideo: (String) -> Unit,
    onToggleFollow: () -> Unit,
    onRetry: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onGoToPage: (Int) -> Unit,
    onSortChange: (String?) -> Unit,
    onFilterChange: (String?) -> Unit,
    onQueryChange: (String) -> Unit,
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
                if (!state.isSearching && state.videos.isNotEmpty()) {
                    // 只说「第 x / y 页」，不说「已加载 n 条」——
                    // 后者会把「站点那边还有多少」暴露成噪音，用户关心的是一页翻了多久。
                    Text(
                        text = stringResource(
                            R.string.artist_page_page_indicator,
                            state.page,
                            state.totalPages,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ⭐ 26.8：nJAV 女优页的**排序 / 筛选**（站点原生 `?sort=` / `?filters=`）。
        // 只在 nJAV 显示 —— 另外两个站点没有这组参数，画出来只会点了没反应。
        if (state.artist.siteSource == SiteSource.Njav) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ArtistSortFilterRow(
                    sort = state.sort,
                    filter = state.filter,
                    onSortChange = onSortChange,
                    onFilterChange = onFilterChange,
                )
            }
        }

        // ⭐ 26.9.4：nJAV 女优页的**作品搜索**（用户要求：「女优作品太多，加一个搜索作品功能」）。
        // 只在真正的女优页显示 —— 详情页给的作者名没有女优页时（退回按名字搜索）没有意义。
        // ⚠️ 它只在**已经拉回来的作品**里筛，一次请求都不发；文案里如实写清楚。
        if (state.artist.siteSource == SiteSource.Njav && state.artist.hasRealArtistPage) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ArtistWorkSearchField(
                    query = state.query,
                    onQueryChange = onQueryChange,
                )
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
                if (state.isSearching) {
                    // 搜索是本地筛选，空结果不是「作者没有作品」，别用那句空态文案。
                    Text(
                        text = stringResource(R.string.artist_work_search_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 40.dp),
                    )
                } else {
                    ArtistStatus(state = state.state, onRetry = onRetry)
                }
            }
            return@LazyVerticalGrid
        }

        // 只画**这一页**的作品（一页 = 站点自己的一页，40–59 条那种量级）。
        // 翻页由下面的分页条控制，不再无限往下滚 —— 用户能知道「一共翻到第几页」。
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

        // 搜索时把分页条收起来：命中的是**跨页的本地结果**，与「第几页」没有关系。
        if (!state.isSearching) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ArtistPager(
                    numbers = state.numbers,
                    page = state.page,
                    totalPages = state.totalPages,
                    canPrev = state.canPrev,
                    canNext = state.canNext,
                    isPaging = state.isPaging,
                    onPrev = onPrevPage,
                    onNext = onNextPage,
                    onGoToPage = onGoToPage,
                    footer = {
                        // 补拉失败要说出来：否则「翻不动了」和「真的没有了」长得一模一样。
                        val failure = state.state as? PageLoadingState.Error
                        if (failure != null) {
                            RetryRow(
                                message = stringResource(
                                    R.string.load_failed_with_reason,
                                    failure.throwable.message.orEmpty(),
                                ),
                                onRetry = onRetry,
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * nJAV 女优页的**排序 / 筛选**入口（26.8）。
 *
 * 取值与站点下拉菜单一一对应（见 `NjavNetwork.actressUrl`）：
 * 排序 7 项、筛选 4 项（「所有」= 不传参数）。用两个按钮 + [ChoiceDialog]，
 * 与「切换数据源」「作者格子行数」等既有交互保持一致。
 */
@Composable
private fun ArtistSortFilterRow(
    sort: String?,
    filter: String?,
    onSortChange: (String?) -> Unit,
    onFilterChange: (String?) -> Unit,
) {
    var showSort by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }

    val sortOptions = listOf(
        stringResource(R.string.njav_sort_released) to "released_at",
        stringResource(R.string.njav_sort_updated) to "published_at",
        stringResource(R.string.njav_sort_saved) to "saved",
        stringResource(R.string.njav_sort_today_views) to "today_views",
        stringResource(R.string.njav_sort_weekly_views) to "weekly_views",
        stringResource(R.string.njav_sort_monthly_views) to "monthly_views",
        stringResource(R.string.njav_sort_views) to "views",
    )
    val filterOptions = listOf(
        stringResource(R.string.njav_filter_all) to "",
        stringResource(R.string.njav_filter_individual) to "individual",
        stringResource(R.string.njav_filter_multiple) to "multiple",
        stringResource(R.string.njav_filter_chinese_subtitle) to "chinese-subtitle",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = { showSort = true }) {
            Text(
                text = sortOptions.firstOrNull { it.second == sort }?.first
                    ?: stringResource(R.string.njav_sort_default),
            )
        }
        OutlinedButton(onClick = { showFilter = true }) {
            Text(
                text = filterOptions.firstOrNull { it.second == (filter ?: "") }?.first
                    ?: stringResource(R.string.njav_filter_all),
            )
        }
        Spacer(Modifier.weight(1f))
    }

    ChoiceDialog(
        visible = showSort,
        title = stringResource(R.string.njav_sort_title),
        options = sortOptions,
        selectedValue = sort ?: "",
        onDismiss = { showSort = false },
        onSelect = { value ->
            showSort = false
            onSortChange(value.takeIf { it.isNotBlank() })
        },
    )
    ChoiceDialog(
        visible = showFilter,
        title = stringResource(R.string.njav_filter_title),
        options = filterOptions,
        selectedValue = filter ?: "",
        onDismiss = { showFilter = false },
        onSelect = { value ->
            showFilter = false
            onFilterChange(value.takeIf { it.isNotBlank() })
        },
    )
}

/**
 * 作品列表的**分页条**（26.8 引入，9.0 加编号，26.9.4 改成渐进展开）。
 *
 * 为什么要有它：站点一页给 40–59 条，直接铺出来是一条长瀑布 —— 用户既不知道一共多少，
 * 也没法退回去。这里有编号，还能一眼看到「第几 / 共几页」。
 *
 * ## ⭐ 26.9.4：页码**跟着当前页长**，不再「1 2 3 4 … 34」
 *
 * 用户原话：**「假设一共有 34 页，你不能说排列直接说 1 2 3 4 省略号 34，这样跨度太大，
 * 最好是只到 6，然后点了 6 之后会展开 7、8、9 三页」**。
 * 现在页码表由 `ArtistPaging.strip` 算：
 *
 * | 站在第几页 | 画出来 |
 * |---|---|
 * | 1 | `1 2 3 4 5 6`（点 6 → 翻到第 6 页，同时右边长出 9） |
 * | 6 | `1 … 6 7 8 9`（那三页就是被「展开」出来的） |
 * | 34（共 34 页） | `1 … 31 32 33 34` |
 *
 * 左边太长时收成 `1 …` + 4 个数字，一行最多 6 格 —— 页数再多也不会把这一行撑爆。
 *
 * ## ⭐ 26.9.4：点页码是**跳页**，不是一路翻过去
 *
 * 一页 = 站点自己的一页（见 `ArtistPaging`），所以点第 34 页就是**要站点第 34 页**，
 * 一次请求。26.9.3 及以前要把第 1..33 页全拉回来才知道第 34 页装的是什么
 * （Pornhub 一页 1.2 MB，跳一次几十次请求）。
 *
 * ⚠️ [canNext] 只管「**站点那边还有没有下一页**」，**不是**「到没到总页数」——
 * 用后者当闸门会让用户永远停在最后一页（想加载更多必须先点下一页，想点下一页又必须先
 * 加载更多，26.9.0 的「最多十页」完整推导见 `ArtistPaging`）。所以走到已加载的最后一页时
 * 按钮**依然是亮的**，点下去会去站点要下一页；站点确实没有了才会变灰。
 *
 * @param numbers 要画的页码（`null` = 省略号），由 `ArtistPaging.strip` 算好
 * @param isPaging 正在拉这一页：此时按钮禁用并显示小转圈，而不是让用户重复点。
 * @param footer 分页条下方的补充内容（例如「翻页失败 + 重试」）。
 */
@Composable
private fun ArtistPager(
    numbers: List<Int?>,
    page: Int,
    totalPages: Int,
    canPrev: Boolean,
    canNext: Boolean,
    isPaging: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onGoToPage: (Int) -> Unit,
    footer: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // totalPages <= 1 时 numbers 是空的：只有一页还摆一排数字，纯属噪音。
        if (numbers.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PagerArrow(enabled = canPrev && !isPaging, text = "‹", onClick = onPrev)
                numbers.forEach { number ->
                    if (number == null) {
                        Text(
                            text = "…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    } else {
                        PagerNumber(
                            number = number,
                            selected = number == page,
                            enabled = !isPaging,
                            onClick = { onGoToPage(number) },
                        )
                    }
                }
                PagerArrow(enabled = canNext && !isPaging, text = "›", onClick = onNext)
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(onClick = onPrev, enabled = canPrev && !isPaging) {
                Text(text = stringResource(R.string.artist_page_prev))
            }
            Text(
                text = stringResource(R.string.artist_page_page_indicator, page, totalPages),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onNext, enabled = canNext && !isPaging) {
                if (isPaging) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(text = stringResource(R.string.artist_page_next))
                }
            }
        }
        footer()
    }
}

/**
 * nJAV 女优页的**作品搜索框**（26.9.4 新增）。
 *
 * 用户的要求：「由于女优作品太多，njav 的女优界面加上一个搜索作品功能」。
 * 女优页把作品一次全铺出来（站点自己不分页），作品多的女优几百部，
 * 靠翻页找一部片子不现实。
 *
 * ⚠️ **本地筛选，不发请求**：输入过程中每敲一个字就联网，既慢又容易被站点的
 * 反爬挑战页拦住（nJAV 有挑战页）。所以下面如实写着「已加载 N 部」，
 * 让用户知道筛的是哪一批。
 */
@Composable
private fun ArtistWorkSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        singleLine = true,
        placeholder = {
            Text(
                text = stringResource(R.string.artist_work_search_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                TextButton(onClick = { onQueryChange("") }) {
                    Text(
                        text = stringResource(R.string.clear),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
    )
}

/** 页码条两端的 `‹` / `›`（等价于上一页 / 下一页，位置更顺手）。 */
@Composable
private fun PagerArrow(enabled: Boolean, text: String, onClick: () -> Unit) {
    val view = LocalView.current
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled) {
                VibrationUtil.performHapticFeedback(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = contentColor, style = MaterialTheme.typography.titleMedium)
    }
}

/** 单个页码。当前页用主色填充，其余是静音底。 */
@Composable
private fun PagerNumber(number: Int, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val background = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled && !selected) {
                VibrationUtil.performHapticFeedback(view)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
        )
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
                    // ⭐ 26.8：nJAV 女优页那两行资料（`158cm / 40J - 22 - 33` 与
                    // `1987-05-25 （39岁）`）**原样**显示。站点没给就整行不画 ——
                    // 这两行对很多女优本来就是空的，硬凑占位符反而像数据丢了。
                    profile?.measurements?.takeIf { it.isNotBlank() }?.let { line ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    profile?.birthday?.takeIf { it.isNotBlank() }?.let { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (isFollowed) {
                    // 已关注：**灰色描边的次级按钮**，文案说「取消关注」（下一步动作）。
                    // 实心主色按钮留给「关注」—— 两者一眼可分，不用猜自己关注上没有。
                    OutlinedButton(
                        onClick = onToggleFollow,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Text(text = stringResource(R.string.artist_unfollow_action))
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
