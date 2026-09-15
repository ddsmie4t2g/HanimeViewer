package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.AppUpdateInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.Announcement
import io.github.daisukikaffuchino.han1meviewer.ui.component.lazy.LazyColumn
import io.github.daisukikaffuchino.han1meviewer.ui.preview.ComponentPreview
import io.github.daisukikaffuchino.han1meviewer.ui.preview.fakeAnnouncements
import io.github.daisukikaffuchino.han1meviewer.ui.preview.fakeHomePage
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component.AnnouncementCard
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component.AppUpdateActionState
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component.AppUpdateCard
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component.BannerCarousel
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component.CategoryRow
import io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component.FeaturedCarousel

/**
 * 渲染首页可滚动内容区域。
 *
 * @param data 主页数据
 * @param onEvent 主页事件回调
 * @param onCloseAnnouncement 关闭公告时调用。
 * @param modifier 应用于列表根布局的修饰符。
 */
@Composable
fun HomePageContent(
    data: HomeData,
    updateInfo: AppUpdateInfo?,
    updateAnnouncement: Announcement?,
    updateActionState: AppUpdateActionState,
    isAVSite: Boolean,
    onEvent: (HomeUiEvent) -> Unit,
    onCloseAnnouncement: () -> Unit,
    contentTopPadding: Dp,
    modifier: Modifier = Modifier,
    isPornhubSite: Boolean = false,
    /** 是否走 nJAV：它的首页栏目要按**站点真实导航**命名（26.8）。 */
    isNjavSite: Boolean = false,
    /**
     * 大轮播是否正在「换一批」（26.9.7）。
     *
     * 只有 Pornhub 的「推荐」那一行有这个概念，其它行/其它数据源传默认值即可。
     */
    isPhCarouselShuffling: Boolean = false,
    /**
     * 大轮播此刻的标题资源 id（26.9.7）。
     *
     * 「换一批」会在**两个数据源**之间轮换（推荐 ↔ 主页热门），标题必须跟着走，
     * 否则换出主页热门的片子、标题还写着「推荐」。
     * ⚠️ 只影响 `HOME_CATEGORY_RECOMMENDED` 那一行；其它行照旧用 `category.titleRes`。
     */
    phCarouselTitleRes: Int = R.string.ph_recommended,
    listState: LazyListState = rememberLazyListState()
) {
    val banners = remember(data.page.banner) {
        listOfNotNull(data.page.banner)
    }
    val announcements = remember(data.announcements) {
        data.announcements.filter { it.isActive }
    }

    val categories = remember(data.page, isAVSite, isPornhubSite, isNjavSite) {
        buildCategoryList(data.page, isAVSite, isPornhubSite, isNjavSite)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(top = contentTopPadding),
    ) {
        item(key = "banner") {
            BannerCarousel(
                banners = banners,
                onBannerClick = { videoCode ->
                    videoCode?.let {
                        onEvent(HomeUiEvent.OpenVideo(it))
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        if (updateInfo != null) {
            item(key = "app_update_${updateInfo.versionCode}") {
                AppUpdateCard(
                    updateInfo = updateInfo,
                    onUpdateClick = {
                        onEvent(HomeUiEvent.UpdateAction(updateInfo.downloadUrl, updateInfo.versionCode))
                    },
                    onIgnoreClick = {
                        onEvent(HomeUiEvent.IgnoreUpdate(updateInfo.versionCode))
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    actionState = updateActionState,
                )
            }
        }
        if (updateAnnouncement != null) {
            item(key = "update_announcement") {
                AnnouncementCard(
                    announcements = listOf(updateAnnouncement),
                    onAnnouncementClick = { announcement ->
                        onEvent(HomeUiEvent.ShowAnnouncementDialog(announcement))
                    },
                    onClose = null,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        if (announcements.isNotEmpty()) {
            item(key = "announcement") {
                AnnouncementCard(
                    announcements = announcements,
                    onAnnouncementClick = { announcement ->
                        onEvent(HomeUiEvent.ShowAnnouncementDialog(announcement))
                    },
                    onClose = onCloseAnnouncement,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
        categories.forEach { category ->
            item(key = "category_${category.titleRes}") {
                // 取数完全一样，只有「怎么画」按 style 分流（见 HomeCategoryStyle）。
                // ⚠️ 分支**写平在这里**，别抽成扩展函数 —— 抽出去会让 `state`
                //    遮蔽 `items(...)`，报一长串 receiver type mismatch。
                when (category.style) {
                    HomeCategoryStyle.CAROUSEL -> FeaturedCarousel(
                        // 标题**跟着数据源走**：这一行的内容会在「推荐」和「主页热门」之间
                        // 轮换，换过去之后标题还写着「推荐」就成了假标签。
                        title = stringResource(
                            if (category.key == HOME_CATEGORY_RECOMMENDED) phCarouselTitleRes
                            else category.titleRes
                        ),
                        videos = category.videos,
                        onMoreClick = {
                            val params = category.toAdvancedSearchParams()
                            if (params.isNotEmpty()) {
                                onEvent(HomeUiEvent.NavigateToSearchAdvanced(params))
                            }
                        },
                        onVideoClick = { code ->
                            onEvent(HomeUiEvent.OpenVideo(code))
                        },
                        // 「换一批」只有 Pornhub 那一行有（它的取数是站点的另一个页面，
                        // 能真的换出另一批）。别的行是检索接口的固定排序，换不出来，
                        // 所以用 key 判断，而不是「有轮播就给按钮」。
                        onShuffle = if (category.key == HOME_CATEGORY_RECOMMENDED) {
                            { onEvent(HomeUiEvent.ShufflePhCarousel) }
                        } else {
                            null
                        },
                        isShuffling = isPhCarouselShuffling,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    HomeCategoryStyle.ROW -> CategoryRow(
                        title = stringResource(category.titleRes),
                        videos = category.videos,
                        onMoreClick = {
                            val params = category.toAdvancedSearchParams()
                            if (params.isNotEmpty()) {
                                onEvent(HomeUiEvent.NavigateToSearchAdvanced(params))
                            }
                        },
                        onVideoClick = { code ->
                            onEvent(HomeUiEvent.OpenVideo(code))
                        },
                        onVideoLongClick = { _, _ ->
                            // onEvent(HomeUiEvent.LongPressVideoCopy(code, title))
                        },
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "首页主内容")
@Composable
private fun HomePageContentPreview() {
    ComponentPreview {
        Surface(color = MaterialTheme.colorScheme.background) {
            HomePageContent(
                data = HomeData(
                    page = fakeHomePage,
                    announcements = fakeAnnouncements,
                ),
                updateInfo = AppUpdateInfo(
                    versionName = "26.1.0",
                    versionCode = 260720,
                    downloadUrl = "https://example.com",
                    updateDescription = "A new version is ready.",
                    forceUpdate = false,
                ),
                updateAnnouncement = fakeAnnouncements.first(),
                updateActionState = AppUpdateActionState.Downloading(30),
                isAVSite = false,
                onEvent = {},
                onCloseAnnouncement = {},
                contentTopPadding = 72.dp,
            )
        }
    }
}
