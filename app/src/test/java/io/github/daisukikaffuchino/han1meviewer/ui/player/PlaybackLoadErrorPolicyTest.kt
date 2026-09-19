package io.github.daisukikaffuchino.han1meviewer.ui.player

import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.DataSourceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException

/**
 * [PlaybackLoadErrorPolicy] 的判据边界。
 *
 * ## 为什么值得钉住
 *
 * 这套判据是「播一会儿被掐」和「链接本来就坏了」之间的分界线，两个方向判错的代价都很实际：
 *
 * | 判错方向 | 后果 |
 * |---|---|
 * | **判窄**（连 `Connection reset` 都当致命） | 回到修之前的状态：掐一下就直接报错，用户以为片子坏了 |
 * | **判宽过头 ⇒ 反过来把 4xx 当致命** | ⚠️⚠️ 这就是 **27.0.7 的真实事故**：有些视频**根本看不了、开不开代理都一样**（见 `clientErrorsAreRetriable` 那条用例） |
 *
 * ⚠️ 这里只测纯函数：`LoadErrorHandlingPolicy.LoadErrorInfo` 要 `DataSpec`（内含
 * `android.net.Uri`），JVM 单测里造不出来 —— 所以判据没有和 media3 的类型绑死，
 * 见 [PlaybackLoadErrorPolicy.httpStatusOf] 里那条消息兜底。
 *
 * ⚠️ 别在这些用例里碰 `LogUtil`（`enabled` 默认取 `BuildConfig.DEBUG`，单测跑 debug 变体 ⇒
 * 真的会去调 `android.util.Log` ⇒ JVM 上抛 `not mocked`）。这里没用到日志。
 */
class PlaybackLoadErrorPolicyTest {

    /** media3 1.10 的包装形状：`IOException(ExecutionException(SocketException))` —— 必须认得出来。 */
    private val wrappedReset: IOException =
        IOException(ExecutionException(SocketException("Connection reset")))

    /**
     * 被掐的传输层错误：可重试，预算给满。
     *
     * 这条例子的形状是从真机的报错里抄下来的（media3 1.10 的 `OkHttpDataSource`
     * 用 `enqueue + SettableFuture.get()`，把回调失败包成
     * `IOException(ExecutionException(真异常))` 且 message = `cause.toString()`）。
     */
    @Test
    fun wrappedSocketResetIsRetriable() {
        assertFalse(
            "连接被掐不是致命错误 —— 它正是这个策略存在的理由",
            PlaybackLoadErrorPolicy.isNonRetriable(wrappedReset),
        )
        assertEquals(
            "传输层预算给满",
            PlaybackLoadErrorPolicy.TRANSPORT_RETRY_COUNT,
            PlaybackLoadErrorPolicy.retryBudget(wrappedReset, C.DATA_TYPE_MEDIA),
        )
        assertTrue(
            "message 里带着内层异常的 toString，所以「类型 + 消息」两条路都能兜住",
            wrappedReset.message.orEmpty().contains("Connection reset"),
        )
    }

    /**
     * ⭐⭐ **27.0.7 回归的钉子：4xx 必须可重试。**
     *
     * 27.0.7 把 4xx 整类划成「预算 0（立刻报错）」，于是：
     * - 多镜像站点「首次 403/404、换节点/换轨即成功」这条唯一的机会被砍掉（默认 `getFallbackSelectionFor`
     *   专门为 403/404/410/416/500/503 准备了换轨）；
     * - `Range` 越界返回的 416 也被当死。
     *
     * 结果就是用户报的「有些视频根本看不了」。**这条用例不许再被改回去。**
     */
    @Test
    fun clientErrorsAreRetriable() {
        for (code in listOf(403, 404, 410, 416)) {
            val error = IOException("Response code: $code")
            assertFalse(
                "$code 不是致命错误（默认策略把它当可换轨/可重试）",
                PlaybackLoadErrorPolicy.isNonRetriable(error),
            )
            assertEquals(
                "$code 的预算必须与 media3 默认一致（3 次），不能是 0",
                PlaybackLoadErrorPolicy.CLIENT_ERROR_RETRY_COUNT,
                PlaybackLoadErrorPolicy.retryBudget(error, C.DATA_TYPE_MEDIA),
            )
        }
    }

    /** 5xx：服务端抽风，值得比 4xx 多等几轮。 */
    @Test
    fun serverErrorsGetALongerBudget() {
        val error = IOException("Response code: 503")
        assertFalse(PlaybackLoadErrorPolicy.isNonRetriable(error))
        assertEquals(
            PlaybackLoadErrorPolicy.SERVER_ERROR_RETRY_COUNT,
            PlaybackLoadErrorPolicy.retryBudget(error, C.DATA_TYPE_MEDIA),
        )
    }

