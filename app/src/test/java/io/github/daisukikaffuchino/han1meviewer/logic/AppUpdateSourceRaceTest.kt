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
        var outcome: UpdateRaceOutcome? = null
        val elapsed = measureTimeMillis {
            outcome = raceUpdateSources(
                sources = listOf(answerAfter(20, 101), hanging(), hanging()),
                isNewer = isNewer,
                firstBudgetMillis = 5_000,
                settleMillis = 80,
                noUpdateBudgetMillis = 500,
            )
        }
        assertEquals(101, outcome?.best?.versionCode)
        // 挂死的源一点都不该拖时间：≈ 20 ms（应答）+ 80 ms（结算），远小于任何预算。
        assertTrue("实际耗时 ${elapsed}ms，不该被挂死的源拖住", elapsed < 600)
        // ⭐ 两条挂死的源算「不等了（dropped）」，**不是失败** —— 日志要靠这个数字说清楚，
        //    26.9.2 把它们记成「更新源失败」才让用户以为「那两个源又坏了」。
        assertEquals(1, outcome?.answered)
        assertEquals(2, outcome?.dropped)
    }

    @Test
    fun highestVersionAmongFastAnswersWins() = runBlocking {
        val outcome = raceUpdateSources(
            sources = listOf(answerAfter(20, 101), answerAfter(60, 103), answerAfter(90, 102)),
            isNewer = isNewer,
            firstBudgetMillis = 5_000,
            settleMillis = 300,
            noUpdateBudgetMillis = 500,
        )
        assertEquals(103, outcome.best?.versionCode)
        assertEquals(3, outcome.answered)
        assertEquals(0, outcome.dropped)
    }

    @Test
    fun failingSourceIsIgnored() = runBlocking {
        val failure = suspend { throw IllegalStateException("HTTP 403") }
        val outcome = raceUpdateSources(
            sources = listOf(failure, answerAfter(20, 105)),
            isNewer = isNewer,
            firstBudgetMillis = 5_000,
            settleMillis = 80,
            noUpdateBudgetMillis = 300,
        )
        assertEquals(105, outcome.best?.versionCode)
        // 失败也计入 answered（它是「答了话：我不行」），与「不等了」区分开。
        assertEquals(2, outcome.answered)
        assertEquals(0, outcome.dropped)
    }

    /**
     * ⭐ 「没有更新」是唯一会被**旧缓存**坑到的方向（jsDelivr 边缘可能还缓存着旧内容），
     * 所以愿意等满 [noUpdateBudgetMillis]；但也不能无限等挂死的源。
     */
    @Test
    fun noUpdateWaitsTheBudgetThenConcludes() = runBlocking {
        var outcome: UpdateRaceOutcome? = null
        val elapsed = measureTimeMillis {
            outcome = raceUpdateSources(
                sources = listOf(answerAfter(20, 99), hanging()),
                isNewer = isNewer,
                firstBudgetMillis = 5_000,
                settleMillis = 80,
                noUpdateBudgetMillis = 250,
            )
        }
        assertEquals(99, outcome?.best?.versionCode)
        // 等到「没有更新」的预算（250ms）就下结论，而不是等到挂死的源（60s）或第一个预算（5s）。
        assertTrue("实际耗时 ${elapsed}ms，应当等到 noUpdate 预算附近", elapsed >= 200)
        assertTrue("实际耗时 ${elapsed}ms，不该超过预算太多", elapsed < 800)
    }

    /** 一个源都没答上来 ⇒ best 为 null（调用方退回缓存 / tag 列表兜底），只等「第一个应答」预算。 */
    @Test
    fun allHangingGivesUpAfterFirstAnswerBudget() = runBlocking {
        var outcome: UpdateRaceOutcome? = null
        val elapsed = measureTimeMillis {
            outcome = raceUpdateSources(
                sources = listOf(hanging(), hanging()),
                isNewer = isNewer,
                firstBudgetMillis = 150,
                settleMillis = 50,
                noUpdateBudgetMillis = 100,
            )
        }
        assertNull(outcome?.best)
        assertEquals(0, outcome?.answered)
        assertEquals(2, outcome?.dropped)
        assertTrue("实际耗时 ${elapsed}ms，应该在第一个应答预算附近放弃", elapsed < 800)
    }

    /** 所有源都失败也算「答了话」── 早早返回，让调用方走缓存，不要卡到预算满。 */
    @Test
    fun allFailingReturnsNullQuickly() = runBlocking {
        val failure = suspend { throw IllegalStateException("boom") }
        var outcome: UpdateRaceOutcome? = null
        val elapsed = measureTimeMillis {
            outcome = raceUpdateSources(
                sources = listOf(failure, failure),
                isNewer = isNewer,
                firstBudgetMillis = 5_000,
                settleMillis = 80,
                noUpdateBudgetMillis = 300,
            )
        }
        assertNull(outcome?.best)
        assertEquals(2, outcome?.answered)
        assertEquals(0, outcome?.dropped)
        assertTrue("全部失败时不该等满预算（实际 ${elapsed}ms）", elapsed < 600)
    }

    @Test
    fun emptySourceListReturnsNothing() = runBlocking {
        val outcome = raceUpdateSources(sources = emptyList(), isNewer = isNewer)
        assertNull(outcome.best)
        assertEquals(0, outcome.answered)
        assertEquals(0, outcome.dropped)
    }

    /**
     * ⭐ tag 列表兜底要用的版本号换算：`26.9.3` → `26009003`，
     * 与 `app/build.gradle.kts` 的 `major*1_000_000 + minor*1_000 + patch` 同一口径。
     */
    @Test
    fun versionCodeMatchesGradleFormula() {
        assertEquals(26_009_003, AppUpdateChecker.versionCodeOf("26.9.3"))
        assertEquals(26_009_003, AppUpdateChecker.versionCodeOf("v26.9.3"))
        assertEquals(26_000_900, AppUpdateChecker.versionCodeOf("26.0.900"))
        assertNull(AppUpdateChecker.versionCodeOf("nightly"))
        assertNull(AppUpdateChecker.versionCodeOf(""))
    }

    /**
     * 兜底路径合成出来的 json 必须是**能被自己解析**的形状：
     * 平时跑不到这条路，真跑起来（所有 json 源都不通）时不能再出错。
     *
     * ⚠️ 这里只做「字段在不在」的烟测：`versionCode` 与下载地址两者缺一，
     * 合成结果就会被 `toAvailableUpdateOrNull()` 判成无效 ⇒ 检查更新白跑一趟。
     */
    @Test
    fun synthesizedFallbackJsonCarriesRequiredFields() {
        val json = AppUpdateChecker.synthesizedUpdateJson("26.9.3", 26_009_003)
        assertTrue("缺少 versionName：$json", json.contains("\"versionName\":\"26.9.3\""))
        assertTrue("缺少 versionCode：$json", json.contains("\"versionCode\":26009003"))
        assertTrue(
            "下载地址不符合仓库既定命名：$json",
            json.contains(
                "https://github.com/ddsmie4t2g/HanimeViewer/releases/download/v26.9.3/" +
                    "Han1meViewer-v26.9.3.apk"
            ),
        )
    }
}
