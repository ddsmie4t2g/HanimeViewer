package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作者页分页条的回归测试。
 *
 * 用户报的现象：**不管作者还是女优有多少视频，最多只显示十页**。
 * 根因是两个 bug 叠在一起（详见 [ArtistPaging] 的类注释）：
 *
 * 1. 站点作品数文案解析恒失败（`87 Videos` 抠出 `"87 "`，`toIntOrNull()` 为 null）
 *    ⇒ 总页数只剩「已加载条数 / 12」一个来源；
 * 2. 「下一页」要求「还没到总页数」⇒ 站在最后一页**死锁**。已加载正好 120 条（=10 页）时，
 *    用户就永远停在「第 10 / 10 页」。
 *
 * 这一组测试把两件事都钉住：**什么时候必须还能翻**、**什么文案才算得出总页数**。
 */
class ArtistPagingTest {

    /** 与 `ArtistViewModel.PAGE_SIZE` 同一个口径（12 条/页）。 */
    private val pageSize = ArtistViewModel.PAGE_SIZE

    /**
     * ⭐ 主回归：已加载 120 条（正好 10 页）时，「下一页」必须还能点。
     *
     * 这条红过就是用户报的「最多十页」又回来了。
     */
    @Test
    fun loadedPrefixFillingWholePagesStillAllowsNext() {
        val verdict = ArtistPaging.resolve(
            requested = 10,
            loadedCount = 120,
            knownTotalPages = null,
            remoteHasMore = true,
            pageSize = pageSize,
        )
        assertEquals(10, verdict.page)
        assertEquals(10, verdict.totalPages)
        assertTrue("站点那边还有下一页时必须给翻", verdict.canNext)
    }

    /** 站点一页 49 条（Pornhub 实测）：第一页进来就该能翻，走到本地最后一页也还能继续。 */
    @Test
    fun firstSitePageStillLetsUserGoForward() {
        val first = ArtistPaging.resolve(1, 49, null, remoteHasMore = true, pageSize = pageSize)
        assertEquals(5, first.totalPages)
        assertTrue(first.canNext)

        val last = ArtistPaging.resolve(5, 49, null, remoteHasMore = true, pageSize = pageSize)
        assertEquals(5, last.page)
        assertTrue(last.canNext)
    }

    /** 站点公布了作品数：页码条一次画准（5668 部 ⇒ 473 页）；跳页先停在「已拿到条数」的那一页。 */
    @Test
    fun knownSiteCountDrivesTotalPages() {
        val verdict = ArtistPaging.resolve(
            requested = 473,
            loadedCount = 49,
            knownTotalPages = 473,
            remoteHasMore = true,
            pageSize = pageSize,
        )
        // 目标页的 12 条还没攒够 ⇒ 停在能画出来的最后一页，等补拉完再回来。
        assertEquals(5, verdict.page)
        assertEquals(473, verdict.totalPages)
        assertTrue(verdict.canNext)
    }

    /** 站点那边确认没有了：总页数按实际拿到的算，人不被送进空白页，「下一页」也该灰掉。 */
    @Test
    fun exhaustedSiteStopsAtLastLoadedPage() {
        val verdict = ArtistPaging.resolve(
            requested = 11,
            loadedCount = 120,
            knownTotalPages = null,
            remoteHasMore = false,
            pageSize = pageSize,
        )
        assertEquals(10, verdict.page)
        assertEquals(10, verdict.totalPages)
        assertFalse(verdict.canNext)
    }

    /** 本地已经攒下了下一页 ⇒ 就算站点那边到头了也能继续翻（最后一页是部分页的情况）。 */
    @Test
    fun localNextPageWorksEvenWhenSiteSaysNoMore() {
        assertTrue(
            ArtistPaging.resolve(1, 30, null, remoteHasMore = false, pageSize = pageSize).canNext
        )
        assertFalse(
            ArtistPaging.resolve(3, 30, null, remoteHasMore = false, pageSize = pageSize).canNext
        )
    }

