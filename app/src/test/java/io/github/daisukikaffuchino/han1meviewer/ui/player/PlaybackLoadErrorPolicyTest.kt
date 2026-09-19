package io.github.daisukikaffuchino.han1meviewer.ui.player

import androidx.media3.common.C
import androidx.media3.common.ParserException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * | **判窄**（把 4xx 也当可重试） | hembed 的签名过期是 403，重试 15 次 ⇒ 用户干等一分钟才看到「403」，而这条链接**永远不会好** |
 * | **判宽**（把 `Connection reset` 当致命） | 回到修之前的状态：掐一下就直接报错，用户以为片子坏了 |
 *
 * ⚠️ 这里只测纯函数：`LoadErrorHandlingPolicy.LoadErrorInfo` 要 `DataSpec`（内含
 * `android.net.Uri`），JVM 单测里造不出来 —— 所以判据没有和 media3 的类型绑死，
 * 见 [PlaybackLoadErrorPolicy.isFatalError] 里那条消息兜底。
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
    fun wrappedSocketResetIsStillRetriable() {
        assertFalse(
            "连接被掐不是致命错误 —— 它正是这个策略存在的理由",
            PlaybackLoadErrorPolicy.isFatalError(wrappedReset),
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

    /** 4xx 一律不重试（签名过期 403、分片 404、区间越界 416）。 */
    @Test
    fun clientErrorsAreFatal() {
        assertTrue(
            "403：签名过期，重试一万次也一样",
            PlaybackLoadErrorPolicy.isFatalError(IOException("Response code: 403")),
        )
        assertTrue("404", PlaybackLoadErrorPolicy.isFatalError(IOException("Response code: 404")))
        assertTrue("416", PlaybackLoadErrorPolicy.isFatalError(IOException("Response code: 416")))
    }

    /** 5xx 与 4xx 相反：服务端抽风值得重试。 */
    @Test
    fun serverErrorsAreRetriable() {
        assertFalse(
            "500 / 503 是服务端临时抽风，默认策略本来就重试它们",
            PlaybackLoadErrorPolicy.isFatalError(IOException("Response code: 500")),
        )
        assertFalse(PlaybackLoadErrorPolicy.isFatalError(IOException("Response code: 503")))
    }

    /** 解析失败 / 文件不存在：确定性问题，重试只会白等。 */
    @Test
    fun deterministicFailuresAreFatal() {
        assertTrue(
            "容器解析失败",
            PlaybackLoadErrorPolicy.isFatalError(
                ParserException.createForMalformedContainer("bad container", null),
            ),
        )
        assertTrue(
            "本地文件不存在",
            PlaybackLoadErrorPolicy.isFatalError(FileNotFoundException("nope.mp4")),
        )
    }

    /** 域名解析失败：能重试，但别拖成 1 分钟。 */
    @Test
    fun dnsFailureGetsAShortBudget() {
        val dns = UnknownHostException("vdownload.hembed.com")
        assertFalse("DNS 失败不是致命的（换个网络就能好）", PlaybackLoadErrorPolicy.isFatalError(dns))
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
        val nested = IOException("outer", IOException("middle", IOException("Response code: 403")))
        assertTrue(
            "嵌套三层的 403 也要认得出来",
            PlaybackLoadErrorPolicy.isFatalError(nested),
        )
        assertFalse("没有 cause 也不能崩", PlaybackLoadErrorPolicy.isFatalError(null))
        assertFalse(PlaybackLoadErrorPolicy.isFatalError(IllegalStateException("boom")))
    }
}
