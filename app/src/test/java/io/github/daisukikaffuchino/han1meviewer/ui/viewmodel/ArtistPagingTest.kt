package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作者页分页的回归测试（26.9.4 重写）。
 *
 * 26.9.4 把分页模型换成了**一页 = 站点自己的一页**（见 [ArtistPaging] 的类注释），
 * 这一组测试钉住三件事：
 *
 * 1. **页码条是渐进展开的**：先只到 6，点 6 之后才长出 7、8、9（用户 2026-09-15 的要求）；
 * 2. **「下一页」只看站点那边有没有**，永远不要拿「总页数」当闸门
 *    （26.9.0 的「不管多少作品最多十页」就是那么来的）；
 * 3. **总页数用站点口径**：站点公布的页数 / 页码条优先，其次是「作品数 ÷ 实测站点一页条数」。
 */
class ArtistPagingTest {

    /** Pornhub 作者页实测的一页条数（跳页 / 换算都按这个量级想）。 */
    private val phPageSize = 49

    //<editor-fold desc="分页条：渐进展开">

    /**
     * ⭐ 主回归（用户原话：「假设一共有 34 页，不能直接排 1 2 3 4 … 34，跨度太大，
     * 最好是只到 6，然后点了 6 之后会展开 7、8、9 三页」）。
     */
    @Test
    fun stripStartsAtSixThenExpandsByThree() {
        // 第一屏：只到 6。
        assertEquals(listOf(1, 2, 3, 4, 5, 6), ArtistPaging.strip(1, 34))
        // 点 6 → 翻到第 6 页，右边长出 7、8、9（左边收成 `1 …`）。
        assertEquals(listOf(1, null, 6, 7, 8, 9), ArtistPaging.strip(6, 34))
        // 再点 9 → 12。
        assertEquals(listOf(1, null, 9, 10, 11, 12), ArtistPaging.strip(9, 34))
        // 站在末页：右边不再长，左边收着。
        assertEquals(listOf(1, null, 31, 32, 33, 34), ArtistPaging.strip(34, 34))
    }

