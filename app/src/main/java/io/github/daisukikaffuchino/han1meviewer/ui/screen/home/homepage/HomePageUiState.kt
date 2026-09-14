package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage

import io.github.daisukikaffuchino.han1meviewer.logic.model.Announcement

/**
 * 主页 UI 事件集合，用于在主界面（Home）中处理用户交互行为，功能如函数名所写。
 *
 */

sealed interface HomeUiEvent {
    data object OpenDrawer : HomeUiEvent
    data object NavigateToPreview : HomeUiEvent

    /**
     * 点了右上角的「浏览」（26.8.3）—— 女优一览 / 女优排行。
     *
     * 只在 nJAV 数据源下会出现这个按钮（见 `HomeTopBarAction`）。它以前挂在抽屉里，
     * 但抽屉第 6 项往下要滚动才看得见，而它是 nJAV 的主浏览入口。
     */
    data object NavigateToActressGallery : HomeUiEvent

    data class OpenSearchPage(val query: String = "") : HomeUiEvent
    data class NavigateToSearchAdvanced(val params: Map<String, String>) : HomeUiEvent
    data class OpenVideo(val videoCode: String) : HomeUiEvent
    data class LongPressVideoCopy(val videoCode: String, val videoTitle: String) : HomeUiEvent
    data object ShowExitDialog : HomeUiEvent
    data class ShowAnnouncementDialog(val announcement: Announcement) : HomeUiEvent
    /**
     * 用户点了「立即更新 / 立即安装」。
     *
     * 以前叫 `OpenUpdatePage` —— 那时真的只是把下载页丢给浏览器；
     * 现在改成应用内下载 + 拉起安装器，由 [HomeRouteScreen] 按当前下载状态决定是
     * 「开始下载」还是「安装已下好的包」。
     */
    data class UpdateAction(val downloadUrl: String, val versionCode: Int) : HomeUiEvent
    data class IgnoreUpdate(val versionCode: Int) : HomeUiEvent
}
