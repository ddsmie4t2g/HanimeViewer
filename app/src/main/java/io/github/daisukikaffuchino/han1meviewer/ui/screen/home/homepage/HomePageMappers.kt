package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage

import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.HomePage
import io.github.daisukikaffuchino.han1meviewer.logic.ph.PhParser

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
 * ⚠️ **例外：Pornhub 的「推荐」行**（[HOME_CATEGORY_RECOMMENDED]）。它不是检索条件，
 * 而是另一个页面（`/recommended`），所以它的标记走
 * [io.github.daisukikaffuchino.han1meviewer.logic.ph.PhParser.isRecommendedMarker]，
 * 而不是 `queryForMarker`。新增这类「非检索」栏目时照着它抄。
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
    /**
     * 是否走 nJAV。
     *
     * ⭐ 26.8 新增：nJAV 的首页栏目改成**站点真实导航里的名字**（中文字幕 / 最近更新 /
     * 新作上市 / 无码流出 / 今日热门 / 本週热门 / 本月热门 / VR），
     * 不再借用 hanime 那套「最新AV / 他們在看 / 高清無碼」的措辞 ——
     * 用户的原话是「显示的我也不是很满意，要扎根于实际网页」。
     *
     * 标记（genre/sort）直接用真实路径，见 [io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavParser.pathForMarker]。
     */
    isNjavSite: Boolean = false,
): List<HomeCategory> {
    return listOfNotNull(
        // ── 站点自己的「推荐」（26.9.5，只有 Pornhub 有内容）─────────────────
        //
        // 内容是 `/recommended` 页（整页 HTML，见 PhNetwork.recommendedUrl），
        // 不是检索接口换参数 —— 所以它的标记不给 queryForMarker，而是给
        // PhParser.isRecommendedMarker 认（NetworkRepo.resolvePhListUrl 先问那个）。
        //
        // ⚠️ **必须卡 `isPornhubSite`**：借用的槽位 `newAnimeTrailer` 在 hanime 那边
        //    装的是「本月新番预告」的真实数据（见 `Parser.homePageVer2`），
        //    不卡的话 hanime 首页会凭空多出一行叫「推荐」、内容却是新番预告。
        //
        // ⭐ 26.9.6：形态改成 **CAROUSEL**（一屏一张大图、左右滑），其他行仍是 ROW。
        //    只改「怎么画」，取数一个字没动。
        if (!isPornhubSite) null else HomeCategory(
            key = HOME_CATEGORY_RECOMMENDED,
            titleRes = R.string.ph_recommended,
            genre = PhParser.RECOMMENDED_MARKER,
            videos = homePage.newAnimeTrailer,
            style = HomeCategoryStyle.CAROUSEL
        ),
        // ── 最新 ───────────────────────────────────────────────────────────
        HomeCategory(
            key = HOME_CATEGORY_LATEST_HANIME,
            titleRes = when {
                isPornhubSite -> R.string.ph_latest
                isNjavSite -> R.string.njav_sec_new
                isAVSite -> R.string.latest_av
                else -> R.string.latest_hanime
            },
            genre = when {
                isPornhubSite -> "最新"
                isNjavSite -> "new"
                isAVSite -> "日本AV"
                else -> "裏番"
            },
            videos = homePage.ecchiAnime
        ),
        // ── 最多觀看（PH）/ 最新上市 ───────────────────────────────────────
        HomeCategory(
            key = HOME_CATEGORY_LATEST_RELEASE,
            titleRes = when {
                isPornhubSite -> R.string.ph_popular
                isNjavSite -> R.string.njav_sec_release
                else -> R.string.latest_release
            },
            sort = when {
                isPornhubSite -> "最多觀看"
                isNjavSite -> "release"
                else -> "最新上市"
            },
            videos = homePage.latestRelease
        ),
        // ── 本週熱門（PH）/ 最新上傳 ───────────────────────────────────────
        //
        // PH 的这一栏不是「最新上傳」而是「本週熱門」：接口的 `period=weekly`
        // 实测与不带 period 的一页 30 条**零重合**，是真的另一批内容。
        HomeCategory(
            key = HOME_CATEGORY_LATEST_UPLOAD,
            titleRes = when {
                isPornhubSite -> R.string.ph_weekly
                isNjavSite -> R.string.njav_sec_weekly
                else -> R.string.latest_upload
            },
            sort = when {
                isPornhubSite -> "本週熱門"
                isNjavSite -> "weekly-hot"
                else -> "最新上傳"
            },
            videos = homePage.latestHanime
        ),
        // ── 最高評分（PH）/ 他們在看 ───────────────────────────────────────
        HomeCategory(
            key = HOME_CATEGORY_WATCHING_NOW,
            titleRes = when {
                isPornhubSite -> R.string.ph_top_rated
                isNjavSite -> R.string.njav_sec_today
                else -> R.string.they_watched
            },
            sort = when {
                isPornhubSite -> "最高評分"
                isNjavSite -> "today-hot"
                else -> "他們在看"
            },
            videos = homePage.watchingNow
        ),
        // ── 素人（PH）/ 素人業餘（nJAV）/ 泡麵番 ──────────────────────────
        HomeCategory(
            key = HOME_CATEGORY_SHORT_EPISODE,
            titleRes = when {
                isPornhubSite -> R.string.ph_amateur
                isNjavSite -> R.string.njav_sec_vr
                isAVSite -> R.string.amateur_nomask
                else -> R.string.category_instant_noodle
            },
            genre = when {
                isPornhubSite -> "素人"
                isNjavSite -> "genres/VR"
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
                isNjavSite -> R.string.njav_sec_uncensored
                isAVSite -> R.string.hd_uncensored
                else -> R.string.category_motion_anime
            },
            genre = when {
                isPornhubSite -> "日本"
                isNjavSite -> "uncensored-leak"
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
            titleRes = when {
                isNjavSite -> R.string.njav_sec_chinese_subtitle
                isAVSite -> R.string.chinese_subtitle
                else -> R.string.ai_generated
            },
            genre = if (isAVSite) null else "AI生成",
            tags = if (isNjavSite) "chinese-subtitle" else if (isAVSite) "中文字幕" else null,
            sort = "最新上傳",
            videos = homePage.aiGenerated
        ),
        // ── 官方獨家（PH）/ 本日排行（nJAV）/ MMD ─────────────────────────
        HomeCategory(
            key = HOME_CATEGORY_MMD,
            titleRes = when {
                isPornhubSite -> R.string.ph_exclusive
                isNjavSite -> R.string.njav_sec_monthly
                isAVSite -> R.string.ranking_today
                else -> R.string.mmd
            },
            genre = when {
                isPornhubSite -> "官方獨家"
                isAVSite -> null
                else -> "MMD"
            },
            sort = when {
                isNjavSite -> "monthly-hot"
                isAVSite && !isPornhubSite -> "本日排行"
                else -> "最新上傳"
            },
            videos = homePage.mmd
        ),
        // ── 本月排行（nJAV）/ Cosplay（hanime）—— PH 不用这一栏 ───────────
        HomeCategory(
            key = HOME_CATEGORY_COSPLAY,
            titleRes = if (isAVSite) R.string.ranking_this_month else R.string.category_cosplay,
            genre = if (isAVSite) null else "Cosplay",
            sort = if (isAVSite) "本月排行" else "最新上傳",
            // nJAV 的这一栏已经用不到（本月热门走 MMD 槽）——parser 不再填 cosplay，
            // 这里保持原样即可：videos 为空时它会被结尾的 filter 丢掉。
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