    /** 页数少的时候全画出来，不要为了「统一」去藏页。 */
    @Test
    fun stripShowsEverythingWhenPagesAreFew() {
        assertEquals(emptyList<Int?>(), ArtistPaging.strip(1, 1))
        assertEquals(listOf(1, 2, 3), ArtistPaging.strip(1, 3))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), ArtistPaging.strip(4, 6))
    }

    /** 页码条一行最多 6 格：页数再多也不会把这一行撑爆（屏宽是硬约束）。 */
    @Test
    fun stripNeverGrowsWiderThanBudget() {
        for (page in 1..999) {
            assertTrue("第 $page 页画太宽了", ArtistPaging.strip(page, 999).size <= 6)
        }
    }

    /** 当前页永远画得出来（否则用户翻到远处会看不到自己在哪）。 */
    @Test
    fun stripAlwaysContainsCurrentPage() {
        for (page in 1..200) {
            val numbers = ArtistPaging.strip(page, 200).filterNotNull()
            assertTrue("第 $page 页不在页码条里：$numbers", page in numbers)
        }
    }

    //</editor-fold>

    //<editor-fold desc="能不能前进 / 总页数">

    /**
     * ⭐ 主回归：已经翻到的页数正好撑满整数页时，「下一页」必须还能点。
     *
     * 26.9.0 就是在这里死锁的（`canNext = page < totalPages`，而 totalPages 是估算值）。
     * 现在 [ArtistPaging.resolve] 只看 `hasNext`。
     */
    @Test
    fun canNextIgnoresTotalPages() {
        val verdict = ArtistPaging.resolve(
            page = 10,
            maxLoadedPage = 10,
            knownTotalPages = null,
            hasNext = true,
        )
        assertEquals(10, verdict.page)
        assertTrue("站点那边还有下一页时必须给翻", verdict.canNext)
    }

    /** 站点自己说了总页数（hanime 的 Laravel 页码条 / nJAV 女优页的页码条）：第一页就画准。 */
    @Test
    fun siteOwnPageCountWins() {
        val verdict = ArtistPaging.resolve(
            page = 1,
            maxLoadedPage = 1,
            knownTotalPages = 20,
            hasNext = true,
        )
        assertEquals(20, verdict.totalPages)
        assertTrue(verdict.canNext)
    }

    /** 站点那边确认没有了：总页数按实际翻到的算，人不被送进空白页，「下一页」也该灰掉。 */
    @Test
    fun exhaustedSiteStopsAtLastLoadedPage() {
        val verdict = ArtistPaging.resolve(
            page = 3,
            maxLoadedPage = 3,
            knownTotalPages = 20,
            hasNext = false,
        )
        assertEquals(3, verdict.totalPages)
        assertFalse(verdict.canNext)
    }

    /** 拿不到任何总数时，总页数跟着「已经翻到第几页」往上长（这就是「动态调整」）。 */
    @Test
    fun totalPagesGrowWhileBrowsing() {
        assertEquals(1, ArtistPaging.resolve(1, 1, null, true).totalPages)
        assertEquals(4, ArtistPaging.resolve(4, 4, null, true).totalPages)
    }

    /** 上一页只看当前页；第 1 页没有上一页。 */
    @Test
    fun canPrevFollowsCurrentPage() {
        assertFalse(ArtistPaging.resolve(1, 5, null, true).canPrev)
        assertTrue(ArtistPaging.resolve(3, 5, null, true).canPrev)
    }

    /** 页号 0 / 负数一律当第 1 页。 */
    @Test
    fun pageIsAtLeastOne() {
        assertEquals(1, ArtistPaging.resolve(0, 5, null, true).page)
        assertEquals(1, ArtistPaging.resolve(-5, 5, null, true).page)
    }

    //</editor-fold>

    //<editor-fold desc="作品数文案 → 页数">

    /**
     * ⭐ 站点文案里的作品数必须解析得出来（26.9.0 这里是**恒 null**：抠出来的字符串带尾巴，
     * `toIntOrNull()` 永远是 null，于是「总页数一次算准」从来没生效过）。
     *
     * ⚠️ 除数必须是**站点一页条数**（实测值），不是应用内一页的条数 —— 后者就是
     * 「页数做的也不对 / 有些作品遗失」的来源。
     */
    @Test
    fun countTextWithTrailingTextStillParses() {
        // Pornhub：87 部 ÷ 49 条/页 = 2 页（不是 87 ÷ 12 = 8 页）。
        assertEquals(2, ArtistPaging.pagesFromCountText("87 Videos", phPageSize))
        assertEquals(97, ArtistPaging.pagesFromCountText("5668 部影片", 59))
        assertEquals(31, ArtistPaging.pagesFromCountText("1,234 部影片", 40))
        assertEquals(42, ArtistPaging.pagesFromCountText("1234 videos", 30))
    }

    /** `1.2K` / `3M` 这类简写反推不出准确条数 ⇒ 当「不知道」，退回「按已翻到的页数长」。 */
    @Test
    fun abbreviatedCountsAreNotTrusted() {
        assertNull(ArtistPaging.pagesFromCountText("1.2K Videos", phPageSize))
        assertNull(ArtistPaging.pagesFromCountText("3M 部影片", phPageSize))
    }

    /** 解析不出来 / 0 / 不知道站点一页几条 一律当「不知道」。 */
    @Test
    fun unparsableCountsFallBackToNull() {
        assertNull(ArtistPaging.pagesFromCountText(null, phPageSize))
        assertNull(ArtistPaging.pagesFromCountText("   ", phPageSize))
        assertNull(ArtistPaging.pagesFromCountText("暂无", phPageSize))
        assertNull(ArtistPaging.pagesFromCountText("0 部影片", phPageSize))
        // ⚠️ 还没拉到任何一页时不知道站点页容量，别拿 12 之类的数硬算。
        assertNull(ArtistPaging.pagesFromCountText("87 Videos", 0))
    }

    //</editor-fold>
}
