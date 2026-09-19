package io.github.daisukikaffuchino.han1meviewer.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import java.io.FileNotFoundException
import java.net.UnknownHostException

/**
 * 播放链路的重试策略：把「传输被掐」从**直接报错**改成**退避后重试**。
 *
 * ## 要解决的现象（用户报「一些视频播一会儿就报错」）
 *
 * ```
 * ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
 * java.io.IOException:
 *   java.util.concurrent.ExecutionException:
 *     java.net.SocketException: Connection reset
 * ```
 *
 * ⚠️ **这一串里只有最后一句是有信息的**，前面两层是 media3 自己的包装痕迹：
 *
 * media3 **1.10 起** [`OkHttpDataSource`] 不再用阻塞的 `call.execute()`，改成
 * `call.enqueue()` + `SettableFuture.get()`（`javap` 实测 1.10.1 的字节码：`catch ExecutionException →
 * new IOException(e)`；而 `IOException(Throwable)` 会把 message 设成 `cause.toString()`）。
 * 于是**任何**回调失败都会长成这个 `IOException → ExecutionException → 真异常` 的形状 ——
 * 看到它别去查 `ExecutionException`，真因永远在被包在最里层的那一个。
 *
 * 里层那句 `SocketException: Connection reset` 的含义很朴素：**这条 TCP 被对面/中间设备掐了**。
 * 大陆链路上这太常见了（CDN 边限速、代理切换出口、运营商侧重置），而**唯一有效的应对就是重试**。
 *
 * ## 默认策略为什么不够
 *
 * media3 默认 `DefaultLoadErrorHandlingPolicy` = **3 次**重试、延迟 1/2/3 s（`javap` 实测：
 * `getMinimumLoadableRetryCount` 对 media 返回 3，`getRetryDelayMsFor` 返回
 * `min((errorCount-1)*1000, 5000)`）⇒ 首次被掐之后**约 6 秒就放弃**并把错误抛给用户。
 * 而真机上「掐一下」往往是连续几秒的一串（重连又被同一个中间设备掐），3 次根本走不出来 ——
 * 表现就是**片头能看、播一会儿突然死**。
 *
 * ## 重试是**从断点续**，不是重下（这条决定了重试值不值得给大）
 *
 * `ProgressiveMediaPeriod.ExtractingLoadable.load()` 每次进入都用
 * `positionHolder.position` 重新 `dataSource.open(...)`（`javap` 实测 1.10.1 的字节码：
 * `getfield positionHolder.position → buildDataSpec → dataSource.open`），也就是新请求带
 * `Range: bytes=<断点>-`。hembed 支持 Range（下载链路一直在用 206 校验），
 * 所以一次重试只补没读到的那一段 —— 多给几次重试的代价只是「多等几秒」，不是「重下整部」。
 *
 * ## 预算（为什么不是一个数）
 *
 * | 情况 | 预算 | 为什么 |
 * |---|---|---|
 * | 4xx（403/404/410/416…）· 解析失败 · 文件不存在 · 明文禁令 | **0（立刻报错）** | 这些不会因为重试变好。尤其 hembed 的签名过期就是 **403**，跟着重试 15 次等于让用户干等一分钟才看到「403」 |
 * | `UnknownHostException` | **3** | 「这个域名解析不出来」是 host 级事实，6 秒内三次都一样，别拖成 1 分钟 |
 * | 清单（`.m3u8` / manifest） | **6** | 清单只有几 KB，≈20 秒足够；再久不如早点告诉用户 |
 * | 其它（`Connection reset` / 超时 / TLS / EOF） | **[TRANSPORT_RETRY_COUNT]** | 掐了再续是唯一出路。延迟封顶 5 s ⇒ 约 1 分钟的耐心 |
 *
 * 延迟沿用 media3 的曲线 `min((errorCount-1)*1000, 5000)`，不另造一套。
 *
 * ⚠️ **判据必须走 cause 链**：`OkHttpDataSource` 那条链路会把真正的异常埋进
 * `IOException → ExecutionException → …`，只看最外层（media3 默认实现就只看链上每一层是否
 * 命中类型，所以它是安全的）会漏掉 403。
 *
 * ⚠️ **别覆盖 [getFallbackSelectionFor]**：默认那条（403/404/410/416/500/503 ⇒ 换轨重试）
 * 是有用的，继承即可。
 *
 * @param transportRetryCount 传输层重试次数。默认 [TRANSPORT_RETRY_COUNT]。
 */
