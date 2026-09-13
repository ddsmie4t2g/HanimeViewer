package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.subscription

import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.logic.model.SubscriptionItem
import io.github.daisukikaffuchino.han1meviewer.logic.model.SubscriptionVideosItem

/**
 * 订阅页面 UI 状态。
 *
 * ## 26.6.3 起这里装的是**两段**互不相干的东西
 *
 * | 字段 | 来源 | 要不要登录 |
 * |---|---|---|
 * | [followed] | 本机 [io.github.daisukikaffuchino.han1meviewer.logic.FollowedArtistStore] | **不要**（三个站点通用） |
 * | [artists] / [videos] | hanime 服务端订阅 | 要 |
 *
 * 分开的理由是用户的原话：「自带的订阅又必须要登录才能用……最好登录不跟网站挂钩」。
 * 关注是「我记住这个人」这件纯本地的事，没有理由被任何一个站点的登录挡住；
 * 而服务端订阅本来就只存在于 hanime，登录之后才有意义。
 *
 * @param followed 本机关注的作者（三站通用，免登录）
 * @param artists hanime 服务端订阅的作者（未登录时恒为空）
 * @param videos hanime 服务端订阅的视频流
 * @param isLoggedIn 当前是否已登录 hanime —— 决定要不要请求 / 画服务端那一段
 * @param isRefreshing 是否正在下拉刷新
 * @param canLoadMore 是否可加载更多
 * @param error 错误信息，null 表示无错误
 * @param showCached 是否只展示缓存数据（Loading/Error 时保留旧数据）
 */
data class SubscriptionUiState(
    val followed: List<ArtistRef> = emptyList(),
    val artists: List<SubscriptionItem> = emptyList(),
    val videos: List<SubscriptionVideosItem> = emptyList(),
    val isLoggedIn: Boolean = false,
    val isRefreshing: Boolean = false,
    val canLoadMore: Boolean = false,
    val currentPage: Int = 1,
    val error: Throwable? = null,
    val showCached: Boolean = false,
)

/**
 * 订阅页面用户交互事件。
 */
sealed interface SubscriptionEvent {
    /** 点击「关注的作者」——带整份身份，好让界面决定进作者页还是搜索 */
    data class OnClickFollowed(val artist: ArtistRef) : SubscriptionEvent

    /** 长按「关注的作者」 */
    data class OnLongClickFollowed(val artist: ArtistRef) : SubscriptionEvent

    /** 点击 hanime 服务端订阅的作者（只能按名字搜索，站点没有作者页） */
    data class OnClickArtist(val artistName: String) : SubscriptionEvent

    /** 长按 hanime 服务端订阅的作者 */
    data class OnLongClickArtist(val artistName: String) : SubscriptionEvent

    /** 点击视频 */
    data class OnClickVideo(val videoCode: String) : SubscriptionEvent

    /** 长按视频 */
    data class OnLongClickVideo(val videoCode: String, val title: String) : SubscriptionEvent

    /** 下拉刷新 */
    data object OnRefresh : SubscriptionEvent

    /** 加载更多 */
    data object OnLoadMore : SubscriptionEvent

    /** 返回 */
    data object OnBack : SubscriptionEvent
}
