package io.github.daisukikaffuchino.han1meviewer.ui.screen.main

import android.content.Intent
import android.content.res.Configuration
import io.github.daisukikaffuchino.utils.LogUtil
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.HCacheManager
import io.github.daisukikaffuchino.han1meviewer.logic.exception.CloudflareBlockedException
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageState
import io.github.daisukikaffuchino.han1meviewer.ui.activity.MainActivity
import io.github.daisukikaffuchino.han1meviewer.ui.component.HapticTextButton as TextButton
import io.github.daisukikaffuchino.han1meviewer.ui.component.ChoiceDialog
import io.github.daisukikaffuchino.han1meviewer.ui.component.ConfirmDialog
import io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.HomeRoute
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.MainDrawerDestination
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.TopNavigation
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.VideoRoute
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.handleMainIntent
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.navigateDrawerDestination
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.HomePageViewModel
import io.github.daisukikaffuchino.han1meviewer.videoUrlRegex
import io.github.daisukikaffuchino.utils.SonnerToast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

@Composable
fun MainActivityContent(
    activity: MainActivity,
    viewModel: HomePageViewModel,
    pendingNavigationRequests: Flow<Intent>,
    showAuthGuard: Boolean,
    showSiteSwitchPicker: Boolean,
    currentSiteSource: String,
    logoutDialogCloseCurrentPage: Boolean?,
    onOpenAccount: () -> Unit,
    onLogoutClick: () -> Unit,
    onRequireLogin: () -> Unit,
    onSwitchSiteClick: () -> Unit,
    onDismissSiteSwitch: () -> Unit,
    onSelectSite: (String) -> Unit,
    onDismissLogout: () -> Unit,
    onConfirmLogout: () -> Unit,
    onOpenClipboardVideo: (String) -> Unit,
) {
    val backStack = viewModel.mainBackStack
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val snackbarHostState = remember { SnackbarHostState() }
    // 【自用构建】已移除「使用须知」20 秒强制阅读与「应用来源」校验两套拦截：
    // AppSettings 里这两个标志位默认就是 true，判定逻辑也已从调用方删除，
    // 启动后不会再弹任何拦截对话框，首页数据也不再受它们影响。
    val isDrawerOpen =
        drawerState.currentValue == DrawerValue.Open || drawerState.targetValue == DrawerValue.Open

    val homeState by viewModel.homePageFlow.collectAsStateWithLifecycle()
    val showStorageSwitchNotice by HCacheManager.storageSwitchNotice.collectAsStateWithLifecycle()
    val isLoggedIn by SettingsRepository.loginStateFlow.collectAsStateWithLifecycle()
    val checkInEnabled by SettingsRepository.checkInEnabledFlow.collectAsStateWithLifecycle()
    val headerAvatarUrl = if (isLoggedIn) {
        (homeState as? PageState.Success)?.info?.page?.avatarUrl
    } else {
        null
    }
    val headerUsername = if (isLoggedIn) {
        (homeState as? PageState.Success)?.info?.page?.username
    } else {
        null
    }
    val headerIsLoading = isLoggedIn && homeState is PageState.Loading
    val currentRoute = backStack.currentKey
    val previousRoute = backStack.backStack.getOrNull(backStack.backStack.lastIndex - 1)
    val selectedDrawerDestination = MainDrawerDestination.fromRoute(backStack.topLevelKey)
    val drawerEnabled = currentRoute == HomeRoute
    val permanentDrawer = (drawerEnabled || previousRoute == HomeRoute) &&
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(permanentDrawer) {
        if (permanentDrawer) drawerState.close()
    }
    LaunchedEffect(Unit) {
        val clipboardText = clipboard.getClipEntry()
            ?.clipData
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(activity)
        val videoCode = clipboardText?.let { videoUrlRegex.find(it)?.groupValues?.get(1) }
        if (videoCode != null) {
            val result = snackbarHostState.showSnackbar(
                message = activity.getString(R.string.detect_ha1_related_link_in_clipboard),
                actionLabel = activity.getString(R.string.enter),
                withDismissAction = true,
            )
            if (result == SnackbarResult.ActionPerformed) {
                onOpenClipboardVideo(videoCode)
            }
        }
    }
    LaunchedEffect(Unit) {
        pendingNavigationRequests.collect { intent ->
            backStack.handleMainIntent(intent)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.sessionExpiredMessage.collect { event ->
            event.message?.let(SonnerToast::error) ?: SonnerToast.error(event.fallbackResId)
        }
    }
    LaunchedEffect(homeState) {
        if (homeState is PageState.Error) {
            val throwable = (homeState as PageState.Error).throwable
            if (throwable is CloudflareBlockedException) {
                LogUtil.e("error", "被屏蔽时的处理")
            }
        }
    }
    MainActivityScaffold(
        drawerState = drawerState,
        drawerEnabled = drawerEnabled,
        permanentDrawer = permanentDrawer,
        applyHorizontalSafeInsets = currentRoute !is VideoRoute,
        selectedDestination = selectedDrawerDestination,
        avatarUrl = headerAvatarUrl,
        username = headerUsername,
        isLoggedIn = isLoggedIn,
        isLoading = headerIsLoading,
        currentSite = SettingsRepository.baseUrl,
        checkInEnabled = checkInEnabled,
        onAvatarClick = {
            if (isLoggedIn) {
                scope.launch { drawerState.close() }
                onOpenAccount()
            } else {
                scope.launch {
                    drawerState.close()
                    onRequireLogin()
                }
            }
        },
        onAvatarLongClick = {
            onLogoutClick()
        },
        onSwitchSiteClick = onSwitchSiteClick,
        onDrawerItemSelected = { destination ->
            val handled = backStack.navigateDrawerDestination(
                destination = destination,
                isLoggedIn = isLoggedIn,
                onRequireLogin = {
                    // 光弹一句「请先登录」是死路（用户不知道从哪儿登）——直接带去登录页，
                    // 顺手把抽屉收掉，免得它盖在登录页上面。
                    activity.openLogin()
                    scope.launch { drawerState.close() }
                },
            )
            if (handled) {
                scope.launch { drawerState.close() }
            }
            handled
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 自用构建：不再用 appAccessGranted 拦截，内容始终可见
            TopNavigation(
                activity = activity,
                backStack = backStack,
                isDrawerOpen = isDrawerOpen && !permanentDrawer,
                showHomeNavigationIcon = !permanentDrawer,
                homeContentStartPadding = if (permanentDrawer) 280.dp else 0.dp,
                onOpenDrawer = {
                    if (drawerEnabled) {
                        scope.launch { drawerState.open() }
                    }
                },
            )
            if (showAuthGuard) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                )
            }
            // 【自用构建】原来的「使用须知 20 秒强制阅读」「应用来源选择」「来源非法警告」
            // 三个对话框已整块移除，启动后不再有任何拦截；来源校验由上方 LaunchedEffect 直接放行。
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }
    // 切换站点：**直接列出三个站点让用户选**，而不是循环 + 二次确认。
    //
    // 以前只有两个站点时「点一下 → 确认」还凑合；到三个就成了
    // 「点一下 → 确认 → 发现不是想去的那站 → 再点一下 → 确认」，最多按四次。
    // 复用设置页那个 [ChoiceDialog]（底部弹层 + 单选），选中项就是当前站点。
    ChoiceDialog(
        visible = showSiteSwitchPicker,
        title = stringResource(R.string.switch_site),
        options = listOf(
            stringResource(R.string.site_picker_hanime) to SiteSource.Hanime1.value,
            stringResource(R.string.site_source_njav_with_host) to SiteSource.Njav.value,
            stringResource(R.string.site_source_pornhub_with_host) to SiteSource.Pornhub.value,
        ),
        selectedValue = currentSiteSource,
        onDismiss = onDismissSiteSwitch,
        onSelect = onSelectSite,
    )
    ConfirmDialog(
        visible = logoutDialogCloseCurrentPage != null,
        title = stringResource(R.string.sure_to_logout),
        message = "",
        confirmText = stringResource(R.string.sure),
        dismissText = stringResource(R.string.no),
        onConfirm = onConfirmLogout,
        onDismiss = onDismissLogout,
    )
    ConfirmDialog(
        visible = showStorageSwitchNotice,
        title = stringResource(R.string.save_failed_title),
        message = stringResource(R.string.save_failed_message),
        confirmText = stringResource(R.string.understood),
        dismissText = null,
        onConfirm = HCacheManager::dismissStorageSwitchNotice,
        onDismiss = HCacheManager::dismissStorageSwitchNotice,
    )
}

// 【自用构建】AppSourceDialog（应用来源选择）已整体移除，不再需要。
