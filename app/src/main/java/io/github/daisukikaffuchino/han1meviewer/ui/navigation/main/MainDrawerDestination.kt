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
     * **我的账号**（自建，26.7.0）：数据存在用户自己的服务器上，**三个站点共用**。
     * 抽草稿里它叫「自建账号」，界面上统一叫「我的账号」。
     */
    MyAccount(
        route = MyAccountRoute,
        iconRes = R.drawable.ic_person,
        titleRes = R.string.account_title,
    ),
    /**
     * **hanime 站点账号**（原「我的账号」页）：只管 hanime 的订阅 / 清单 / 评论。
     * ⚠️ 它以前叫「我的账号」，与自建账号撞名，用户会以为「登录了 hanime 就等于登录了全部」——
     * 26.7.1 起明确标成「hanime 站点账号」，并把自建账号放在它前面。
     */
    SiteAccount(
        route = AccountRoute,
        iconRes = R.drawable.ic_admin_panel_settings,
        titleRes = R.string.site_account_hanime,
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
    /**
     * **收藏夹**（9.0 新增）。
     *
     * 与 [Playlist] 分开成两个入口（用户明确要求「新建独立收藏夹」）：
     * 播放清单是「连着看完的一串」，收藏夹是「按主题收起来的最爱」，
     * 混在一起时两边都得靠标题去猜。
     */
    Favorites(
        route = MyFavoritesRoute,
        iconRes = R.drawable.ic_book,
        titleRes = R.string.favorite_collections,
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
