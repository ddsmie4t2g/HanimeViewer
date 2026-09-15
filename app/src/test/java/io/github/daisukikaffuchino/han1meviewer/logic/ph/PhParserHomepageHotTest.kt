package io.github.daisukikaffuchino.han1meviewer.logic.ph

import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主页「热门色情视频」那一节 + 它作为**大轮播第二个来源**的标记（26.9.8）。
 *
 * 夹具是 2026-09-15 从线上抓下来的真实标记
 * （`build/ph-home-cn.html`，1.26 MB 整页；这里只留结构、去掉无关属性）。
 *
 * 这套选择器要同时满足三件事，而它们在真实页面上互相干扰：
 *
 * 1. 认出 `ul#singleFeedSection` 里那 61 张卡；
 * 2. **别把页头那两个预载下拉吃进来** —— 整页有 65 个 `li.pcVideoListItem`，
 *    多出来的 4 张在 `ul#hottestMenuSection` / `ul#recommMenuSection` 里；
 * 3. 广告卡（`li.sniperModeEngaged`，没有 viewkey）要自然掉出去。
 *
 * 另外这里是「26.9.7 的 bug 复盘」的回归网：轮播放着主页热门、点「更多」却进推荐列表。
 * 根因是「更多」用了建分类时就定死的「推荐」标记。所以标记的两条性质在这里钉死：
 * **简繁英都认**，且**不吞掉「本週熱門」**（那两个字属于检索映射表，吞了就进错页）。
 */
class PhParserHomepageHotTest {

