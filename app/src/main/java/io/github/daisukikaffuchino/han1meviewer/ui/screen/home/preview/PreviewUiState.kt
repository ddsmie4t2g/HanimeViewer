package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.preview

import androidx.compose.runtime.saveable.listSaver
import io.github.daisukikaffuchino.han1meviewer.logic.model.GetchuPreview
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimePreview
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState

/**
 * 预览页面路由状态。
 *
 * @param currentDateCode 当前日期码 (yyyyMM)
 * @param selectedIndex 当前选中的预览项索引
 */
data class PreviewRouteUiState(
    val currentDateCode: String = currentDateCode(),
    val selectedIndex: Int = 0,
) {
    companion object {
        val Saver = listSaver<PreviewRouteUiState, Any>(
            save = { listOf(it.currentDateCode, it.selectedIndex) },
            restore = {
                PreviewRouteUiState(
                    currentDateCode = it[0] as String,
                    selectedIndex = it[1] as Int,
                )
            },
        )
    }
}

/**
 * 预览页面的月份头部展示状态。
 *
 * @param dateCode 日期码
 * @param headerImageUrl 顶部横幅图片 URL
 * @param prevLabel 上一月份标签
 * @param nextLabel 下一月份标签
 * @param canPrev 是否可切换到上一月
 * @param canNext 是否可切换到下一月
 */
data class PreviewMonthHeaderState(
    val dateCode: String,
    val headerImageUrl: String?,
    val prevLabel: String,
    val nextLabel: String,
    val canPrev: Boolean,
    val canNext: Boolean,
)

/**
 * 图片查看器状态。
 *
 * @param imageUrls 图片 URL 列表
 * @param initialPage 初始展示的图片索引
 */
data class PreviewImageViewerState(
    val imageUrls: List<String>,
    val initialPage: Int,
)

/**
 * 【月度归档】站方停更月份（`202605` 起）的展示状态。
 *
 * 这些月份的 `/previews/{yyyyMM}` 整段返回 500，页面改为按上市月份检索，
 * 列出该月 1 日至月底上线的全部番剧 —— 它们已经上映，不再具有"预告"性质。
 *
 * @param items 已加载的番剧
 * @param isLoading 首屏是否在加载
 * @param isLoadingMore 是否在加载下一页
 * @param hasError 最近一次请求是否失败
 * @param noMoreData 是否已经没有更多数据
 * @param loadedPages 已成功加载的页数
 */
data class PreviewArchiveUiState(
    val items: List<HanimeInfo> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val hasError: Boolean = false,
    val noMoreData: Boolean = false,
    val loadedPages: Int = 0,
) {
    /** 是否有可展示的内容 */
    val hasItems: Boolean get() = items.isNotEmpty()

    /** 首屏加载失败且没有任何内容 */
    val isFatalError: Boolean get() = hasError && items.isEmpty()
}

/**
 * 预览页面 UI 状态。
 *
 * @param routeState 路由/翻页状态
 * @param currentDateLabel 当前月份标签
 * @param prevDateLabel 上一月份标签
 * @param nextDateLabel 下一月份标签
 * @param monthAnimationDirection 月份切换动画方向
 * @param displayState 当前展示的网络状态
 * @param commentCount 评论区评论数
 * @param canPrev 是否可切换到上一月
 * @param canNext 是否可切换到下一月
 * @param monthHeaderState 月份头部状态
 * @param imageViewerState 图片查看器状态，null 表示未打开
 * @param archiveState 【月度归档】非 null 表示当前月份已停更，页面展示的是
 *        「按上市月份检索」的结果，而不是站方预告。此时 [displayState] 不参与渲染。
 * @param selectedTab 顶部标签页：已上架（hanime，默认）/ 发售表（Getchu）
 * @param getchuState Getchu 该月发售表的加载状态
 */
