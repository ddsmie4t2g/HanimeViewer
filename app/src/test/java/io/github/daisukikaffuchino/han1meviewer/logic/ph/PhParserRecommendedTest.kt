package io.github.daisukikaffuchino.han1meviewer.logic.ph

import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pornhub「推荐」页（`/recommended`）解析的回归测试 —— 26.9.5 新增。
 *
 * 夹具是 **2026-09-15 从线上抓下来的真实标记**（`build/ph-probe-recommended-desktop.html`），
 * 只做了删减（去掉无关属性），没有改写结构。留档的意义在于这套选择器**必须**同时满足
 * 两个条件，而它们恰好互相矛盾：
 *
 * 1. 认得出 `ul#recommendedListings` 里的卡片；
 * 2. **别把旁边两个区块的卡片也吃进来** —— 同一页上
 *    `recommendedPornstarsWrapper` / `recommendedCategoriesWrapper` 里也有一堆
 *    `li.pcVideoListItem`，全局取就会串页（作者页栽过同一个跟头）。
 */
class PhParserRecommendedTest {

    /** 真实卡片（vkey / 标题 / 时长 / 观看数都照抄线上），只删了无关属性与内联样式。 */
    private fun card(
        vkey: String,
        title: String,
        duration: String,
        views: String,
        imageId: String,
    ) = """
        <li class="pcVideoListItem js-pop videoblock " id="v$imageId"
            data-video-id="$imageId" data-type="video" data-video-vkey="$vkey"
            data-segment="straight" tabindex="0" data-entrycode="VidPg-premVid">
            <div class="wrap flexibleHeight">
                <div class="phimage">
                    <div class="preloadLine"></div>
                    <a href="/view_video.php?viewkey=$vkey" title="$title"
                       class="latestThumb fade videoPreviewBg linkVideoThumb js-linkVideoThumb img js-viewTrack">
                        <img src="https://ei.phncdn.com/videos/202412/23/$imageId/original/(m=q3XO02ZbecuKGgaaaa)(mh=OktSqqTb8cud6rw4)0.jpg"
                             alt="$title" loading="lazy" width="320" height="180" title="$title" />
                        <div class="marker-overlays js-noFade">
                            <var class="bgShadeEffect duration tooltipTrig" data-title="Video Duration">$duration</var>
                        </div>
                    </a>
                </div>
                <div class="thumbnail-info-wrapper clearfix">
                    <div class="videoUploaderBlock">
                        <div class="usernameWrapper"><div class="usernameWrap">
                            <a href="/channels/familyxxx" class="bolded ">FAMILYxxx</a>
                        </div></div>
                        <div class="videoDetailBlock">
                            <span class="views"><i class="ph-icon-view-on tooltipTrig" data-title="Total Views"></i><var>$views</var></span>
                            <var class="added">1 year ago</var>
                        </div>
                    </div>
                    <div class="vidTitleWrapper">
                        <span class="title">
                            <a href="/view_video.php?viewkey=$vkey" title="$title"
                               class="thumbnailTitle js-viewTrack">$title</a>
                        </span>
                    </div>
                </div>
            </div>
        </li>
    """.trimIndent()

    private val card1 = card(
        vkey = "6769999af3dbc",
        title = "FAMILY XXX - Big Black Stepdad Jax Slayher Gets Freaky With Horny Step Daughter Lily Starfire",
        duration = "39:24",
        views = "11.1M",
        imageId = "462256431",
    )

    private val card2 = card(
        vkey = "670401306475e",
        title = "FAMILY XXX - Nympho Stepsis Scarlit Scandal Wants Bro To Teach Her",
        duration = "41:29",
        views = "6M",
        imageId = "460123456",
    )

    private val card3 = card(
        vkey = "66cce9dbc3a64",
        title = "HOTWIFE XXX - Beautiful Ebony Hotwife Nia Nacci Loves Fucking BBC",
        duration = "29:00",
        views = "2.3M",
        imageId = "459000111",
    )

    /** 隔壁「Recommended Pornstars」区块里的卡片 —— **不能被当成作品收进来**。 */
    private val neighbourSection = """
        <div class="sectionWrapper recommendedPornstarsWrapper">
            <h1>Recommended Pornstars</h1>
            <ul class="full-row-thumbs display-grid col-4">
                ${card("decoy00000001", "Decoy Pornstar Card", "10:00", "1.2K", "999000001")}
            </ul>
        </div>
    """.trimIndent()

