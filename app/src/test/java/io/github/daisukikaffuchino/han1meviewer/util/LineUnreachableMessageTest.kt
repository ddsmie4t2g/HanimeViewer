package io.github.daisukikaffuchino.han1meviewer.util

import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.network.LineUnreachableException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.ExecutionException

/**
 * 「线路不可达」必须落到**它自己那条文案**上 —— 26.9.19 引入。
 *
 * ## 为什么这条映射值得单测
 *
 * `PlaybackLoadErrorPolicy` 只决定「重试几次」，而用户**唯一能看到的东西**是这一句文案
 * （见 `ExoPlaybackEngine.describePlaybackError` ⇒ `SonnerToast`）。判错的后果是
 * 「转圈少了，但用户仍然不知道该做什么」—— 修复只做了一半。
 *
 * 两个方向都要钉住：
 *
 * | 方向 | 后果 |
 * |---|---|
 * | 抢走别的异常（把普通 RST 也报成「线路不可达」） | 用户被引导去开代理，而其实只是抖了一下 —— 白折腾 |
 * | 自己落进 `home_error_connection_reset`（「网络不稳定」） | 用户看不出「要开代理」，修了等于没修 |
 *
 * ⚠️ 这里只测纯函数（不碰 `LogUtil`、不碰任何 Android 运行时）：`toNetworkErrorMessageRes`
 * 只做类型判断与消息匹配，返回的 `R.string.*` 在 JVM 单测里是可读的普通常量。
 */
class LineUnreachableMessageTest {

    @Test
    fun directExceptionMapsToItsOwnMessage() {
        assertEquals(
            "类型匹配那条路（非播放链路会走到）",
            R.string.home_error_line_unreachable,
            LineUnreachableException("vdownload.hembed.com").toNetworkErrorMessageRes(),
        )
    }

    /**
     * ⭐ 真实生效的那条路径。
     *
     * media3 1.10 的 `OkHttpDataSource` 用 `enqueue + SettableFuture.get()`，把回调失败包成
     * `IOException(ExecutionException(真异常))`，且外层的 message 就是 `cause.toString()` ——
     * 所以**类型匹配命中不了**，全靠消息里的 `line-unreachable` 兜住。这一条要是断了，
     * 用户看到的就是「网络不稳定」，而不是「请开代理」。
     */
    @Test
    fun wrappedByMedia3StillMapsToTheRightMessage() {
        val wrapped: IOException =
            IOException(ExecutionException(LineUnreachableException("vdownload.hembed.com")))
        assertEquals(
            "外层是普通 IOException，只能靠消息匹配",
            R.string.home_error_line_unreachable,
            wrapped.toNetworkErrorMessageRes(),
        )
    }

    /** 反向：普通的 RST 必须**留在原来那条文案**上，不能被新分支抢走。 */
    @Test
    fun plainConnectionResetIsNotHijacked() {
        val reset: IOException = IOException(ExecutionException(SocketException("Connection reset")))
        assertEquals(
            "抖一下就报「线路不可达」会把用户引去做无用功",
            R.string.home_error_connection_reset,
            reset.toNetworkErrorMessageRes(),
        )
    }
}
