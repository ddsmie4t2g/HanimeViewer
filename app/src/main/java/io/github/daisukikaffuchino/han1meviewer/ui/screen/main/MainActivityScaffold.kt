package io.github.daisukikaffuchino.han1meviewer.ui.screen.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.MainDrawerDestination
import io.github.daisukikaffuchino.han1meviewer.ui.preview.ComponentPreview
import io.github.daisukikaffuchino.han1meviewer.ui.theme.HanimeDefaults
import io.github.daisukikaffuchino.utils.VibrationUtil
import kotlinx.coroutines.launch

@Composable
fun MainActivityScaffold(
    drawerState: DrawerState,
    drawerEnabled: Boolean,
    permanentDrawer: Boolean,
    applyHorizontalSafeInsets: Boolean,
    selectedDestination: MainDrawerDestination?,
    avatarUrl: String?,
    username: String?,
    isLoggedIn: Boolean,
    isLoading: Boolean,
    currentSite: String,
    /**
     * 当前数据源（9.0）。
     *
     * 抽屉里有些条目**只对一个站点有意义** —— 「hanime 站点账号」是 hanime 站自己的
     * 登录态（订阅 / 在线清单 / 评论），切到 nJAV / Pornhub 之后它就是一个
     * 点了也没用的东西（用户的原话：「njav 站点时侧边栏里的 hanime 站点账号就不应该存在」）。
     */
    siteSource: SiteSource,
    checkInEnabled: Boolean,
    onAvatarClick: () -> Unit,
    onAvatarLongClick: () -> Unit,
    onSwitchSiteClick: () -> Unit,
    onDrawerItemSelected: (MainDrawerDestination) -> Boolean,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val drawerFraction by animateFloatAsState(
        targetValue = if (drawerState.currentValue == DrawerValue.Open || drawerState.targetValue == DrawerValue.Open) 1f else 0f,
        label = "drawer_fraction",
    )
    val currentContent by rememberUpdatedState(content)
    val movableContent = remember {
        movableContentOf {
            currentContent()
        }
    }
    val horizontalSafeInsetsModifier = if (applyHorizontalSafeInsets) {
        Modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
        )
    } else {
        Modifier
    }

    val drawerContent: @Composable ColumnScope.() -> Unit = {
        MainDrawerContent(
            selectedDestination = selectedDestination,
            avatarUrl = avatarUrl,
            username = username,
            isLoggedIn = isLoggedIn,
            isLoading = isLoading,
            currentSite = currentSite,
            siteSource = siteSource,
            checkInEnabled = checkInEnabled,
            onAvatarClick = onAvatarClick,
            onAvatarLongClick = onAvatarLongClick,
            onSwitchSiteClick = onSwitchSiteClick,
            onDrawerItemSelected = onDrawerItemSelected,
        )
    }

    if (permanentDrawer) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HanimeDefaults.Colors.pageSurface)
                .then(horizontalSafeInsetsModifier),
        ) {
            PermanentDrawerSheet(
                modifier = Modifier.width(280.dp),
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                windowInsets = WindowInsets(0, 0, 0, 0),
                content = drawerContent,
            )
            movableContent()
        }
    } else {
        ModalNavigationDrawer(
            modifier = Modifier
                .fillMaxSize()
                .background(HanimeDefaults.Colors.pageSurface)
                .then(horizontalSafeInsetsModifier),
            gesturesEnabled = drawerEnabled,
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                    content = drawerContent,
                )
            },
        ) {
            MainDrawerBody(drawerFraction = drawerFraction, content = movableContent)

            BackHandler(
                enabled = drawerState.currentValue == DrawerValue.Open ||
                    drawerState.targetValue == DrawerValue.Open,
            ) {
                scope.launch { drawerState.close() }
            }
        }
    }
}