    private val listing = """
        <div class="latestThumbDesign recommendedVideosContainer">
            <ul class="full-row-thumbs display-grid col-3-sm col-4 videos recommendedContainerLoseOne"
                id="recommendedListings">
                <li class="sniperModeEngaged"><div class="w a"><div class="c"></div></div></li>
                $card1
                $card2
                $card3
            </ul>
        </div>
    """.trimIndent()

    /** 页码条 —— 与线上一致（当前页是 `li.page_current > span`，其余是 `li.page_number > a`）。 */
    private val pagination = """
        <div class="pagination3 paginationGated">
            <ul class="firstPage">
                <li class="page_previous disabled"><a class="orangeButton" href=""><b>Prev</b></a></li>
                <li class="page_current"><span class="greyButton">1</span></li>
                <li class="page_number"><a class="greyButton" href="/recommended?page=2">2</a></li>
                <li class="page_number"><a class="greyButton" href="/recommended?page=3">3</a></li>
                <li class="page_number"><a class="greyButton" href="/recommended?page=10">10</a></li>
            </ul>
        </div>
    """.trimIndent()

    private fun page(listingHtml: String = listing, extra: String = neighbourSection) =
        "<html><head><title>Recommended Porn Videos</title></head><body>$extra$listingHtml$pagination</body></html>"

    // ────────────────────────────────────────── /recommended 的卡片解析

    @Test
    fun recommendedListParsesEveryCardInItsOwnContainer() {
        val videos = PhParser.recommendedList(page())

        assertEquals(3, videos.size)
        assertEquals(
            listOf("6769999af3dbc", "670401306475e", "66cce9dbc3a64"),
            videos.map { it.videoCode },
        )

        val first = videos.first()
        assertEquals(
            "FAMILY XXX - Big Black Stepdad Jax Slayher Gets Freaky With Horny Step Daughter Lily Starfire",
            first.title,
        )
        assertEquals("39:24", first.duration)
        assertEquals("11.1M", first.views)
        assertTrue(first.coverUrl.startsWith("https://ei.phncdn.com/videos/"))
    }

    @Test
    fun recommendedListIgnoresCardsFromNeighbouringSections() {
        val codes = PhParser.recommendedList(page()).map { it.videoCode }

        assertFalse(
            "隔壁推荐区块的卡片混进来了（取容器时必须限定 #recommendedListings）",
            codes.contains("decoy00000001"),
        )
        assertEquals(3, codes.size)
    }

    @Test
    fun recommendedListStillWorksWhenTheListingIdIsGone() {
        // 站点改版把 id 去掉时，退到「推荐容器里的卡片」；仍然不许收隔壁区块的。
        val withoutId = listing.replace(" id=\"recommendedListings\"", "")
        val codes = PhParser.recommendedList(page(listingHtml = withoutId)).map { it.videoCode }

        assertEquals(3, codes.size)
        assertFalse(codes.contains("decoy00000001"))
    }

    @Test
    fun recommendedListDropsCardsWithoutViewkey() {
        val broken = """
            <ul id="recommendedListings">
                <li class="pcVideoListItem"><div class="phimage"></div></li>
                $card1
            </ul>
        """.trimIndent()

        val codes = PhParser.recommendedList(page(listingHtml = broken)).map { it.videoCode }

        assertEquals(listOf("6769999af3dbc"), codes)
    }

    @Test
    fun recommendedListDeduplicatesByViewkey() {
        val duplicated = """
            <ul id="recommendedListings">
                $card1
                $card1
                $card2
            </ul>
        """.trimIndent()

        assertEquals(2, PhParser.recommendedList(page(listingHtml = duplicated)).size)
    }

    // ────────────────────────────────────────── 分页状态

    @Test
    fun recommendedStateIsSuccessWhileThereAreCards() {
        val size = when (val state = PhParser.recommendedState(page())) {
            is PageLoadingState.Success -> state.info.size
            else -> -1
        }

        assertEquals(3, size)
    }

