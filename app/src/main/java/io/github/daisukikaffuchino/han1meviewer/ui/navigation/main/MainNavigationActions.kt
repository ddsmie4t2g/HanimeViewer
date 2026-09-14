package io.github.daisukikaffuchino.han1meviewer.ui.navigation.main

import android.content.Intent
import kotlinx.serialization.json.Json

/**
 * 需要登录才能进的抽屉项。
 *
 * ⭐ 26.7.1 起**移除了「订阅」**：那一页现在未登录也能用（作者按 hanime / Pornhub / nJAV
 * 分成三块，hanime 那块未登录时读本机同步副本）。把它拦在登录页前面，等于继续暗示
 * 「这些功能属于 hanime」—— 而用户要的恰恰是「关注是我自己的数据，不该被某个站点的登录挡住」。
 *
 * ⭐ 26.7.3 起把 **hanime 站点账号**加进来：那一页（`AccountRoute`）的内容是
 * 「hanime 账号资料 / 改密 / 订阅清单」这类服务端数据，**没有登录时整页无从加载** ——
 * 原来的表现是打开后直接看到一句「加载失败，请重试」（`NotLoggedInException` 也被
 * 当成加载失败），而用户点它的本意是「去登录 hanime」。现在直接把他带去登录页。
 */
private val loginRequiredDrawerItems = setOf<MainDrawerDestination>(
    MainDrawerDestination.SiteAccount,
)

const val EXTRA_OPEN_DAILY_CHECK_IN = "openDailyCheckIn"
const val ACTION_OPEN_CLOUDFLARE_VERIFICATION =
    "io.github.daisukikaffuchino.han1meviewer.action.OPEN_CLOUDFLARE_VERIFICATION"
const val EXTRA_CLOUDFLARE_URL = "cloudflare_url"
const val EXTRA_CLOUDFLARE_HOST = "cloudflare_host"

fun TopLevelBackStack<HanimeScreen>.navigateDrawerDestination(
    destination: MainDrawerDestination,
    isLoggedIn: Boolean,
    onRequireLogin: () -> Unit,
): Boolean {
    if (destination in loginRequiredDrawerItems && !isLoggedIn) {
        onRequireLogin()
        return false
    }

    addTopLevel(destination.route)
    return true
}

fun TopLevelBackStack<HanimeScreen>.handleMainIntent(intent: Intent) {
    if (intent.action == ACTION_OPEN_CLOUDFLARE_VERIFICATION) {
        val url = intent.getStringExtra(EXTRA_CLOUDFLARE_URL)
        val host = intent.getStringExtra(EXTRA_CLOUDFLARE_HOST)
        intent.removeExtra(EXTRA_CLOUDFLARE_URL)
        intent.removeExtra(EXTRA_CLOUDFLARE_HOST)
        intent.action = null
        if (!url.isNullOrBlank() && !host.isNullOrBlank()) {
            add(CloudflareRoute(url = url, host = host), launchSingleTop = true)
        }
        return
    }

    intent.data?.let { uri ->
        when (uri.scheme) {
            "http", "https" -> {
                val videoCode = uri.getQueryParameter("v")
                if (videoCode != null) {
                    add(VideoRoute(videoCode))
                    return
                }
            }

            "file", "content" -> {
                add(VideoRoute("-1", uri.toString()))
                return
            }
        }
    }

    if (intent.getBooleanExtra(EXTRA_OPEN_DAILY_CHECK_IN, false)) {
        intent.removeExtra(EXTRA_OPEN_DAILY_CHECK_IN)
        add(DailyCheckInRoute, launchSingleTop = true)
        return
    }

    intent.getStringExtra("startSearchFromTag")?.let { tag ->
        intent.removeExtra("startSearchFromTag")
        add(SearchRoute(query = tag))
        return
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    val map = intent.getSerializableExtra("startSearchFromMap") as? HashMap<String, String>
    if (map != null) {
        intent.removeExtra("startSearchFromMap")
        add(SearchRoute(advancedSearchJson = Json.encodeToString(map)))
        return
    }

    val videoCode = intent.getStringExtra("startVideoCode")
    if (!videoCode.isNullOrEmpty()) {
        intent.removeExtra("startVideoCode")
        add(VideoRoute(videoCode))
    }
}
