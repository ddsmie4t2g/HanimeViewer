package io.github.daisukikaffuchino.han1meviewer.logic

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

/**
 * 更新源赛跑（[raceUpdateSources]）的回归测试。
 *
 * 用户报的是「检测更新能检测，但时间有点长」。根因不是逻辑错，而是**等待策略**：
 * 老实现要收齐**所有**源的应答才做判断，而实测 5 条源里有 3 条是黑洞
 * （TCP connect 不返回，只在 connectTimeout 时失败）⇒ 每次检查都被最慢的那条拖住。
 *
 * 这里用「假源」把规则钉死 —— 不碰网络，只关心：
 * 1. **先答上来的算数**，挂死的源一点都不拖时间；
 * 2. 已经知道有更新时，只再等一小会儿取更高的版本；
 * 3. 判定「没有更新」愿意等满预算（防 jsDelivr 边缘的旧内容），但不会无限等；
 * 4. 一个源都没答上来 ⇒ 返回 null（调用方退回缓存），且只等「第一个应答」的预算。
 */
class AppUpdateSourceRaceTest {

    /** 一个「要等一会儿」的假源。 */
    private fun answerAfter(delayMs: Long, versionCode: Int, json: String = "json-$versionCode") =
        suspend {
            delay(delayMs)
            UpdateSourceAnswer(versionCode, json)
        }

    /** 一个挂死的假源：远超所有预算，正常情况下应当在决定后被取消。 */
    private fun hanging() = suspend {
        delay(60_000)
        UpdateSourceAnswer(Int.MAX_VALUE, "never")
    }

    /** 本机版本 100：101 以上才算「有更新」。 */
    private val isNewer: (Int) -> Boolean = { it > 100 }

    @Test
    fun fastestAnswerWinsAndHangingSourceDoesNotDelay() = runBlocking {
        var result: UpdateSourceAnswer? = null
        val elapsed = measureTimeMillis {
            result = raceUpdateSources(
                sources = listOf(answerAfter(20, 101), hanging(), hanging()),
                isNewer = isNewer,
                firstBudgetMillis = 5_000,
                settleMillis = 80,
                noUpdateBudgetMillis = 500,
            )
        }
        assertEquals(101, result?.versionCode)
        // 挂死的源一点都不该拖时间：≈ 20 ms（应答）+ 80 ms（结算），远小于任何预算。
        assertTrue("实际耗时 ${elapsed}ms，不该被挂死的源拖住", elapsed < 600)
    }

    @Test
    fun highestVersionAmongFastAnswersWins() = runBlocking {
        val result = raceUpdateSources(
            sources = listOf(answerAfter(20, 101), answerAfter(60, 103), answerAfter(90, 102)),
            isNewer = isNewer,
            firstBudgetMillis = 5_000,
            settleMillis = 300,
            noUpdateBudgetMillis = 500,
        )
        assertEquals(103, result?.versionCode)
    }

    @Test
    fun failingSourceIsIgnored() = runBlocking {
        val failure = suspend { throw IllegalStateException("HTTP 403") }
        val result = raceUpdateSources(
            sources = listOf(failure, answerAfter(20, 105)),
            isNewer = isNewer,
            firstBudgetMillis = 5_000,
            settleMillis = 80,
            noUpdateBudgetMillis = 300,
        )
        assertEquals(105, result?.versionCode)
    }

    /**
     * ⭐ 「没有更新」是唯一会被**旧缓存**坑到的方向（jsDelivr 边缘可能还缓存着旧内容），
     * 所以愿意等满 [noUpdateBudgetMillis]；但也不能无限等挂死的源。
     */
    @Test
    fun noUpdateWaitsTheBudgetThenConcludes() = runBlocking {
        var result: UpdateSourceAnswer? = null
        val elapsed = measureTimeMillis {
            result = raceUpdateSources(
                sources = listOf(answerAfter(20, 99), hanging()),
                isNewer = isNewer,
                firstBudgetMillis = 5_000,
                settleMillis = 80,
                noUpdateBudgetMillis = 250,
            )
        }
        assertEquals(99, result?.versionCode)
        // 等到「没有更新」的预算（250ms）就下结论，而不是等到挂死的源（60s）或第一个预算（5s）。
        assertTrue("实际耗时 ${elapsed}ms，应当等到 noUpdate 预算附近", elapsed >= 200)
        assertTrue("实际耗时 ${elapsed}ms，不该超过预算太多", elapsed < 800)
    }

    /** 一个源都没答上来 ⇒ 返回 null（调用方退回缓存），只等「第一个应答」的预算。 */
    @Test
    fun allHangingGivesUpAfterFirstAnswerBudget() = runBlocking {
        var result: UpdateSourceAnswer? = null
        val elapsed = measureTimeMillis {
            result = raceUpdateSources(
                sources = listOf(hanging(), hanging()),
                isNewer = isNewer,
                firstBudgetMillis = 150,
                settleMillis = 50,
                noUpdateBudgetMillis = 100,
            )
        }
        assertNull(result)
        assertTrue("实际耗时 ${elapsed}ms，应该在第一个应答预算附近放弃", elapsed < 800)
    }

    /** 所有源都失败也算「没答上来」，不要卡到预算满 —— 早早返回，让调用方走缓存。 */
    @Test
    fun allFailingReturnsNullQuickly() = runBlocking {
        val failure = suspend { throw IllegalStateException("boom") }
        var result: UpdateSourceAnswer? = null
        val elapsed = measureTimeMillis {
            result = raceUpdateSources(
                sources = listOf(failure, failure),
                isNewer = isNewer,
                firstBudgetMillis = 5_000,
                settleMillis = 80,
                noUpdateBudgetMillis = 300,
            )
        }
        assertNull(result)
        assertTrue("全部失败时不该等满预算（实际 ${elapsed}ms）", elapsed < 600)
    }

    @Test
    fun emptySourceListReturnsNull() = runBlocking {
        assertNull(raceUpdateSources(sources = emptyList(), isNewer = isNewer))
    }
}
