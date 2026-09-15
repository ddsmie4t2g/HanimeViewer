package io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor

import io.github.daisukikaffuchino.utils.LogUtil
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * 图片请求的**失败重试**。（9.0）
 *
 * ## 为什么需要它
 *
 * 26.8 之前，封面/头像一旦请求失败一次就**永久**停留在错误占位图上
 * （`VideoCardItem` 的 `h_chan_load_failed`）—— 用户看到的就是「一部分封面/头像
 * 显示 loadfailed」，而重新退出再进又好了。
 *
 * 这不是某个域名被封（那类问题已经在 [CdnRelayInterceptor] / [ImageRelayInterceptor]
 * 处理掉了），而是**跨洋链路的一次抖动**：
 *
 * | 环节 | 抖动的来源 |
 * |---|---|
 * | 自建中转（美国 VPS） | 跨境 TCP 建连偶发超时、TLS 握手中途被掐 |
 * | 中转回源（→ hembed / fourhoi） | 上游 CDN 偶发 5xx |
 * | wsrv.nl 兜底 | 第三方限流、偶发 502 |
 *
 * 一张封面 50–70 KB，重试成本极低；而失败一次的代价是「用户以为这张图坏了」。
 * 所以这里对**幂等的 GET** 做有限重试。
 *
 * ## 判定规则（刻意保守）
 *
 * - **只重试 GET**：POST 之类重放可能产生副作用，图片链路全是 GET，没有例外。
 * - **重试 [IOException]**（连接被掐、超时、TLS 失败）与 **5xx**（服务器/中转那一刻不舒服）。
 * - **不重试 4xx**：403 是「链接签名过期」、404 是「这张图真的没了」，
 *   重试只会把失败结论推迟几百毫秒，还多占一条连接。
 * - **响应式取消优先**：Composable 被回收（快速滚动）时 OkHttp 会取消 call，
 *   这时必须立刻放弃，否则滚动越猛积压的重试越多。
 *
 * ## 为什么装在**最外层**
 *
 * 见 [io.github.daisukikaffuchino.han1meviewer.logic.network.ImageNetworkClient]：
 * 它排在 [CdnRelayInterceptor] 之**前**，所以每次重试都会重新走一遍
 * 「直连还是中转」的判定 —— 第一次直连撞墙失败会把该 host 记为
 * [io.github.daisukikaffuchino.han1meviewer.logic.network.CdnRelay.isKnownDead]，
 * 于是重试**直接走中转**，不会又白等一次 RST。
 *
 * ⚠️ 重试次数别调大：这是「跨过一次抖动」，不是「一直等到通」。
 * 真不通的域名（被封）重试多少次都不通，只会让错误晚几百毫秒出现。
 */
class ImageRetryInterceptor(
    /** 额外重试次数（不含首次）。默认 2 → 最多 3 次尝试。 */
    private val maxRetries: Int = 2,
    /** 首次退避毫秒数，第 n 次退避 = [baseDelayMs] × n（默认 300 / 600）。 */
    private val baseDelayMs: Long = 300L,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // 只重试幂等的 GET。图片链路全是 GET，这条判断同时挡住将来被误接到别处。
        if (!request.method.equals("GET", ignoreCase = true)) return chain.proceed(request)

        var lastError: IOException? = null
        for (attempt in 0..maxRetries) {
            // 界面已经不要这张图了（快速滚动 / 离开页面）→ 立即放弃。
            if (chain.call().isCanceled()) {
                throw lastError ?: IOException("canceled while loading image")
            }
            try {
                val response = chain.proceed(request)
                // 4xx 与 2xx/3xx 都直接返回；只有 5xx 值得再试一次。
                if (response.code < 500 || attempt == maxRetries) return response
                // 失败的响应体必须先关掉，否则连接泄漏。
                response.close()
                lastError = null
            } catch (e: IOException) {
                lastError = e
                if (attempt == maxRetries) throw e
            }
            backoff(attempt + 1)
        }
        // 只有「全是 5xx」这一条路径能走出循环；此时把最后一次的响应抛出去不合适
        // （已 close），给一个明确的异常。
        throw lastError ?: IOException("image request failed after $maxRetries retries")
    }

    private fun backoff(round: Int) {
        val delay = baseDelayMs * round
        try {
            Thread.sleep(delay)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LogUtil.w(TAG, "重试等待被打断：${e.message}")
            throw IOException("interrupted while retrying image request", e)
        }
    }

    private companion object {
        const val TAG = "ImageRetry"
    }
}
