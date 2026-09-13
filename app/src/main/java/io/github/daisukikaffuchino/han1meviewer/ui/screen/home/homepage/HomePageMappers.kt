package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage

import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.HomePage

/**
 * 将首页原始数据转换为 UI 可直接展示的分类行数据。
 *
 * ## 三个数据源共用同一份槽位，靠 [genre] / [sort] 这两个「检索标记」分流
 *
 * 每个 `HomeCategory` 都带一个标记（`genre` 或 `sort`），点「更多」时它会被
 * [io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo] 翻译成对应数据源的检索条件。
 * 所以**新增一个栏目必须同时改两处**：
 *
 * 1. 这里给它一个 `titleRes` 与一个标记；
 * 2. 该数据源的 `*Parser.queryForMarker`（如 [io.github.daisukikaffuchino.han1meviewer.logic.ph.PhParser]）
 *    里给这个标记一条映射。
 *
 * 只做第 1 步的话，栏目画得出来、点进去却静默退回默认排序 —— 看着像「点了没反应」。
 *
 * @param homePage 仓库层返回的首页原始数据。
 * @param isAVSite 是否是非 hanime 的「AV 型」站点（nJAV / Pornhub）。
 * @param isPornhubSite 是否走 Pornhub —— 它虽同属 AV 型，但栏目名完全不同，
 *   而且要单独覆盖，所以格外单开一个开关而不是塞进 [isAVSite]。
 * @return 当前站点类型下存在视频内容的分类行列表。
 */
