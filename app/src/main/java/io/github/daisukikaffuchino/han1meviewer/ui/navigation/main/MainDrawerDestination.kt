package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.settings.HomeSettingsRoute

enum class MainDrawerDestination(
    val route: HanimeScreen,
    @param:DrawableRes val iconRes: Int,
    @param:StringRes val titleRes: Int,
) {
    Home(
        route = HomeRoute,
        iconRes = R.drawable.ic_home,
        titleRes = R.string.home_page,
    ),
    Settings(
        route = HomeSettingsRoute,
        iconRes = R.drawable.ic_settings,
        titleRes = R.string.settings,
    ),
    /**
     * **自建账号**：数据存在用户自己的服务器上，与任何站点无关。
     * 刻意放在「设置」旁边而不是混进站点账号那一套里 —— 两者是不同的东西。
     */
    MyAccount(
        route = MyAccountRoute,
        iconRes = R.drawable.ic_person,
        titleRes = R.string.account_title,
    ),
    DailyCheckIn(
        route = DailyCheckInRoute,
        iconRes = R.drawable.ic_thumb_up_off_alt,
        titleRes = R.string.check_in_feature_name,
    ),
    WatchLater(
        route = MyWatchLaterRoute,
        iconRes = R.drawable.ic_access_time,
        titleRes = R.string.watch_later,
    ),
    FavVideo(
        route = MyFavVideoRoute,
        iconRes = R.drawable.ic_favorite_border,
        titleRes = R.string.fav_video,
    ),
    Playlist(
        route = MyPlaylistRoute,
        iconRes = R.drawable.ic_format_list_bulleted,
        titleRes = R.string.play_list,
    ),
    Subscription(
        route = SubscriptionRoute,
        iconRes = R.drawable.ic_subscribtion,
        // 这一页 26.6.3 起装的是两段：本机关注的作者（三站通用、免登录）+ hanime 服务端订阅。
        // 只叫「我的订阅」会让人以为关注的东西不在这儿 —— 用户报的正是这一点。
        titleRes = R.string.follow_and_subscribe,
    ),
    WatchHistory(
        route = WatchHistoryRoute,
        iconRes = R.drawable.ic_history,
        titleRes = R.string.watch_history,
    ),
    Download(
        route = DownloadRoute,
        iconRes = R.drawable.ic_download,
        titleRes = R.string.download,
    );

    companion object {
        fun fromRoute(route: HanimeScreen?): MainDrawerDestination? =
            entries.firstOrNull { it.route == route }
    }
}
