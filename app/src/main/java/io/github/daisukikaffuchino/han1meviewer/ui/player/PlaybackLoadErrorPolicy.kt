package io.github.daisukikaffuchino.han1meviewer.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.Loader
import io.github.daisukikaffuchino.han1meviewer.logic.network.LineUnreachableException
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
 * ## ⚠️⚠️ 27.0.8 的回归修复：**4xx 绝不能判成「立刻放弃」**
 *
 * 27.0.7 把「4xx」整类划进了「重试也不会变好 ⇒ 预算 0（立刻报错）」。这个判断是**错的**，
 * 代价是「有些视频根本看不了、开不开代理都一样」：
 *
 * | 为什么错 | 说明 |
 * |---|---|
 * | 4xx 在 media3 的语义里**是可恢复的** | 默认 `getFallbackSelectionFor` 专门为 `403 / 404 / 410 / 416 / 500 / 503` 返回换轨重试（`FALLBACK_TYPE_SELECTION`）——换轨（HLS 换 variant、多轨换线路）**正是这类错误的解法**，而「预算 0」让播放器连换轨的机会都没有 |
 * | 多镜像站点天生允许「首次 4xx」 | 这类站点的分片/清单走 CDN 边缘，某节点未同步时首次 403/404、重试换节点即成功。默认策略的 3 次重试正是为它准备的 |
 * | 416 不是「链接坏了」 | `Range` 起点越界（例如续播位置落在换清晰度后的新文件之外）会返回 416，重试/换轨即可，判死等于「打开就报错」 |
 *
 * ⇒ 27.0.8 起：**4xx 恢复默认语义（可重试 + 可换轨）**，与 media3 默认的宽容度**只增不减**。
 * 「不可重试」的集合回到 media3 默认那几类（见 [isNonRetriable]）——
 * **即本策略在任何情况下都不会比默认更早放弃**，这是这次回归的硬约束。
 *
 * ## 预算（为什么不是一个数）
 *
 * | 情况 | 预算 | 为什么 |
 * |---|---|---|
 * | 解析失败 · 文件不存在 · 明文禁令 · 加载器内部异常 · `Range` 越界 | **0（立刻报错）** | 与 media3 默认**完全同一集合**：重试不会变好 |
 * | 4xx（403/404/410/416…） | **3** | 与默认一致（见上面那段）；**不是 0** |
 * | 5xx（500/502/503） | **6** | 服务端/网关抽风，值得多等几轮 |
 * | `UnknownHostException` | **3** | 「这个域名解析不出来」是 host 级事实，6 秒内三次都一样，别拖成 1 分钟 |
 * | 清单（`.m3u8` / manifest） | **6** | 清单只有几 KB，≈20 秒足够；再久不如早点告诉用户 |
 * | [LineUnreachableException]（直连必死 **且** 中转不可用） | **2** | 本地**立刻**抛出、不发请求，2 次只为留住「用户刚挂上代理」的窗口。见 [LINE_UNREACHABLE_RETRY_COUNT] —— ⚠️ 26.9.19 之前它落进下面那档，拿 15 次预算，制造了「一直转圈」 |
 * | 其它（`Connection reset` / 超时 / TLS / EOF） | **[TRANSPORT_RETRY_COUNT]** | 掐了再续是唯一出路。延迟封顶 5 s ⇒ 约 1 分钟的耐心 |
 *
 * 延迟沿用 media3 的曲线 `min((errorCount-1)*1000, 5000)`，不另造一套。
 *
 * ⚠️ **判据必须走 cause 链**：`OkHttpDataSource` 那条链路会把真正的异常埋进
 * `IOException → ExecutionException → …`，只看最外层会漏掉真正的状态码。
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
        if (isNonRetriable(exception)) return C.TIME_UNSET
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

        /**
         * 「整条线路没有出口」时的重试次数 —— 拿到
         * [io.github.daisukikaffuchino.han1meviewer.logic.network.LineUnreachableException] 时用。
         *
         * ## ⚠️⚠️ 这是 26.9.19「一直转圈」事故的修复点
         *
         * 那个异常的含义是**客户端已经确认过「直连必死 + 中转不可用」**（见
         * `CdnRelayInterceptor` 里那两处抛出点）。它和 [TRANSPORT_RETRY_COUNT] 想治的
         * 「掐一下、等会儿就好」是**相反**的情况：
         *
         * | | 原始 `SocketException: Connection reset` | [LineUnreachableException] |
         * |---|---|---|
         * | 语义 | 中间设备抖了一下 | 这条线路现在**没有出口** |
         * | 每次重试的代价 | **真实再撞一次墙** 0.8–2.6 s | **0**（本地立刻抛出，不发请求）|
         * | 该给几次 | 15（等它恢复） | 2（只为留住「用户刚挂上代理」那一个窗口）|
         *
         * 原先它落进 `else` 档拿 15 次预算，而这 15 次每一次都要付一次撞墙的时间
         * ⇒ ≈ 100 s 的转圈、且一个字提示都没有 —— 正是用户报的现象。
         *
         * ## 为什么是 2 而不是 0
         *
         * `CdnRelay` 里「直连必死」的结论带 TTL（`DIRECT_DEAD_TTL_MS`，120 s）。给 0 次
         * 意味着这一轮**完全没有机会**发现「用户刚挂上代理」，只能等下一次用户手动点播放。
         * 2 次重试（延迟 0 ms + 1 s）刚好跨过一个很短的时间窗，代价 ≈ 3 s；
         * 而结论一旦在重试期间过期，重试就会真的去验一次直连，通了即恢复。
         */
        const val LINE_UNREACHABLE_RETRY_COUNT = 2

        /** 域名解析失败：host 级事实，别让用户干等。 */
        const val DNS_RETRY_COUNT = 3

        /** 清单很小，重试几次就够。 */
        const val MANIFEST_RETRY_COUNT = 6

        /** 服务端 / 网关错误：值得多等几轮。 */
        const val SERVER_ERROR_RETRY_COUNT = 6

        /**
         * 4xx 的重试次数。
         *
         * ⚠️⚠️ **不能是 0** —— 见类注释「27.0.8 的回归修复」。这是 media3 默认值，
         * 也是「首次 403/404、换节点成功」这类多镜像站点唯一的机会。
         */
        const val CLIENT_ERROR_RETRY_COUNT = 3

        const val BASE_RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_DELAY_MS = 5_000L

        /**
         * ⚠️ 这条是为了**单测**：`HttpDataSource.InvalidResponseCodeException` 的构造函数要
         * `DataSpec`（内含 `android.net.Uri`），JVM 单测里造不出来，所以同时保留消息匹配
         * （media3 给它的 message 正是 `Response code: 403`）。真实路径上类型分支先命中。
         */
        private val STATUS_MESSAGE =
            Regex("""response code: (\d\d\d)""", RegexOption.IGNORE_CASE)

        /** 与 media3 默认一致的退避曲线：1 s、2 s、3 s、4 s，之后固定 5 s。 */
        fun retryDelayMs(errorCount: Int): Long =
            ((errorCount - 1).coerceAtLeast(0) * BASE_RETRY_DELAY_MS).coerceAtMost(MAX_RETRY_DELAY_MS)

        /** 该错误类型的重试预算（次数）。到点返回 [C.TIME_UNSET] 即停止重试。 */
        fun retryBudget(
            exception: Throwable?,
            dataType: Int,
            transportRetryCount: Int = TRANSPORT_RETRY_COUNT,
        ): Int {
            // ⚠️ 这一档必须排在**最前面**（包括「清单」那条之前）：[LineUnreachableException]
            //    是本地立刻抛出的、与 dataType 无关的确定性结论 —— 清单虽然只有几 KB，
            //    但同样没必要为它走 6 次预算（那 6 次一样是白等）。
            if (anyCause(exception) { it is LineUnreachableException }) {
                return LINE_UNREACHABLE_RETRY_COUNT
            }
            val status = httpStatusOf(exception)
            return when {
                dataType == C.DATA_TYPE_MANIFEST -> minOf(MANIFEST_RETRY_COUNT, transportRetryCount)
                anyCause(exception) { it is UnknownHostException } -> DNS_RETRY_COUNT
                status != null && status in 500..599 -> SERVER_ERROR_RETRY_COUNT
                status != null && status in 400..499 -> CLIENT_ERROR_RETRY_COUNT
                else -> transportRetryCount
            }
        }

        /**
         * 重试也不会变好的错误 —— 这些直接报错。
         *
         * ⚠️⚠️ **这个集合必须与 media3 默认的 `isAnyCauseNonRetriable` 保持一致，只能更小、不能更大**：
         * 27.0.7 往里塞了「4xx」，直接导致「有些视频根本看不了」。
         */
        fun isNonRetriable(error: Throwable?): Boolean = anyCause(error) { cause ->
            cause is ParserException ||
                cause is FileNotFoundException ||
                cause is HttpDataSource.CleartextNotPermittedException ||
                cause is Loader.UnexpectedLoaderException ||
                (cause is DataSourceException &&
                    cause.reason == DataSourceException.POSITION_OUT_OF_RANGE)
        }

        /**
         * 沿 cause 链找 HTTP 状态码（类型优先，消息兜底）。
         *
         * 类型分支在真实链路上先命中；消息分支是给单测留的路（见 [STATUS_MESSAGE] 的说明）。
         */
        fun httpStatusOf(error: Throwable?): Int? {
            var status: Int? = null
            anyCause(error) { cause ->
                when (cause) {
                    is HttpDataSource.InvalidResponseCodeException -> {
                        status = cause.responseCode
                        true
                    }

                    else -> {
                        val code = STATUS_MESSAGE.find(cause.message.orEmpty())
                            ?.groupValues?.getOrNull(1)?.toIntOrNull()
                        if (code != null) {
                            status = code
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            return status
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
