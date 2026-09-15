package io.github.daisukikaffuchino.han1meviewer.logic.ph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页大轮播「换一批」的**批次表**（26.9.7）。
 *
 * 这张表是**纯数学**，也是「按一次就能看到另一个来源」这条产品承诺的唯一依据，
 * 所以逐条钉死 —— 公式写错的表现是「按好几下都是同一批」，
 * 那在界面上几乎看不出来（用户只会觉得按钮不好使）。
 */
class PhCarouselBatchesTest {

    @Test
    fun `第 0 批与第负数批都是推荐第 1 页`() {
        // 第 0 批 = 首页首次加载就填好的那一批，不需要再请求。
        assertEquals(
            PhCarouselBatches.Batch.Recommended(1),
            PhCarouselBatches.batchAt(0)
        )
        // 防御性：批次号理论上不会为负，但一旦为负必须落在「推荐第 1 页」而不是崩。
        assertEquals(
            PhCarouselBatches.Batch.Recommended(1),
            PhCarouselBatches.batchAt(-1)
        )
        assertEquals(
            PhCarouselBatches.Batch.Recommended(1),
            PhCarouselBatches.batchAt(-99)
        )
    }

    @Test
    fun `按一次就是主页热门，不用先翻完推荐`() {
        // ⭐ 这是**产品承诺**：推荐能翻 18+ 页，若排成「先推荐后热门」，
        //    用户要按 18 次才看得到热门。所以第 1 批必须就是主页热门第 0 批。
        assertEquals(
            PhCarouselBatches.Batch.HomepageHot(0),
            PhCarouselBatches.batchAt(1)
        )
    }

    @Test
    fun `前八批的完整对照表`() {
        assertEquals(PhCarouselBatches.Batch.Recommended(1), PhCarouselBatches.batchAt(0))
        assertEquals(PhCarouselBatches.Batch.HomepageHot(0), PhCarouselBatches.batchAt(1))
        assertEquals(PhCarouselBatches.Batch.Recommended(2), PhCarouselBatches.batchAt(2))
        assertEquals(PhCarouselBatches.Batch.HomepageHot(1), PhCarouselBatches.batchAt(3))
        assertEquals(PhCarouselBatches.Batch.Recommended(3), PhCarouselBatches.batchAt(4))
        assertEquals(PhCarouselBatches.Batch.HomepageHot(2), PhCarouselBatches.batchAt(5))
        assertEquals(PhCarouselBatches.Batch.Recommended(4), PhCarouselBatches.batchAt(6))
        assertEquals(PhCarouselBatches.Batch.HomepageHot(0), PhCarouselBatches.batchAt(7))
    }

    @Test
    fun `两个来源严格交替`() {
        // 相邻两批永远来自不同来源 —— 公式写成 index/2 之类就会连着给同一个来源。
        var prev: PhCarouselBatches.Batch? = null
        for (n in 0..60) {
            val cur = PhCarouselBatches.batchAt(n)
            if (prev != null) {
                assertNotEquals(
                    "第 $n 批与第 ${n - 1} 批来源相同了，交替规则被破坏",
                    prev!!::class,
                    cur::class
                )
            }
            prev = cur
        }
    }

    @Test
    fun `推荐页号从 2 起单调递增`() {
        // 第 0 批是站点给的第 1 页，之后每轮一次就 +1。
        val pages = (0..40)
            .map { PhCarouselBatches.batchAt(it) }
            .filterIsInstance<PhCarouselBatches.Batch.Recommended>()
            .map { it.page }
        assertEquals(1, pages.first())
        assertEquals(pages.sorted(), pages)
        assertEquals("推荐页号必须每次 +1", pages.size, pages.distinct().size)
        assertEquals(2, pages[1])
    }

    @Test
    fun `推荐页号不封顶`() {
        // ⚠️ 故意不封顶：站点自己也没说准有多少页（26.9.5 实测到 18+）。
        //    越界由站点回 404、调用方回卷第 1 页处理，不在这里猜一个上限。
        val p = PhCarouselBatches.batchAt(2 * 100).let {
            (it as PhCarouselBatches.Batch.Recommended).page
        }
        assertTrue("第 200 批还应有推荐页号（不封顶），实际 $p", p > 50)
    }

    @Test
    fun `主页热门三批循环`() {
        val indices = (1..20 step 2)
            .map { (PhCarouselBatches.batchAt(it) as PhCarouselBatches.Batch.HomepageHot).index }
        // 序列必须是 0,1,2,0,1,2,… 循环，且永远落在 0..2 内。
        assertEquals(List(10) { it % PhCarouselBatches.HOMEPAGE_HOT_BATCHES }, indices)
        assertTrue(indices.all { it in 0 until PhCarouselBatches.HOMEPAGE_HOT_BATCHES })
    }

    @Test
    fun `主页热门分批能覆盖那 61 条`() {
        // 实测主页 singleFeedSection 61 条；按 21 条切，3 批要能全盖住。
        val covered = PhCarouselBatches.BATCH_SIZE * PhCarouselBatches.HOMEPAGE_HOT_BATCHES
        assertTrue(
            "3 批共 $covered 条，盖不住实测的 61 条",
            covered >= 61
        )
        // 但也不该切出太多空批：最后一批（index 2）要有东西。
        val lastFrom = (PhCarouselBatches.HOMEPAGE_HOT_BATCHES - 1) * PhCarouselBatches.BATCH_SIZE
        assertTrue("最后一批起点 $lastFrom 已经越过 61 条", lastFrom < 61)
    }

    @Test
    fun `一批的条数与推荐一页一致`() {
        // 两个来源的观感要一致；推荐页实测 21 条/页。
        assertEquals(21, PhCarouselBatches.BATCH_SIZE)
    }
}
