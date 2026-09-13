package io.github.daisukikaffuchino.han1meviewer.ui.screen.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults.pinnedScrollBehavior
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.FollowedArtistStore
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.logic.model.SubscriptionItem
import io.github.daisukikaffuchino.han1meviewer.logic.model.SubscriptionVideosItem
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import io.github.daisukikaffuchino.han1meviewer.ui.component.ChoiceDialog
import io.github.daisukikaffuchino.han1meviewer.ui.component.IconButton
import io.github.daisukikaffuchino.han1meviewer.ui.component.PullRefreshOverlay
import io.github.daisukikaffuchino.han1meviewer.ui.component.appbar.HanimeScaffold
import io.github.daisukikaffuchino.han1meviewer.ui.component.content.EmptyContent
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.subscription.SubscriptionContent
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.subscription.SubscriptionEvent
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.subscription.SubscriptionUiState
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.MySubscriptionsViewModel
import kotlinx.coroutines.launch

/**
 * 订阅页面 Screen 层。
 *
 * 持有 [MySubscriptionsViewModel]，管理缓存、下拉刷新、加载更多等状态编排。
 * 渲染委托给 [SubscriptionContent]。
 *
 * ## ⭐ 26.6.3：这一段**不需要登录**
 *
 * 页面上永远有「关注的作者」这一段 —— 它读的是本机
 * [FollowedArtistStore]（Pornhub / nJAV 的关注都存在这里），
 * **未登录时不发任何网络请求**，因此也不会再出现「没登录 → 整页报错」。
 *
 * hanime 的服务端订阅只占第二段：[SettingsRepository.isAlreadyLogin] 为真时才请求、
 * 才渲染。两者互不依赖，是个刻意的取舍 —— 用户的原话是「最好登录不跟网站挂钩」。
 *
 * @param navigateBack 返回回调
 * @param viewModel 订阅 ViewModel（只服务 hanime 那一段）
 * @param onClickArtist 点击 hanime 订阅作者 → 跳搜索（hanime 没有作者页）
 * @param onLongClickArtist 长按 hanime 订阅作者 → 复制分享文本
 * @param onClickFollowed 点击「关注的作者」→ 进作者页（或退回搜索）
 * @param onLongClickFollowed 长按「关注的作者」→ 复制分享文本
 * @param onClickVideosItem 点击视频 → 跳转详情
 * @param onLongClickVideosItem 长按视频 → 复制分享文本
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SubscriptionScreen(
    navigateBack: () -> Unit,
    viewModel: MySubscriptionsViewModel,
    onClickArtist: (String) -> Unit,
    onLongClickArtist: (String) -> Unit,
    onClickFollowed: (ArtistRef) -> Unit,
    onLongClickFollowed: (ArtistRef) -> Unit,
    onClickVideosItem: (String) -> Unit,
    onLongClickVideosItem: (String, String) -> Unit,
) {
    val state by viewModel.subscriptionsState.collectAsStateWithLifecycle()
    val settings by SettingsRepository.settings.collectAsStateWithLifecycle()
    val cachedArtists = rememberSaveable { mutableStateOf<List<SubscriptionItem>>(emptyList()) }
    val cachedVideos =
        rememberSaveable { mutableStateOf<List<SubscriptionVideosItem>>(emptyList()) }
    val scrollBehavior = pinnedScrollBehavior(rememberTopAppBarState())
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var showArtistRowsDialog by rememberSaveable { mutableStateOf(false) }

    val refreshState = rememberPullToRefreshState()
    var isRefreshing by rememberSaveable { mutableStateOf(false) }

    val isLoggedIn = settings.isAlreadyLogin
    val canLoadMore = viewModel.canLoadMore()

    // 本机关注的作者（三个站点通用）。**不经过任何网络**，所以未登录也一定有内容。
    val localFollowed = remember(settings.followedArtistsJson) {
        FollowedArtistStore.asArtistRefs
    }

    LaunchedEffect(state, isLoggedIn) {
        // 未登录：服务端那一段整个不参与 —— 不请求、不报错、不显示加载态。
        if (!isLoggedIn) return@LaunchedEffect
        when (val s = state) {
            is WebsiteState.Success -> {
                cachedArtists.value = s.info.subscriptions.toList()
                cachedVideos.value = s.info.subscriptionsVideos.toList()
            }

            is WebsiteState.Loading -> {
                if (cachedArtists.value.isEmpty()) viewModel.loadMySubscriptions()
            }

            else -> Unit
        }
        if (state !is WebsiteState.Loading) {
            isRefreshing = false
        }
    }

    val uiState = SubscriptionUiState(
        followed = localFollowed,
        artists = cachedArtists.value,
        videos = cachedVideos.value,
        isLoggedIn = isLoggedIn,
        isRefreshing = isRefreshing,
        canLoadMore = canLoadMore,
        error = (state as? WebsiteState.Error)?.throwable,
        showCached = state is WebsiteState.Loading && cachedArtists.value.isNotEmpty(),
    )

    ChoiceDialog(
        visible = showArtistRowsDialog,
        title = stringResource(R.string.subscription_artist_rows),
        options = (1..3).map { rows ->
            stringResource(R.string.subscription_artist_rows_option, rows) to rows.toString()
        },
        selectedValue = settings.subscriptionArtistRows.toString(),
        onDismiss = { showArtistRowsDialog = false },
        onSelect = { value ->
            showArtistRowsDialog = false
            scope.launch {
                SettingsRepository.setSubscriptionArtistRows(value.toInt())
            }
        },
    )

    val handleEvent: (SubscriptionEvent) -> Unit = { event ->
        when (event) {
            SubscriptionEvent.OnBack -> navigateBack()
            is SubscriptionEvent.OnClickArtist -> onClickArtist(event.artistName)
            is SubscriptionEvent.OnLongClickArtist -> onLongClickArtist(event.artistName)
            is SubscriptionEvent.OnClickFollowed -> onClickFollowed(event.artist)
            is SubscriptionEvent.OnLongClickFollowed -> onLongClickFollowed(event.artist)
            is SubscriptionEvent.OnClickVideo -> onClickVideosItem(event.videoCode)
            is SubscriptionEvent.OnLongClickVideo -> onLongClickVideosItem(
                event.videoCode,
                event.title
            )

            SubscriptionEvent.OnRefresh -> {
                isRefreshing = true
                if (isLoggedIn) {
                    viewModel.loadMySubscriptions(forceReload = true)
                } else {
                    // 未登录时下拉刷新没有服务端可刷：立刻收掉转圈，别转个不停。
                    isRefreshing = false
                }
            }

            SubscriptionEvent.OnLoadMore -> if (isLoggedIn) viewModel.loadMySubscriptions()
        }
    }

    HanimeScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        title = stringResource(R.string.follow_and_subscribe),
        onBack = navigateBack,
        actions = {
            IconButton(onClick = { showArtistRowsDialog = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_table_rows),
                    contentDescription = stringResource(R.string.subscription_artist_rows),
                )
            }
        },
        scrollBehavior = scrollBehavior,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .pullToRefresh(
                    state = refreshState,
                    isRefreshing = isRefreshing,
                    onRefresh = { handleEvent(SubscriptionEvent.OnRefresh) }
                )
        ) {
            // 未登录时**永远**走这条分支：本机关注至少画一句「怎么关注」的提示。
            val hasServerContent = cachedArtists.value.isNotEmpty() || cachedVideos.value.isNotEmpty()
            when {
                !isLoggedIn -> SubscriptionContent(
                    uiState = uiState,
                    onEvent = handleEvent,
                    gridState = gridState,
                    artistRows = settings.subscriptionArtistRows,
                )

                state is WebsiteState.Loading && !hasServerContent -> {
                    LoadingIndicator(Modifier.align(Alignment.Center))
                }

                state is WebsiteState.Error && !hasServerContent -> {
                    EmptyContent(
                        hint = stringResource(
                            R.string.load_failed_with_reason,
                            (state as WebsiteState.Error).throwable.message.orEmpty()
                        ),
                        picRes = R.drawable.h_chan_sad
                    )
                }

                else -> SubscriptionContent(
                    uiState = uiState,
                    onEvent = handleEvent,
                    gridState = gridState,
                    artistRows = settings.subscriptionArtistRows,
                )
            }

            PullRefreshOverlay(
                state = refreshState,
                isRefreshing = isRefreshing,
            )
        }
    }
}
