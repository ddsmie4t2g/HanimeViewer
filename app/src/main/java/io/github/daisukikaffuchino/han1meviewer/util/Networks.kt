package io.github.daisukikaffuchino.han1meviewer.util

import com.google.common.util.concurrent.ListenableFuture
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.network.LineUnreachableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import javax.net.ssl.SSLHandshakeException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun <R> ListenableFuture<R>.await(): R {
    // Fast path
    if (isDone) {
        try {
            return get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
    return suspendCancellableCoroutine { cancellableContinuation ->
        addListener(
            {
                try {
                    cancellableContinuation.resume(get())
                } catch (throwable: Throwable) {
                    val cause = throwable.cause ?: throwable
                    when (throwable) {
                        is java.util.concurrent.CancellationException ->
                            cancellableContinuation.cancel(cause)

                        else -> cancellableContinuation.resumeWithException(cause)
                    }
                }
            },
            DirectExecutor
        )

        cancellableContinuation.invokeOnCancellation {
            cancel(false)
        }
    }
}

/**
 * Suspend extension that allows suspend [Call] inside coroutine.
 */
suspend fun Call.await(): Response {
    return suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        })
        continuation.invokeOnCancellation { cancel() }
    }
}

/**
 * Run suspend catching
 *
 * @param block suspend block
 */
inline fun <R> runSuspendCatching(block: () -> R): Result<R> {
    return try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (e: Throwable) {
        Result.failure(e)
    }
}

private data object DirectExecutor : Executor {

    override fun execute(command: Runnable) {
        command.run()
    }
}

/**
 * 将首页加载异常映射为对应的错误提示字符串资源。
 *
 * 优先根据异常类型判断常见网络问题，必要时回退到异常信息中的关键字匹配。
 *
 * @receiver 首页加载过程中抛出的异常
 * @return 错误提示的字符串资源 ID
 */
fun Throwable.toNetworkErrorMessageRes(): Int {
    val rawMessage = message.orEmpty().lowercase()
    return when {
        // ⭐ 26.9.19：这一档必须在**最前面**。
        //
        // 它是唯一一条「由客户端自己判定的确定结论」（直连已验死 + 自建中转不可用），
        // 其余判据都是对远端响应或线路抖动的**猜测**。命中它时用户该做的事很明确 ——
        // 开代理 —— 所以不能被 `timeout` / `connection reset` 之类更模糊的分支抢走。
        //
        // ⚠️ 类型匹配在播放链路里通常**命中不了**：media3 的 `OkHttpDataSource` 会把失败
        // 包成 `IOException(ExecutionException(真异常))`，最外层是个普通 `IOException`。
        // 好在那个 `IOException` 的 message 就是 `cause.toString()`（含 `line-unreachable`），
        // 所以下面那条消息兜底才是真实生效的那条 —— 两条都留着，缺一不可。
        this is LineUnreachableException ||
                rawMessage.contains("line-unreachable") -> {
            R.string.home_error_line_unreachable
        }

        this is UnknownHostException ||
                rawMessage.contains("unable to resolve host") ||
                rawMessage.contains("no address associated with hostname") -> {
            R.string.home_error_dns
        }

        this is SocketTimeoutException || rawMessage.contains("timeout") -> {
            R.string.home_error_timeout
        }

        this is SSLHandshakeException ||
                rawMessage.contains("ssl") ||
                rawMessage.contains("certificate") -> {
            R.string.home_error_ssl
        }

        this is ConnectException || rawMessage.contains("failed to connect") -> {
            R.string.home_error_connect
        }

        this is SocketException && rawMessage.contains("connection reset") -> {
            R.string.home_error_connection_interrupted
        }

        rawMessage.contains("connection reset") -> {
            R.string.home_error_connection_reset
        }

        rawMessage.contains("403") -> {
            R.string.home_error_forbidden
        }

        rawMessage.contains("404") -> {
            R.string.home_error_not_found
        }

        rawMessage.contains("500") || rawMessage.contains("502") ||
                rawMessage.contains("503") || rawMessage.contains("504") -> {
            R.string.home_error_server_unavailable
        }

        else -> {
            R.string.home_error_generic
        }
    }
}