    /** 真实卡片结构（vkey / 标题 / 时长 / 观看数照抄线上），只删了无关属性与内联样式。 */
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
                    <a href="/view_video.php?viewkey=$vkey" title="$title"
                       class="latestThumb fade videoPreviewBg linkVideoThumb js-linkVideoThumb img js-viewTrack">
                        <img src="https://ei.phncdn.com/videos/202412/23/$imageId/original/(m=q3XO02ZbecuKGgaaaa)(mh=OktSqqTb8cud6rw4)0.jpg"
                             alt="$title" loading="lazy" width="320" height="180" title="$title" />
                        <div class="marker-overlays js-noFade">
                            <var class="bgShadeEffect duration tooltipTrig" data-title="视频时长">$duration</var>
                        </div>
                    </a>
                </div>
                <div class="thumbnail-info-wrapper clearfix">
                    <div class="videoUploaderBlock">
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
        title = "Chinese Amateur Fucks Her Best Friend",
        duration = "3:26",
        views = "3M",
        imageId = "462256431",
    )

    private val card2 = card(
        vkey = "670401306475e",
        title = "Japanese Housewife Cheats On Her Husband",
        duration = "11:42",
        views = "870K",
        imageId = "460123456",
    )

    /** 广告卡：没有 `data-video-vkey`、也没有 `viewkey` 链接 —— 必须被静默丢掉。 */
    private val adCard = """
        <li class="sniperModeEngaged"><div class="w a"><div class="c"></div></div></li>
    """.trimIndent()

    /**
     * 页头那两个预载下拉 —— 线上整页多出来的那 4 张就在类似结构里。
     * **不能被当成「热门色情视频」的内容**（这正是容器必须限定的原因）。
     */
    private val headerDropdowns = """
        <ul class="js-menuSectionVideos" id="hottestMenuSection">
            <li class="pcVideoListItem" data-video-vkey="decoy-hot-01"><div class="phimage"></div></li>
            <li class="pcVideoListItem" data-video-vkey="decoy-hot-02"><div class="phimage"></div></li>
        </ul>
        <ul class="js-menuSectionVideos" id="recommMenuSection">
            <li class="pcVideoListItem" data-video-vkey="decoy-rec-01"><div class="phimage"></div></li>
            <li class="pcVideoListItem" data-video-vkey="decoy-rec-02"><div class="phimage"></div></li>
        </ul>
    """.trimIndent()

    private val singleFeed = """
        <div class="sectionWrapper">
            <h1 class="sectionTitle">热门色情视频 <i class="roundFlagIcon round-flag-int"></i></h1>
            <ul class="full-row-thumbs display-grid col-3-sm col-4 videos" id="singleFeedSection">
                $adCard
                $card1
                $card2
            </ul>
        </div>
    """.trimIndent()

    private fun page(sectionHtml: String = singleFeed, extra: String = headerDropdowns) =
        "<html><head><title>Hot Porn Videos</title></head><body>$extra$sectionHtml</body></html>"

    // ────────────────────────────────────────── 卡片解析

    @Test
    fun homepageHotListParsesEveryCardInTheSingleFeedSection() {
        val videos = PhParser.homepageHotList(page())

        assertEquals(2, videos.size)
        assertEquals(listOf("6769999af3dbc", "670401306475e"), videos.map { it.videoCode })

        val first = videos.first()
        assertEquals("Chinese Amateur Fucks Her Best Friend", first.title)
        assertEquals("3:26", first.duration)
        assertEquals("3M", first.views)
        assertTrue(first.coverUrl.startsWith("https://ei.phncdn.com/videos/"))
    }

    @Test
    fun homepageHotListIgnoresTheHeaderDropdownCards() {
        // ⚠️ 线上整页 65 张卡、`#singleFeedSection` 里只有 61 张；多出来的 4 张在页头下拉。
        //    选择器退回全局 `li.pcVideoListItem` 时，这条会挂。
        val codes = PhParser.homepageHotList(page()).map { it.videoCode }

        assertEquals(2, codes.size)
        assertFalse("页头下拉的卡片混进来了（容器必须限定 #singleFeedSection）", codes.any { it.startsWith("decoy-") })
    }

    @Test
    fun homepageHotListDropsTheAdCard() {
        // 广告卡没有 viewkey ⇒ 应该被丢掉，而不是塞一个点不开的空壳。
        assertFalse(PhParser.homepageHotList(page()).any { it.videoCode.isBlank() })
    }

    @Test
    fun homepageHotListStillWorksWhenTheSectionIdIsGone() {
        // 站点改版把 id 去掉时退到 `ul.full-row-thumbs li.pcVideoListItem`；
        // 页头那两个下拉用的是 `js-menuSectionVideos`，不会被带进来。
        val withoutId = singleFeed.replace(" id=\"singleFeedSection\"", "")
        val codes = PhParser.homepageHotList(page(sectionHtml = withoutId)).map { it.videoCode }

        assertEquals(listOf("6769999af3dbc", "670401306475e"), codes)
        assertFalse(codes.any { it.startsWith("decoy-") })
    }

    @Test
    fun homepageHotListReturnsEmptyWhenThereIsNoSectionAtAll() {
        // 容器不在（被限流 / 挑战页）⇒ 空列表，上层按「这一批没有」处理，别抛异常。
        val codes = PhParser.homepageHotList(page(sectionHtml = "")).map { it.videoCode }

        assertEquals(emptyList<String>(), codes)
    }

    @Test
    fun homepageHotListDeduplicatesByViewkey() {
        val duplicated = """
            <ul id="singleFeedSection">
                $card1
                $card1
                $card2
            </ul>
        """.trimIndent()

        assertEquals(2, PhParser.homepageHotList(page(sectionHtml = duplicated)).size)
    }

    // ────────────────────────────────────────── 列表页状态

    @Test
    fun homepageHotStateIsSuccessWhileThereAreCards() {
        val size = when (val state = PhParser.homepageHotState(page())) {
            is PageLoadingState.Success -> state.info.size
            else -> -1
        }

        assertEquals(2, size)
    }

    /**
     * 空 = 到底。
     *
     * ⚠️ 与 `recommendedState` 同一个理由：`PageLoadingState` 没有「还有下一页」这个位，
     * 列表页只认 `NoMoreData`。主页**本来就只有一页**，所以这个状态基本只会在
     * 「站点改版 / 被限流」时出现；真正的第 2 页请求在 `NetworkRepo.phListFlow`
     * 里**发请求之前**就被答掉了（那一趟是 1.25 MB）。
     */
    @Test
    fun homepageHotStateIsNoMoreDataOnAnEmptyPage() {
        val empty = """<ul id="singleFeedSection">$adCard</ul>"""
        assertTrue(PhParser.homepageHotState(page(sectionHtml = empty)) is PageLoadingState.NoMoreData)
    }

    @Test
    fun homepageHotStateIsNoMoreDataWhenTheMarkupIsNothingButAnErrorPage() {
        assertTrue(
            PhParser.homepageHotState("<html><body>503 Service Unavailable</body></html>")
                is PageLoadingState.NoMoreData
        )
    }

    // ────────────────────────────────────────── 地址与标记

    /**
     * 主页地址的判据决定列表页用哪个解析器（见 `NetworkRepo.phListFlow`）。
     * 它必须与「推荐」「检索接口」「详情页」三种地址都不相交 —— 判错的表现是
     * 「点进去一直空」或「更多进了错的列表」。
     */
    @Test
    fun isHomepageUrlTellsItselfApartFromEveryOtherKindOfUrl() {
        assertTrue(PhNetwork.isHomepageUrl(PhNetwork.homeUrl()))
        // 站点两种形态（带不带尾斜杠）都要认。
        assertTrue(PhNetwork.isHomepageUrl("https://www.pornhub.com"))
        assertTrue(PhNetwork.isHomepageUrl("https://www.pornhub.com/"))

        assertFalse(PhNetwork.isHomepageUrl(PhNetwork.recommendedUrl(page = 1)))
        assertFalse(PhNetwork.isHomepageUrl(PhNetwork.apiUrl(page = 1)))
        assertFalse(PhNetwork.isHomepageUrl("https://www.pornhub.com/view_video.php?viewkey=abc"))
        assertFalse(PhNetwork.isHomepageUrl("https://www.pornhub.com/pornstar/foo/videos"))
    }

    @Test
    fun homepageHotMarkerIsRecognisedInBothScriptsAndEnglish() {
        assertTrue(PhParser.isHomepageHotMarker(PhParser.HOMEPAGE_HOT_MARKER))
        assertTrue(PhParser.isHomepageHotMarker("热门色情视频"))
        assertTrue(PhParser.isHomepageHotMarker("Hot Porn Videos"))
        assertTrue(PhParser.isHomepageHotMarker(" 熱門色情視頻 "))
    }

    /**
     * ⚠️ **这条最重要**：「熱門 / 热门」两个字属于「本週熱門」（`queryForMarker` 映射到
     * `ordering=mostviewed&period=weekly`），**不能被当成主页热门**。吞了的话，
     * 「本週熱門」那一栏的「更多」会跳去主页。
     */
    @Test
    fun homepageHotMarkerDoesNotSwallowTheWeeklyHotMarker() {
        listOf("熱門", "热门", "本週熱門", "本周热门", "最新", "最多觀看", "日本", "Cosplay")
            .forEach { marker ->
                assertFalse("「$marker」不该被当成主页热门", PhParser.isHomepageHotMarker(marker))
            }
        assertFalse(PhParser.isHomepageHotMarker(null))
        assertFalse(PhParser.isHomepageHotMarker(""))
        assertFalse(PhParser.isHomepageHotMarker("   "))
    }

    /** 两个「页面型」标记必须互不吞并 —— 否则「更多」会随机进错列表。 */
    @Test
    fun theTwoPageMarkersDoNotSwallowEachOther() {
        assertFalse(PhParser.isHomepageHotMarker(PhParser.RECOMMENDED_MARKER))
        assertFalse(PhParser.isHomepageHotMarker("推荐"))
        assertFalse(PhParser.isRecommendedMarker(PhParser.HOMEPAGE_HOT_MARKER))
        assertFalse(PhParser.isRecommendedMarker("热门色情视频"))

        // 而且两个都**不在**检索映射表里（在里面就会静默退化成「最新」）。
        assertNull(PhParser.queryForMarker(PhParser.HOMEPAGE_HOT_MARKER))
        assertNull(PhParser.queryForMarker("热门色情视频"))
    }

    /** 「本週熱門」仍然正常走检索映射 —— 这次加旁路没动原来那张表。 */
    @Test
    fun weeklyHotStillMapsToTheSearchQuery() {
        assertEquals("mostviewed", PhParser.queryForMarker("本週熱門")?.ordering)
        assertEquals("weekly", PhParser.queryForMarker("本週熱門")?.period)
        assertEquals("weekly", PhParser.queryForMarker("热门")?.period)
    }
}
