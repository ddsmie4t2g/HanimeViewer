package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage

import androidx.annotation.StringRes
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository

const val HOME_CATEGORY_LATEST_HANIME = "latest_hanime"
const val HOME_CATEGORY_LATEST_RELEASE = "latest_release"
const val HOME_CATEGORY_LATEST_UPLOAD = "latest_upload"
const val HOME_CATEGORY_WATCHING_NOW = "watching_now"
const val HOME_CATEGORY_SHORT_EPISODE = "short_episode"
const val HOME_CATEGORY_MOTION_ANIME = "motion_anime"
const val HOME_CATEGORY_3D_CG = "3d_cg"
const val HOME_CATEGORY_2_5D = "2_5d"
const val HOME_CATEGORY_2D_ANIME = "2d_anime"
const val HOME_CATEGORY_AI_GENERATED = "ai_generated"
const val HOME_CATEGORY_MMD = "mmd"
const val HOME_CATEGORY_COSPLAY = "cosplay"

/**
 * 站点自己的「推荐」行（26.9.5 新增）—— **目前只有 Pornhub 有内容**
 * （`/recommended` 页，见 [io.github.daisukikaffuchino.han1meviewer.logic.ph.PhNetwork.recommendedUrl]）。
 *
 * hanime / nJAV 下这一行的视频列表恒为空，会在 `buildCategoryList` 结尾
 * 被「空行不画」的过滤丢掉；设置里那一项也按数据源隐藏
 * （见 `HomeSettingsRouteScreen`），免得这边留着一条永远没内容的开关。
 */
const val HOME_CATEGORY_RECOMMENDED = "recommended"

data class HomeCategoryPreferenceItem(
    val key: String,
    @param:StringRes val normalTitleRes: Int,
    @param:StringRes val avTitleRes: Int? = null,
)

/**
 * 设置页里「首页栏目」的**全部**条目，同时决定新用户的默认顺序。
 *
 * ⚠️ 「推荐」放在最前面是刻意的：它是站点推荐引擎的输出，比
 * 「最新 / 最多观看 / 本周热门」这类机械排序更值得先看到 —— 用户原话是
 * 「现在只有最新、最多观看、本周热门这些都不带变的，加点它自己的首页推荐」。
 * 老用户如果自己排过顺序，顺序按他保存的走（新键会被 `normalizeHomeCategoryKeys`
 * 追加到末尾），**不覆盖用户的排序**。
 */
val defaultHomeCategoryPreferenceItems = listOf(
    HomeCategoryPreferenceItem(HOME_CATEGORY_RECOMMENDED, R.string.ph_recommended),
    HomeCategoryPreferenceItem(HOME_CATEGORY_LATEST_HANIME, R.string.latest_hanime, R.string.latest_av),
    HomeCategoryPreferenceItem(HOME_CATEGORY_LATEST_RELEASE, R.string.latest_release),
    HomeCategoryPreferenceItem(HOME_CATEGORY_LATEST_UPLOAD, R.string.latest_upload),
    HomeCategoryPreferenceItem(HOME_CATEGORY_WATCHING_NOW, R.string.they_watched),
    HomeCategoryPreferenceItem(HOME_CATEGORY_SHORT_EPISODE, R.string.category_instant_noodle, R.string.amateur_nomask),
    HomeCategoryPreferenceItem(HOME_CATEGORY_MOTION_ANIME, R.string.category_motion_anime, R.string.hd_uncensored),
    HomeCategoryPreferenceItem(HOME_CATEGORY_3D_CG, R.string.category_3d_animation, R.string.ai_decensored),
    HomeCategoryPreferenceItem(HOME_CATEGORY_2_5D, R.string.animation_2_5d, R.string.china_av),
    HomeCategoryPreferenceItem(HOME_CATEGORY_2D_ANIME, R.string.animation_2d, R.string.chinese_amateur),
    HomeCategoryPreferenceItem(HOME_CATEGORY_AI_GENERATED, R.string.ai_generated, R.string.chinese_subtitle),
    HomeCategoryPreferenceItem(HOME_CATEGORY_MMD, R.string.mmd, R.string.ranking_today),
    HomeCategoryPreferenceItem(HOME_CATEGORY_COSPLAY, R.string.category_cosplay, R.string.ranking_this_month),
)

val defaultHomeCategoryOrder: List<String>
    get() = defaultHomeCategoryPreferenceItems.map { it.key }

val homeCategoryOrder: List<String>
    get() = normalizeHomeCategoryKeys(SettingsRepository.current.homeCategoryOrder)

val hiddenHomeCategoryKeys: Set<String>
    get() = SettingsRepository.current.hiddenHomeCategoryKeys

suspend fun saveHomeCategoryPreferences(order: List<String>, hiddenKeys: Set<String>) =
    SettingsRepository.setHomeCategories(
        normalizeHomeCategoryKeys(order),
        hiddenKeys.filterTo(linkedSetOf()) { it in defaultHomeCategoryOrder },
    )

private fun normalizeHomeCategoryKeys(keys: List<String>): List<String> {
    val defaults = defaultHomeCategoryOrder
    return keys.distinct().filter { it in defaults } + defaults.filterNot { it in keys }
}