data class PreviewUiState(
    val routeState: PreviewRouteUiState = PreviewRouteUiState(),
    val currentDateLabel: String = "",
    val prevDateLabel: String = "",
    val nextDateLabel: String = "",
    val monthAnimationDirection: Int = 1,
    val displayState: WebsiteState<HanimePreview> = WebsiteState.Loading,
    val commentCount: Int = 0,
    val canPrev: Boolean = false,
    val canNext: Boolean = false,
    val monthHeaderState: PreviewMonthHeaderState,
    val imageViewerState: PreviewImageViewerState? = null,
    val archiveState: PreviewArchiveUiState? = null,
    val selectedTab: PreviewTab = PreviewTab.Hanime,
    val getchuState: PageState<GetchuPreview> = PageState.Loading,
) {
    /**
     * 是否显示顶部标签行。
     *
     * 只有「站方预告已停更」的月份才需要它：更早的月份仍有完整预告页
     * （海报 + 播放器 + 剧照），那两个标签对它们没有意义。
     */
    val showTabs: Boolean get() = archiveState != null
}

/**
 * 日历页顶部的两个标签。
 *
 * 这一页有两个**不同含义**的「这个月」：
 *
 * | 标签 | 含义 | 数据源 |
 * |---|---|---|
 * | [Hanime] | 该月**已经在 hanime 上架**的番剧 | hanime 站内检索 `date=yyyy 年 m 月` |
 * | [Getchu] | 该月**预定发售**的里番（发售表） | getchu.com `all/month_title.html` |
 *
 * ⭐ 为什么必须分成两个标签（26.8.4 的根因）：以前只有「已上架」这一个列表，
 * 而站方自 202605 起停更了预告页，于是当月常常是空的。用户看到空列表就会认为
 * 「明明上了几部却没显示」。
 *
 * ⭐ **26.8.3 起默认停在 [Hanime]**（两个标签的顺序也调成它在左）：用户点日历想问的是
 * 「这个月已经出来的里番有哪些」，那是「已上架」而不是「还没发售的预定表」。
 * 发售表仍然保留在第二个标签里。[Getchu] 里那一套（封面走中转、点封面进详情页）
 * 一个字都没动 —— 改的只是**进门先看哪一个**。
 */
enum class PreviewTab {
    /** 已上架 —— 该月已在 hanime 上线的番剧（默认） */
    Hanime,

    /** 发售表 —— 该月预定发售（Getchu） */
    Getchu,
}

/**
 * 预览页面的用户交互事件。
 */
sealed interface PreviewEvent {
    /** 返回上一页 */
    data object OnBack : PreviewEvent

    /** 切换日历页顶部标签（发售表 / 已上架） */
    data class OnSelectTab(val tab: PreviewTab) : PreviewEvent

    /** Getchu 发售表里点开某部作品的详情 */
    data class OnOpenGetchuDetail(val id: String) : PreviewEvent

    /** Getchu 发售表首屏失败后重试 */
    data object OnRetryGetchu : PreviewEvent

    /** 点击游览列表中的某个项 */
    data class OnSelectTourItem(val index: Int) : PreviewEvent

    /** 切换到上一个月 */
    data class OnPrevMonth(val fromDateCode: String) : PreviewEvent

    /** 切换到下一个月 */
    data class OnNextMonth(val fromDateCode: String) : PreviewEvent

    /** 打开图片查看器 */
    data class OnOpenImage(val index: Int, val imageUrls: List<String>) : PreviewEvent

    /** 关闭图片查看器 */
    data object OnDismissImageViewer : PreviewEvent

    /** 打开视频详情 */
    data class OnOpenVideo(val videoCode: String?) : PreviewEvent

    /** 打开 Getchu 新番预告 */
    data object OnOpenGetchuPreview : PreviewEvent

    /** Open the current month's official preview page in the browser. */
    data object OnOpenWebPreview : PreviewEvent

    /** 打开评论页 */
    data class OnOpenComment(val label: String, val dateCode: String) : PreviewEvent

    /** 重试加载 */
    data object OnRetryLoad : PreviewEvent

    /** 【月度归档】加载下一页 */
    data object OnLoadMoreArchive : PreviewEvent

    /** 【月度归档】首屏加载失败后重试 */
    data object OnRetryArchive : PreviewEvent
}