    /**
     * 「不可重试」的集合必须与 media3 默认一致 —— **只能更小，不能更大**。
     *
     * 27.0.7 的教训就是往里多塞了一类（4xx）。这条用例把「多塞」本身钉住。
     */
    @Test
    fun nonRetriableSetIsNotBroaderThanMedia3Default() {
        val mustStayRetriable = listOf(
            IOException("Response code: 403"),
            IOException("Response code: 404"),
            IOException("Response code: 416"),
            IOException("Response code: 500"),
            wrappedReset,
            UnknownHostException("vdownload.hembed.com"),
            IOException("unexpected end of stream"),
        )
        for (error in mustStayRetriable) {
            assertFalse(
                "${error.message} 必须保持可重试 —— 判窄会让视频「打开就报错」",
                PlaybackLoadErrorPolicy.isNonRetriable(error),
            )
        }
    }

    /** 解析失败 / 文件不存在 / `Range` 越界：确定性问题，重试只会白等。 */
    @Test
    fun deterministicFailuresAreNotRetriable() {
        assertTrue(
            "容器解析失败",
            PlaybackLoadErrorPolicy.isNonRetriable(
                ParserException.createForMalformedContainer("bad container", null),
            ),
        )
        assertTrue(
            "本地文件不存在",
            PlaybackLoadErrorPolicy.isNonRetriable(FileNotFoundException("nope.mp4")),
        )
        assertTrue(
            "Range 越界（media3 默认也把它当不可重试）",
            PlaybackLoadErrorPolicy.isNonRetriable(
                DataSourceException(IOException("range"), DataSourceException.POSITION_OUT_OF_RANGE),
            ),
        )
    }

    /** 域名解析失败：能重试，但别拖成 1 分钟。 */
    @Test
    fun dnsFailureGetsAShortBudget() {
        val dns = UnknownHostException("vdownload.hembed.com")
        assertFalse("DNS 失败不是致命的（换个网络就能好）", PlaybackLoadErrorPolicy.isNonRetriable(dns))
        assertEquals(
            "host 级事实，3 次（≈6 s）就够下结论",
            PlaybackLoadErrorPolicy.DNS_RETRY_COUNT,
            PlaybackLoadErrorPolicy.retryBudget(dns, C.DATA_TYPE_MEDIA),
        )
    }

    /** 清单很小，重试次数少一档；上限由构造参数决定。 */
    @Test
    fun manifestBudgetIsShorter() {
        assertEquals(
            PlaybackLoadErrorPolicy.MANIFEST_RETRY_COUNT,
            PlaybackLoadErrorPolicy.retryBudget(wrappedReset, C.DATA_TYPE_MANIFEST),
        )
        assertEquals(
            "构造参数更小时，清单预算跟着降（不越过总预算）",
            2,
            PlaybackLoadErrorPolicy.retryBudget(
                exception = wrappedReset,
                dataType = C.DATA_TYPE_MANIFEST,
                transportRetryCount = 2,
            ),
        )
        assertEquals(
            "非清单类型用构造参数",
            2,
            PlaybackLoadErrorPolicy.retryBudget(
                exception = wrappedReset,
                dataType = C.DATA_TYPE_MEDIA,
                transportRetryCount = 2,
            ),
        )
    }

    /** 退避曲线与 media3 默认一致：首次立即重试，之后 1 s 起、封顶 5 s。 */
    @Test
    fun delayCurveMatchesMedia3Default() {
        assertEquals(0L, PlaybackLoadErrorPolicy.retryDelayMs(1))
        assertEquals(1_000L, PlaybackLoadErrorPolicy.retryDelayMs(2))
        assertEquals(4_000L, PlaybackLoadErrorPolicy.retryDelayMs(5))
        assertEquals(PlaybackLoadErrorPolicy.MAX_RETRY_DELAY_MS, PlaybackLoadErrorPolicy.retryDelayMs(6))
        assertEquals(
            "再多次也不超过封顶",
            PlaybackLoadErrorPolicy.MAX_RETRY_DELAY_MS,
            PlaybackLoadErrorPolicy.retryDelayMs(99),
        )
    }

    /** 判据要走 **cause 链**：真异常常被埋在两层包装里（`cause` 为 null 也要安全）。 */
    @Test
    fun judgementWalksTheCauseChain() {
        val nested = IOException("outer", IOException("middle", IOException("Response code: 404")))
        assertEquals(
            "嵌套三层的状态码也要认得出来",
            404,
            PlaybackLoadErrorPolicy.httpStatusOf(nested),
        )
        assertFalse("没有 cause 也不能崩", PlaybackLoadErrorPolicy.isNonRetriable(null))
        assertNull("认不出状态码就返回 null（由调用方按「其它传输错误」处理）",
            PlaybackLoadErrorPolicy.httpStatusOf(IllegalStateException("boom")))
        assertFalse(PlaybackLoadErrorPolicy.isNonRetriable(IllegalStateException("boom")))
    }
}
