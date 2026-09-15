package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daisukikaffuchino.han1meviewer.BuildConfig
import io.github.daisukikaffuchino.han1meviewer.HA1_GITHUB_URL
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageState
import io.github.daisukikaffuchino.han1meviewer.logic.AppUpdateState
import io.github.daisukikaffuchino.han1meviewer.logic.AppUpdateInfo
import io.github.daisukikaffuchino.han1meviewer.logic.state.dataOrNull
import io.github.daisukikaffuchino.han1meviewer.ui.component.PageContent
import io.github.daisukikaffuchino.han1meviewer.ui.component.PullRefreshOverlay
import io.github.daisukikaffuchino.han1meviewer.ui.component.isFirstPageEmpty
import io.github.daisukikaffuchino.han1meviewer.ui.component.isFirstPageError
import io.github.daisukikaffuchino.han1meviewer.ui.component.isFirstPageLoading
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component.HomePageTopBar
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component.HomeTopBarAction
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component.AppUpdateActionState
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component.AppUpdateCard
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component.AnnouncementCard
import io.github.daisukikaffuchino.han1meviewer.ui.screen.rememberRandomLoadingHint
import io.github.daisukikaffuchino.han1meviewer.ui.theme.HanimeDefaults
import io.github.daisukikaffuchino.han1meviewer.util.toNetworkErrorMessageRes
import io.github.daisukikaffuchino.utils.SonnerToast