    /** 上一页只看当前页；第 1 页没有上一页。 */
    @Test
    fun canPrevFollowsCurrentPage() {
        assertFalse(ArtistPaging.resolve(1, 120, null, true, pageSize).canPrev)
        assertTrue(ArtistPaging.resolve(3, 120, null, true, pageSize).canPrev)
    }

    /** 页码越界（点太远 / 负数）一律夹回合法范围，不越界取切片。 */
    @Test
    fun requestedPageIsClamped() {
        assertEquals(1, ArtistPaging.resolve(0, 49, null, true, pageSize).page)
        assertEquals(1, ArtistPaging.resolve(-5, 49, null, true, pageSize).page)
        assertEquals(5, ArtistPaging.resolve(9999, 49, null, true, pageSize).page)
    }

    /** 一条都还没加载（首屏 loading）时按 1 页画，别把自己算崩。 */
    @Test
    fun emptyPrefixIsOnePage() {
        val verdict = ArtistPaging.resolve(1, 0, null, remoteHasMore = true, pageSize = pageSize)
        assertEquals(1, verdict.page)
        assertEquals(1, verdict.totalPages)
        assertFalse(verdict.canPrev)
    }

    /**
     * ⭐ 站点文案里的作品数必须解析得出来。
     *
     * 26.9.0 这里是**恒 null**（抠出来的字符串带尾巴），所以「总页数一次算准」从来没生效过。
     */
    @Test
    fun countTextWithTrailingTextStillParses() {
        assertEquals(8, ArtistPaging.pagesFromCountText("87 Videos", pageSize))
        assertEquals(473, ArtistPaging.pagesFromCountText("5668 部影片", pageSize))
        assertEquals(103, ArtistPaging.pagesFromCountText("1,234 部影片", pageSize))
        assertEquals(103, ArtistPaging.pagesFromCountText("1234 videos", pageSize))
    }

    /** `1.2K` / `3M` 这类简写反推不出准确条数 ⇒ 当「不知道」，退回已加载条数口径。 */
    @Test
    fun abbreviatedCountsAreNotTrusted() {
        assertNull(ArtistPaging.pagesFromCountText("1.2K Videos", pageSize))
        assertNull(ArtistPaging.pagesFromCountText("3M 部影片", pageSize))
    }

    /** 解析不出来 / 0 一律当「不知道」。 */
    @Test
    fun unparsableCountsFallBackToNull() {
        assertNull(ArtistPaging.pagesFromCountText(null, pageSize))
        assertNull(ArtistPaging.pagesFromCountText("   ", pageSize))
        assertNull(ArtistPaging.pagesFromCountText("暂无", pageSize))
        assertNull(ArtistPaging.pagesFromCountText("0 部影片", pageSize))
    }

    /**
     * ⭐ **站点总页数 → 应用内页数**（hanime 作者页唯一的「一共多少页」来源）。
     *
     * 实测：普通模板 59 条/站点页 × 20 页、带分类的简化模板 41 条/站点页 × 13 页。
     * 换算要乘站点一页实测条数，所以是**上界**（末页不满 ⇒ 估出来比真实略多一两页），
     * 用户点到估出来的末页会被夹回真正的最后一页 —— 宁可估大也不估小。
     */
    @Test
    fun siteTotalPagesConvertToAppPages() {
        assertEquals(99, ArtistPaging.pagesFromSitePages(20, 59, pageSize))
        assertEquals(45, ArtistPaging.pagesFromSitePages(13, 41, pageSize))
        // 只有一页时也要给出 1，而不是 0。
        assertEquals(1, ArtistPaging.pagesFromSitePages(1, 5, pageSize))
    }

    /** 站点页数 / 实测条数缺一边就没法换算 ⇒ null，调用方退回「已加载条数」口径。 */
    @Test
    fun sitePagesNeedBothInputs() {
        assertNull(ArtistPaging.pagesFromSitePages(null, 59, pageSize))
        assertNull(ArtistPaging.pagesFromSitePages(20, 0, pageSize))
        assertNull(ArtistPaging.pagesFromSitePages(0, 59, pageSize))
    }
}
