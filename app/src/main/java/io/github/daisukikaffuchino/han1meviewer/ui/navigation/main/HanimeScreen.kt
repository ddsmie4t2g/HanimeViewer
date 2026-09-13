package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

interface HanimeScreen : NavKey

@Serializable
object HomeRoute : HanimeScreen

@Serializable
object WatchHistoryRoute : HanimeScreen

@Serializable
object MyFavVideoRoute : HanimeScreen

@Serializable
object MyWatchLaterRoute : HanimeScreen

@Serializable
object MyPlaylistRoute : HanimeScreen

@Serializable
object SubscriptionRoute : HanimeScreen

@Serializable
object DailyCheckInRoute : HanimeScreen

@Serializable
object DownloadRoute : HanimeScreen

@Serializable
object AccountRoute : HanimeScreen

/**
 * **自建账号**（26.7.0）——与 [AccountRoute]（hanime 站点账号）是两件事：
 * 这一个的数据存在用户自己的服务器上，见
 * [io.github.daisukikaffuchino.han1meviewer.logic.account.AccountRepository]。
 */
@Serializable
object MyAccountRoute : HanimeScreen

@Serializable
object LoginRoute : HanimeScreen

@Serializable
object ManualCookiesRoute : HanimeScreen

@Serializable
data class CloudflareRoute(
    val url: String,
    val host: String,
) : HanimeScreen

@Serializable
data class AvatarCropRoute(
    val sourceUri: String,
) : HanimeScreen

@Serializable
data class SearchRoute(
    val query: String? = null,
    val advancedSearchJson: String? = null,
) : HanimeScreen

/**
 * 作者页（26.6.3 新增）。
 *
 * 参数与 [SearchRoute.advancedSearchJson] 同一套路：把
 * [io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef] 整份 JSON 塞进来。
 * 之所以不在路由里摊平成一个个字段：作者资料的字段会随站点增加（作品数、关注者数、
 * 之后的简介…），摊平后每加一个字段都要动路由签名，而 JSON 只要给默认值就兼容。
 */
@Serializable
data class ArtistRoute(
    val artistJson: String,
) : HanimeScreen

@Serializable
object PreviewRoute : HanimeScreen

@Serializable
object GetchuPreviewRoute : HanimeScreen

@Serializable
data class GetchuPreviewDetailRoute(
    val id: String,
) : HanimeScreen

@Serializable
data class PreviewCommentRoute(
    val date: String,
    val dateCode: String,
) : HanimeScreen

@Serializable
data class VideoRoute(
    val videoCode: String,
    val localUri: String? = null,
) : HanimeScreen