/**
 * 首页容器屏幕，负责连接 ViewModel 状态与导航回调。
 *
 * @param viewModel 提供首页数据与公告数据的 ViewModel。
 * @param isDrawerOpen 侧边抽屉是否已打开。
 * @param modifier 作用于屏幕根布局的修饰符。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomePageScreen(
    viewModel: HomePageViewModel,
    isDrawerOpen: Boolean,
    showNavigationIcon: Boolean,
    onEvent: (HomeUiEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageState by viewModel.homePageFlow.collectAsStateWithLifecycle()
    val updateState by viewModel.appUpdateState.collectAsStateWithLifecycle()
    val updateAnnouncement by viewModel.updateAnnouncement.collectAsStateWithLifecycle()
    val updateDownloadState by viewModel.updateDownloadState.collectAsStateWithLifecycle()
    // 大轮播「换一批」期间禁用按钮（26.9.7）—— 每次换一批都是一趟 1 MB+ 的请求。
    val isPhCarouselShuffling by viewModel.phCarouselShuffling.collectAsStateWithLifecycle()
    // 大轮播的标题跟着当前数据源走（推荐 / 主页热门），所以从 ViewModel 取而不是写死。
    val phCarouselTitleRes by viewModel.phCarouselTitleRes.collectAsStateWithLifecycle()
    val settings by SettingsRepository.settings.collectAsStateWithLifecycle()
    val refreshState = rememberPullToRefreshState()
    val homeListState = rememberLazyListState()
    var wasRefreshing by remember { mutableStateOf(false) }
    val loadingHint = rememberRandomLoadingHint()
    val topBarScrollProgress by remember(homeListState) {
        derivedStateOf {
            when {
                homeListState.firstVisibleItemIndex > 0 -> 1f
                else -> (homeListState.firstVisibleItemScrollOffset / 160f).coerceIn(0f, 1f)
            }
        }
    }
    val topBarContainerColor by animateColorAsState(
        targetValue = HanimeDefaults.Colors.pageSurface.copy(
            alpha = 0.68f + (0.28f * topBarScrollProgress)
        ),
        animationSpec = tween(durationMillis = 150),
        label = "HomeTopBarContainerColor",
    )
    val density = LocalDensity.current
    val contentTopPadding = with(density) {
        WindowInsets.statusBars.getTop(this).toDp() + 72.dp
    }
    // 非 hanime 数据源下首页内容是 AV，分类标题也要跟着换成 AV 那一套；
    // Pornhub 的栏目名与 nJAV 完全不同，所以再单开一个开关。
    val isAVSite = SettingsRepository.isAvSite
    val isPornhubSite = SettingsRepository.isPornhubSite
    // nJAV 的首页栏目名要与站点导航一致（26.8）。
    val isNjavSite = SettingsRepository.isNjavSite
    LaunchedEffect(Unit) {
        viewModel.initializeHomePage()
    }

    BackHandler(enabled = !isDrawerOpen) {
        onEvent(HomeUiEvent.ShowExitDialog)
    }

    val isCurrentlyRefreshing = (pageState as? PageState.Success)?.isRefreshing == true
    val simulatedUpdateDescription = stringResource(R.string.simulated_update_description)
    val simulatedUpdate = remember(simulatedUpdateDescription) {
        AppUpdateInfo(
            versionName = "Debug Preview",
            versionCode = Int.MAX_VALUE,
            downloadUrl = HA1_GITHUB_URL,
            updateDescription = simulatedUpdateDescription,
            forceUpdate = false,
        )
    }
    val showSimulatedUpdate = BuildConfig.DEBUG && settings.alwaysShowUpdateCard
    val availableUpdate = if (showSimulatedUpdate) {
        simulatedUpdate
    } else {
        (updateState as? AppUpdateState.Available)?.info
    }
    val forcedUpdate = availableUpdate?.takeIf { it.forceUpdate }

    /**
     * 「正在检查更新」该不该占屏。
     *
     * ⭐ 只在**首页内容还没到手**时占。更新检查要并发问 5 条更新源，部分网络下其中几条是
     * 黑洞（见 `AppUpdateChecker.raceUpdateSources`），老逻辑是「检查没回来就一直转圈」——
     * 内容其实早就到了，用户却盯着「正在检查更新」等。现在内容一到就照常显示，
     * 更新卡片/公告回来时再插进去；强制更新仍然整页接管（见下面的 forcedUpdate 分支）。
     */
    val blockingUpdateCheck =
        !showSimulatedUpdate && updateState is AppUpdateState.Checking && pageState.dataOrNull == null

    // ViewModel 的状态 → 纯 UI 状态。卡片只认 AppUpdateActionState，不依赖 ViewModel。
    val updateActionState = when (val s = updateDownloadState) {
        is HomePageViewModel.UpdateDownloadState.Idle -> AppUpdateActionState.Idle
        // Pending = 已入队但还没拿到第一个进度值 → 走「不确定」进度条
        is HomePageViewModel.UpdateDownloadState.Pending -> AppUpdateActionState.Downloading(null)
        is HomePageViewModel.UpdateDownloadState.Downloading ->
            AppUpdateActionState.Downloading(s.progress, s.bytes)

        is HomePageViewModel.UpdateDownloadState.ReadyToInstall -> AppUpdateActionState.ReadyToInstall
        is HomePageViewModel.UpdateDownloadState.Failed -> AppUpdateActionState.Failed(s.message)
    }

    LaunchedEffect(pageState) {
        val errorState = pageState as? PageState.Error
        if (wasRefreshing && errorState?.cachedInfo != null) {
            SonnerToast.error(errorState.throwable.toNetworkErrorMessageRes())
        }
        wasRefreshing = isCurrentlyRefreshing
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (forcedUpdate != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = contentTopPadding,
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 12.dp,
                    ),
                    verticalArrangement = Arrangement.Center,
                ) {
                    updateAnnouncement?.let { announcement ->
                        item(key = "forced_update_announcement") {
                            AnnouncementCard(
                                announcements = listOf(announcement),
                                onAnnouncementClick = { selectedAnnouncement ->
                                    onEvent(HomeUiEvent.ShowAnnouncementDialog(selectedAnnouncement))
                                },
                                onClose = null,
                            )
                        }
                    }
                    item(key = "forced_update_${forcedUpdate.versionCode}") {
                        AppUpdateCard(
                            updateInfo = forcedUpdate,
                            onUpdateClick = {
                                onEvent(HomeUiEvent.UpdateAction(forcedUpdate.downloadUrl, forcedUpdate.versionCode))
                            },
                            onIgnoreClick = {},
                            actionState = updateActionState,
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pullToRefresh(
                            state = refreshState,
                            isRefreshing = isCurrentlyRefreshing,
                            // 内容到手就允许下拉刷新 —— 更新检查还在跑不该让手势失效。
                            enabled = showSimulatedUpdate ||
                                updateState !is AppUpdateState.Checking ||
                                pageState.dataOrNull != null,
                            onRefresh = {
                                viewModel.getHomePage(isRefresh = true)
                            }
                        )
                ) {
                    PageContent(
                        isLoading = blockingUpdateCheck || pageState.isFirstPageLoading,
                        isError = pageState.isFirstPageError,
                        isEmpty = pageState.isFirstPageError || pageState.isFirstPageEmpty,
                        errorMessage = (pageState as? PageState.Error)?.throwable
                            ?.toNetworkErrorMessageRes()
                            ?.let { stringResource(it) }
                            ?: "",
                        onRetry = { viewModel.getHomePage(isRefresh = false) },
                        loadingMessage = if (blockingUpdateCheck) {
                            stringResource(R.string.checking_for_updates)
                        } else {
                            loadingHint
                        },
                    ) {
                        val homeData = pageState.dataOrNull

                        if (homeData != null) {
                            AnimatedContent(
                                targetState = homeData,
                                transitionSpec = {
                                    fadeIn(tween(300)) togetherWith fadeOut(tween(200))
                                },
                                label = "HomeContentAnimation",
                            ) { data ->
                                HomePageContent(
                                    data = data,
                                    updateInfo = availableUpdate,
                                    updateAnnouncement = updateAnnouncement,
                                    updateActionState = updateActionState,
                                    isAVSite = isAVSite,
                                    isPornhubSite = isPornhubSite,
                                    isNjavSite = isNjavSite,
                                    isPhCarouselShuffling = isPhCarouselShuffling,
                                    phCarouselTitleRes = phCarouselTitleRes,
                                    onEvent = onEvent,
                                    onCloseAnnouncement = viewModel::dismissAnnouncements,
                                    contentTopPadding = contentTopPadding,
                                    listState = homeListState,
                                )
                            }
                        }
                    }

                    PullRefreshOverlay(
                        state = refreshState,
                        isRefreshing = isCurrentlyRefreshing,
                    )
                }
        }
        HomePageTopBar(
            onOpenDrawer = { onEvent(HomeUiEvent.OpenDrawer) },
            onSearchClick = { onEvent(HomeUiEvent.OpenSearchPage()) },
            onNavigateToPreview = { onEvent(HomeUiEvent.NavigateToPreview) },
            onNavigateToActressGallery = { onEvent(HomeUiEvent.NavigateToActressGallery) },
            // 右上角按数据源变脸（见 HomeTopBarAction）：
            // nJAV 的主浏览入口是「女优一览 / 排行」，hanime 才是日历，Pornhub 两者都没有。
            topBarAction = when {
                isNjavSite -> HomeTopBarAction.Browse
                isPornhubSite -> HomeTopBarAction.None
                else -> HomeTopBarAction.Preview
            },
            containerColor = topBarContainerColor,
            showNavigationIcon = showNavigationIcon,
            modifier = Modifier.zIndex(1f),
        )
    }
}