fun buildCategoryList(
    homePage: HomePage,
    isAVSite: Boolean,
    isPornhubSite: Boolean = false,
): List<HomeCategory> {
    return listOfNotNull(
        // ── 最新 ───────────────────────────────────────────────────────────
        HomeCategory(
            key = HOME_CATEGORY_LATEST_HANIME,
            titleRes = when {
                isPornhubSite -> R.string.ph_latest
                isAVSite -> R.string.latest_av
                else -> R.string.latest_hanime
            },
            genre = when {
                isPornhubSite -> "最新"
                isAVSite -> "日本AV"
                else -> "裏番"
            },
            videos = homePage.ecchiAnime
        ),
        // ── 最多觀看（PH）/ 最新上市 ───────────────────────────────────────
        HomeCategory(
            key = HOME_CATEGORY_LATEST_RELEASE,
            titleRes = if (isPornhubSite) R.string.ph_popular else R.string.latest_release,
            sort = if (isPornhubSite) "最多觀看" else "最新上市",
            videos = homePage.latestRelease
        ),
        // ── 本週熱門（PH）/ 最新上傳 ───────────────────────────────────────
        //
        // PH 的这一栏不是「最新上傳」而是「本週熱門」：接口的 `period=weekly`
        // 实测与不带 period 的一页 30 条**零重合**，是真的另一批内容。
        HomeCategory(
            key = HOME_CATEGORY_LATEST_UPLOAD,
            titleRes = if (isPornhubSite) R.string.ph_weekly else R.string.latest_upload,
            sort = if (isPornhubSite) "本週熱門" else "最新上傳",
            videos = homePage.latestHanime
        ),
        // ── 最高評分（PH）/ 他們在看 ───────────────────────────────────────
        HomeCategory(
            key = HOME_CATEGORY_WATCHING_NOW,
            titleRes = if (isPornhubSite) R.string.ph_top_rated else R.string.they_watched,
            sort = if (isPornhubSite) "最高評分" else "他們在看",
            videos = homePage.watchingNow
        ),
        // ── 素人（PH）/ 素人業餘（nJAV）/ 泡麵番 ──────────────────────────
        HomeCategory(
            key = HOME_CATEGORY_SHORT_EPISODE,
            titleRes = when {
                isPornhubSite -> R.string.ph_amateur
                isAVSite -> R.string.amateur_nomask
                else -> R.string.category_instant_noodle
            },
            genre = when {
                isPornhubSite -> "素人"
                isAVSite -> "素人業餘"
                else -> "泡麵番"
            },
            sort = "最新上傳",
            videos = homePage.shortEpisodeAnime
        ),
        // ── 日本（PH）/ 高清無碼（nJAV）/ Motion Anime ────────────────────
        HomeCategory(
            key = HOME_CATEGORY_MOTION_ANIME,
            titleRes = when {
                isPornhubSite -> R.string.ph_japanese
                isAVSite -> R.string.hd_uncensored
                else -> R.string.category_motion_anime
            },
            genre = when {
                isPornhubSite -> "日本"
                isAVSite -> "高清無碼"
                else -> "Motion Anime"
            },
            sort = "最新上傳",
            videos = homePage.motionAnime
        ),
        // ── 動漫（PH）/ AI解碼（nJAV）/ 3DCG ─────────────────────────────
        HomeCategory(
            key = HOME_CATEGORY_3D_CG,
            titleRes = when {
                isPornhubSite -> R.string.ph_hentai
                isAVSite -> R.string.ai_decensored
                else -> R.string.category_3d_animation
            },
            genre = when {
                isPornhubSite -> "動漫"
                isAVSite -> "AI解碼"
                else -> "3DCG"
            },
            sort = "最新上傳",
            videos = homePage.threeDCG
        ),
        // ── 華語（PH）/ 國產AV（nJAV）/ 2.5D ─────────────────────────────
        HomeCategory(
            key = HOME_CATEGORY_2_5D,
            titleRes = when {
                isPornhubSite -> R.string.ph_chinese
                isAVSite -> R.string.china_av
                else -> R.string.animation_2_5d
            },
            genre = when {
                isPornhubSite -> "華語"
                isAVSite -> "國產AV"
                else -> "2.5D"
            },
            sort = "最新上傳",
            videos = homePage.twoPointFiveDAnime
        ),
        // ── Cosplay（PH）/ 國產素人（nJAV）/ 2D動畫 ───────────────────────
        HomeCategory(
            key = HOME_CATEGORY_2D_ANIME,
            titleRes = when {
                isPornhubSite -> R.string.category_cosplay
                isAVSite -> R.string.chinese_amateur
                else -> R.string.animation_2d
            },
            genre = when {
                isPornhubSite -> "Cosplay"
                isAVSite -> "國產素人"
                else -> "2D動畫"
            },
            sort = "最新上傳",
            videos = homePage.twoDAnime
        ),
        // ── AI 生成 / 中文字幕（PH 不用这一栏）─────────────────────────────
        HomeCategory(
            key = HOME_CATEGORY_AI_GENERATED,
            titleRes = if (isAVSite) R.string.chinese_subtitle else R.string.ai_generated,
            genre = if (isAVSite) null else "AI生成",
            tags = if (isAVSite) "中文字幕" else null,
            sort = "最新上傳",
            videos = homePage.aiGenerated
        ),
        // ── 官方獨家（PH）/ 本日排行（nJAV）/ MMD ─────────────────────────
        HomeCategory(
            key = HOME_CATEGORY_MMD,
            titleRes = when {
                isPornhubSite -> R.string.ph_exclusive
                isAVSite -> R.string.ranking_today
                else -> R.string.mmd
            },
            genre = when {
                isPornhubSite -> "官方獨家"
                isAVSite -> null
                else -> "MMD"
            },
            sort = if (isAVSite && !isPornhubSite) "本日排行" else "最新上傳",
            videos = homePage.mmd
        ),
        // ── 本月排行（nJAV）/ Cosplay（hanime）—— PH 不用这一栏 ───────────
        HomeCategory(
            key = HOME_CATEGORY_COSPLAY,
            titleRes = if (isAVSite) R.string.ranking_this_month else R.string.category_cosplay,
            genre = if (isAVSite) null else "Cosplay",
            sort = if (isAVSite) "本月排行" else "最新上傳",
            videos = homePage.cosplay
        )
    ).filter { it.videos.isNotEmpty() }
        .let { categories ->
            val hiddenKeys = hiddenHomeCategoryKeys
            val orderIndex = homeCategoryOrder.withIndex().associate { it.value to it.index }
            categories
                .filterNot { it.key in hiddenKeys }
                .sortedBy { orderIndex[it.key] ?: Int.MAX_VALUE }
        }
}