@OptIn(UnstableApi::class)
class PlaybackLoadErrorPolicy(
    private val transportRetryCount: Int = TRANSPORT_RETRY_COUNT,
) : DefaultLoadErrorHandlingPolicy(transportRetryCount) {

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val exception = loadErrorInfo.exception
        if (isFatalError(exception)) return C.TIME_UNSET
        val budget = retryBudget(
            exception = exception,
            dataType = loadErrorInfo.mediaLoadData.dataType,
            transportRetryCount = transportRetryCount,
        )
        if (loadErrorInfo.errorCount > budget) return C.TIME_UNSET
        return retryDelayMs(loadErrorInfo.errorCount)
    }

    /**
     * 上面三个判据都是纯函数、放在 companion 里，**为了能在 JVM 单测里钉住**：
     * `LoadErrorInfo` 依赖 `android.net.Uri`（`DataSpec` 的字段），单测里构造不出来。
     */
    internal companion object {

        /** 传输层被掐时的重试次数（≈1 分钟的耐心，见 [retryDelayMs]）。 */
        const val TRANSPORT_RETRY_COUNT = 15

        /** 域名解析失败：host 级事实，别让用户干等。 */
        const val DNS_RETRY_COUNT = 3

        /** 清单很小，重试几次就够。 */
        const val MANIFEST_RETRY_COUNT = 6

        const val BASE_RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_DELAY_MS = 5_000L

        /**
         * ⚠️ 这条是为了**单测**：`HttpDataSource.InvalidResponseCodeException` 的构造函数要
         * `DataSpec`（内含 `android.net.Uri`），JVM 单测里造不出来，所以同时保留消息匹配
         * （media3 给它的 message 正是 `Response code: 403`）。真实路径上类型分支先命中。
         */
        private val CLIENT_ERROR_MESSAGE = Regex("""response code: 4\d\d""", RegexOption.IGNORE_CASE)

        /** 与 media3 默认一致的退避曲线：1 s、2 s、3 s、4 s，之后固定 5 s。 */
        fun retryDelayMs(errorCount: Int): Long =
            ((errorCount - 1).coerceAtLeast(0) * BASE_RETRY_DELAY_MS).coerceAtMost(MAX_RETRY_DELAY_MS)

        /** 该错误类型的重试预算（次数）。到点返回 [C.TIME_UNSET] 即停止重试。 */
        fun retryBudget(
            exception: Throwable?,
            dataType: Int,
            transportRetryCount: Int = TRANSPORT_RETRY_COUNT,
        ): Int = when {
            anyCause(exception) { it is UnknownHostException } -> DNS_RETRY_COUNT
            dataType == C.DATA_TYPE_MANIFEST -> minOf(MANIFEST_RETRY_COUNT, transportRetryCount)
            else -> transportRetryCount
        }

        /** 重试也不会变好的错误 —— 这些直接报错。 */
        fun isFatalError(error: Throwable?): Boolean = anyCause(error) { cause ->
            cause is ParserException ||
                cause is FileNotFoundException ||
                cause is HttpDataSource.CleartextNotPermittedException ||
                cause is Loader.UnexpectedLoaderException ||
                (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode in 400..499) ||
                CLIENT_ERROR_MESSAGE.containsMatchIn(cause.message.orEmpty())
        }

        private inline fun anyCause(error: Throwable?, predicate: (Throwable) -> Boolean): Boolean {
            var current = error
            while (current != null) {
                if (predicate(current)) return true
                current = current.cause
            }
            return false
        }
    }
}
