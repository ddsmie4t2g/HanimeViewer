package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage

import androidx.annotation.StringRes
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.daisukikaffuchino.han1meviewer.logic.model.Announcement
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HomePage

/**
 * 首页主要数据源
 * @param page 主页主要数据
 * @param announcements 首页公告列表
 */
data class HomeData(
    val page: HomePage,
    val announcements: List<Announcement> = emptyList()
)

/**
 * 为首页搜索相关组件提供搜索历史查询能力。
 */
val LocalSearchHistoryQuery = staticCompositionLocalOf<suspend (String) -> List<String>> {
    { emptyList() }
}

/**
 * 首页一行的**呈现形态**（26.9.6）。
 *
 * 绝大多数栏目是「一排等宽小卡片，横向滚」（[ROW]）。但站点自己的「推荐」
 * 是**编辑/算法挑选**出来的一批，值得给它一块大地方 —— 用户原话是
 * 「把这个推荐的做成占据空间大点的那种单个轮播」。
 *
 * ⚠️ 只影响**怎么画**，不影响**取数** —— 取数一律走 [HomeCategory.genre] 等标记。
 * 所以加一种形态**不需要**动 `NetworkRepo` / `HomePageMappers` 的取数逻辑。
 */
enum class HomeCategoryStyle {
    /** 一排小卡片，横向滚动（默认）。 */
    ROW,

    /** 一屏一张大图，左右滑（轮播）。 */
    CAROUSEL,
}

/**
 * 首页视频分类行数据。
 *
 * @param titleRes 分类标题的字符串资源。
 * @param genre 高级搜索使用的可选类型参数。
 * @param sort 高级搜索使用的可选排序参数。
 * @param tags 高级搜索使用的可选标签参数。
 * @param videos 当前分类下展示的视频列表。
 * @param style 这一行怎么画，见 [HomeCategoryStyle]。
 */
data class HomeCategory(
    val key: String,
    @param:StringRes val titleRes: Int,
    val genre: String? = null,
    val sort: String? = null,
    val tags: String? = null,
    val videos: List<HanimeInfo>,
    val style: HomeCategoryStyle = HomeCategoryStyle.ROW,
)
