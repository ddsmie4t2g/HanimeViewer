package io.github.daisukikaffuchino.han1meviewer.logic.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * [CdnRelay.isConnectionLevelFailure] 的分类边界。
 *
 * ## 为什么这个纯函数值得单测
 *
 * 它决定「要不要在本次会话里认定中转不可达」，两个方向判错的代价都很实际：
 *
 * | 判错方向 | 后果 |
 * |---|---|
 * | **判宽**（把上游断连当中转下线） | 用户视频再也放不出来 —— 每个请求都不再尝试中转，只能杀进程重启 |
 * | **判窄**（漏掉连接失败） | 每个请求都白绕一次连不上的中转；首页十几个并发叠起来就是白屏 |
 *
 * 边界靠肉眼盯不住，所以钉在这里。
 */
class CdnRelayConnectionFailureTest {

    /** 连接建立阶段的失败 —— 只有这些才说明「中转这台机器不可达」。 */
    @Test
    fun connectionLevelFailuresAreRecognised() {
        assertTrue(
            "端口拒绝（中转进程不在）—— 26.9.18 中转下线时实测到的就是它",
            CdnRelay.isConnectionLevelFailure(ConnectException("Connection refused")),
        )
        assertTrue(
            "connect 超时（IP 被临时封）",
            CdnRelay.isConnectionLevelFailure(SocketTimeoutException("connect timed out")),
        )
        assertTrue(
            "路由不可达",
            CdnRelay.isConnectionLevelFailure(NoRouteToHostException("no route to host")),
        )
        assertTrue(
            "域名解析失败",
            CdnRelay.isConnectionLevelFailure(UnknownHostException("dns failed")),
        )
        assertTrue(
            "TLS 握手失败（证书被换 / 被 RST）",
            CdnRelay.isConnectionLevelFailure(SSLException("handshake failed")),
        )
        assertTrue(
            "TLS 握手的子类也要算",
            CdnRelay.isConnectionLevelFailure(SSLHandshakeException("bad cert")),
        )
    }

    /**
     * 数据阶段的失败 —— 中转本身是活的，**不能**把它判死。
     *
     * 这一组是整个功能的安全阀：中转转发一个视频分片时，上游把连接掐了是很常见的
     * （`relay.py` 的日志里有一批 `stream error SSLEOFError`），若把它们也当成
     * 「中转不可达」，用户会莫名其妙地彻底看不了视频。
     */
    @Test
    fun dataTransferFailuresAreNotTreatedAsUnreachable() {
        assertFalse(
            "上游把连接掐了（中转活着）",
            CdnRelay.isConnectionLevelFailure(SocketException("Connection reset")),
        )
        assertFalse(
            "流提前结束",
            CdnRelay.isConnectionLevelFailure(IOException("unexpected end of stream")),
        )
        assertFalse(
            "被中断",
            CdnRelay.isConnectionLevelFailure(InterruptedIOException("interrupted")),
        )
        assertFalse(
            "完全无关的异常",
            CdnRelay.isConnectionLevelFailure(IllegalStateException("boom")),
        )
    }

    /**
     * ⚠️ 子类关系别搞错：`SocketTimeoutException` **是** `InterruptedIOException` 的子类。
     *
     * 收白名单时如果按「先收父类、再排除」的写法，很容易把超时一起排掉；
     * 这里用 `is` 逐个匹配就没这个问题 —— 这个用例把结论钉住。
     */
    @Test
    fun subtypeOrderDoesNotLeak() {
        assertTrue(
            "超时要算（connect 与 read 阶段在 OkHttp 里分不出来，权衡后都收）",
            CdnRelay.isConnectionLevelFailure(SocketTimeoutException()),
        )
        assertFalse(
            "但父类本身不算",
            CdnRelay.isConnectionLevelFailure(InterruptedIOException()),
        )
    }
}