@Composable
private fun MainDrawerContent(
    selectedDestination: MainDrawerDestination?,
    avatarUrl: String?,
    username: String?,
    isLoggedIn: Boolean,
    isLoading: Boolean,
    currentSite: String,
    siteSource: SiteSource,
    checkInEnabled: Boolean,
    onAvatarClick: () -> Unit,
    onAvatarLongClick: () -> Unit,
    onSwitchSiteClick: () -> Unit,
    onDrawerItemSelected: (MainDrawerDestination) -> Boolean,
) {
    MainDrawerHeader(
        avatarUrl = avatarUrl,
        username = username,
        isLoggedIn = isLoggedIn,
        isLoading = isLoading,
        currentSite = currentSite,
        onAvatarClick = onAvatarClick,
        onAvatarLongClick = onAvatarLongClick,
        onSwitchSiteClick = onSwitchSiteClick,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        MainDrawerPrimaryItems(
            selectedDestination = selectedDestination,
            onDrawerItemSelected = onDrawerItemSelected,
            checkInEnabled = checkInEnabled,
        )
        MainDrawerSection(
            titleRes = R.string.account_section,
            items = buildList {
                // 自建账号在前：**一个号统筹三个站点**（关注 / 本机清单 / 观看记录）。
                add(MainDrawerDestination.MyAccount)
                // hanime 站点账号是 hanime 自己的登录态（订阅/在线清单/评论），
                // 名字里就写明只属于 hanime。
                // ⭐ 9.0：切到 nJAV / Pornhub 时**不显示**它 —— 那两个站没有「hanime 账号」
                // 这回事，摆在那里只会让人点进去发现什么都做不了（用户报的原话）。
                if (siteSource == SiteSource.Hanime1) add(MainDrawerDestination.SiteAccount)
            },
            selectedDestination = selectedDestination,
            onItemClick = { onDrawerItemSelected(it) },
        )
        MainDrawerSection(
            titleRes = R.string.my_list,
            items = listOf(
                MainDrawerDestination.WatchLater,
                MainDrawerDestination.FavVideo,
                MainDrawerDestination.Playlist,
                // ⭐ 9.0：收藏夹与播放清单并列，但作用域互不相干（各自查各自的 kind）。
                MainDrawerDestination.Favorites,
                MainDrawerDestination.Subscription,
            ),
            selectedDestination = selectedDestination,
            onItemClick = { onDrawerItemSelected(it) },
        )
        MainDrawerSection(
            titleRes = R.string.video,
            items = listOf(
                MainDrawerDestination.WatchHistory,
                MainDrawerDestination.Download,
            ),
            selectedDestination = selectedDestination,
            onItemClick = { onDrawerItemSelected(it) },
        )
        // 26.8.4 起抽屉里**没有**「浏览 / 女优」那一段了：它是 nJAV 的主浏览入口，
        // 而抽屉第 6 项往下要滚动才看得见 —— 藏在那儿等于没有。现在挪到首页右上角，
        // 按数据源变脸（nJAV → 浏览，hanime → 日历，Pornhub → 无）。
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun MainDrawerBody(
    drawerFraction: Float,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(HanimeDefaults.Colors.pageSurface),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val scale = 1f - (0.03f * drawerFraction)
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - (0.08f * drawerFraction)
                },
        ) {
            content()
            if (drawerFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.14f * drawerFraction)),
                )
            }
        }
    }
}

@Composable
private fun MainDrawerPrimaryItems(
    selectedDestination: MainDrawerDestination?,
    onDrawerItemSelected: (MainDrawerDestination) -> Boolean,
    checkInEnabled: Boolean,
) {
    val view = LocalView.current
    val primaryItems = buildList {
        add(MainDrawerDestination.Home)
        add(MainDrawerDestination.Settings)
        if (checkInEnabled) add(MainDrawerDestination.DailyCheckIn)
    }
    Column {
        primaryItems.forEach { item ->
            NavigationDrawerItem(
                label = { Text(stringResource(item.titleRes)) },
                icon = {
                    Icon(
                        painter = painterResource(item.iconRes),
                        contentDescription = stringResource(item.titleRes),
                    )
                },
                selected = selectedDestination == item,
                onClick = {
                    VibrationUtil.performHapticFeedback(view)
                    onDrawerItemSelected(item)
                },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
    }
}

@Composable
private fun MainDrawerSection(
    titleRes: Int,
    items: List<MainDrawerDestination>,
    selectedDestination: MainDrawerDestination?,
    onItemClick: (MainDrawerDestination) -> Unit,
) {
    val view = LocalView.current
    Spacer(modifier = Modifier.height(8.dp))
    HorizontalDivider()
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 12.dp),
    )
    Column {
        items.forEach { item ->
            NavigationDrawerItem(
                label = { Text(stringResource(item.titleRes)) },
                icon = {
                    Icon(
                        painter = painterResource(item.iconRes),
                        contentDescription = stringResource(item.titleRes),
                    )
                },
                selected = selectedDestination == item,
                onClick = {
                    VibrationUtil.performHapticFeedback(view)
                    onItemClick(item)
                },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 600)
@Composable
private fun MainActivityScaffoldPreview() {
    ComponentPreview {
        MainActivityScaffold(
            drawerState = rememberDrawerState(initialValue = DrawerValue.Open),
            drawerEnabled = true,
            permanentDrawer = true,
            applyHorizontalSafeInsets = true,
            selectedDestination = MainDrawerDestination.Home,
            avatarUrl = null,
            username = "Han1meViewer",
            isLoggedIn = true,
            isLoading = false,
            currentSite = "https://hanime1.me/",
            siteSource = SiteSource.Hanime1,
            checkInEnabled = true,
            onAvatarClick = {},
            onAvatarLongClick = {},
            onSwitchSiteClick = {},
            onDrawerItemSelected = { true },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            )
        }
    }
}