    /**
     * 空页 = 到底。
     *
     * ⚠️ 这里**故意**不接受「有页码条就继续翻」：`PageLoadingState` 没有「还有下一页」
     * 这个位，列表页只认 `NoMoreData`（`SearchScreen` 的 `canLoadMore`），
     * 返回 `Success(空)` 会让它一直保持「可以再翻」，用户每滚一下白发一次请求。
     * 真正会遇到的「到底」是越界页码 404，那一段在 `NetworkRepo.phListFlow` 里处理。
     */
    @Test
    fun recommendedStateIsNoMoreDataOnAnEmptyPage() {
        val empty = """
            <ul id="recommendedListings"><li class="sniperModeEngaged"></li></ul>
        """.trimIndent()

        assertTrue(PhParser.recommendedState(page(listingHtml = empty)) is PageLoadingState.NoMoreData)
    }

    @Test
    fun recommendedStateIsNoMoreDataWhenTheMarkupIsNothingButAnErrorPage() {
        assertTrue(
            PhParser.recommendedState("<html><body>503 Service Unavailable</body></html>")
                is PageLoadingState.NoMoreData
        )
    }

    // ────────────────────────────────────────── 地址与标记

    @Test
    fun recommendedUrlOmitsPageForTheFirstPageAndAddsItAfterwards() {
        assertEquals("https://www.pornhub.com/recommended", PhNetwork.recommendedUrl(page = 1))
        assertTrue(PhNetwork.recommendedUrl(page = 3).endsWith("/recommended?page=3"))
    }

    /**
     * 列表页靠这个判据选解析器（JSON 还是 HTML），判错就会拿 JSON 解析器去啃 HTML，
     * 表现是「点进去一直空」。所以它必须能把自己和检索接口分得干干净净。
     */
    @Test
    fun isRecommendedUrlTellsItselfApartFromTheSearchApi() {
        assertTrue(PhNetwork.isRecommendedUrl(PhNetwork.recommendedUrl(page = 1)))
        assertTrue(PhNetwork.isRecommendedUrl(PhNetwork.recommendedUrl(page = 7)))
        assertFalse(PhNetwork.isRecommendedUrl(PhNetwork.apiUrl(page = 1)))
        assertFalse(PhNetwork.isRecommendedUrl("https://www.pornhub.com/view_video.php?viewkey=abc"))
    }

    /**
     * 「推荐」是唯一一个不走 `queryForMarker` 的标记 —— 它映射到另一个页面。
     * 简繁两套都要认：界面文案按语言给简体，别处（`genre_ph.json`）用繁体。
     */
    @Test
    fun recommendedMarkerIsRecognisedInBothScripts() {
        assertTrue(PhParser.isRecommendedMarker(PhParser.RECOMMENDED_MARKER))
        assertTrue(PhParser.isRecommendedMarker("推荐"))
        assertTrue(PhParser.isRecommendedMarker("Recommended"))
        assertTrue(PhParser.isRecommendedMarker(" 推薦 "))
    }

    @Test
    fun recommendedMarkerDoesNotSwallowTheOrdinarySortMarkers() {
        listOf("最新", "最多觀看", "本週熱門", "日本", "Cosplay").forEach { marker ->
            assertFalse("「$marker」不该被当成推荐", PhParser.isRecommendedMarker(marker))
        }
        assertFalse(PhParser.isRecommendedMarker(null))
        assertFalse(PhParser.isRecommendedMarker(""))
        assertFalse(PhParser.isRecommendedMarker("   "))
    }

    /**
     * 反向保障：普通的排序 / 标签标记仍然由 `queryForMarker` 正常映射
     * （这次改动只加了「推荐」这一条旁路，没动原来那张表），
     * 同时「推荐」**不该**出现在那张表里 —— 否则它会静默退回「最新」。
     */
    @Test
    fun ordinaryMarkersStillMapToSearchQueries() {
        assertEquals("newest", PhParser.queryForMarker("最新")?.ordering)
        assertEquals("mostviewed", PhParser.queryForMarker("最多觀看")?.ordering)
        assertEquals("weekly", PhParser.queryForMarker("本週熱門")?.period)
        assertEquals("japanese", PhParser.queryForMarker("日本")?.tag)

        assertNull(PhParser.queryForMarker(PhParser.RECOMMENDED_MARKER))
        assertNull(PhParser.queryForMarker("推荐"))
        assertNotNull(PhParser.queryForMarker("官方獨家"))
    }
}
