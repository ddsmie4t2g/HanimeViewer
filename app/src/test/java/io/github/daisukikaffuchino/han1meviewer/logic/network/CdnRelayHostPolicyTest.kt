package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.utils.LogUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [CdnRelay] 里那几张**按域名查表**的纯函数的边界。
 *
 * 这几张表是「哪些域名走中转」「哪些跳过直连」「走中转时补哪个 Referer」的唯一来源，
 * 手改一个字符就会静默改变行为 —— 比如 `phncdn.com` 少一条后缀匹配，
 * 表现就是「Pornhub 封面成片 loadfailed」，而且不会报任何错。
 * 所以把边界钉住。
 */
class CdnRelayHostPolicyTest {

    private var logWasEnabled = true

    /**
     * ⚠️ 单测跑在 JVM 上，`android.util.Log` 是 stub —— **一旦真被调用就抛
     * `RuntimeException: Method i in android.util.Log not mocked`**（不是断言失败，
     * 看着像测试写错了，其实是环境问题）。
     *
     * 而 `LogUtil.enabled` 默认取 `BuildConfig.DEBUG`；单测走的是 debug 变体，
     * 所以它是 `true`，`log { }` 块会真的执行。这里用 LogUtil **自己的开关**关掉它 ——
     * 比给测试加 Android 桩或开 `isReturnDefaultValues` 干净，且完全不碰生产代码。
     */
    @Before
    fun silenceLogs() {
        logWasEnabled = LogUtil.enabled
        LogUtil.enabled = false
    }

    @After
    fun restoreLogs() {
        LogUtil.enabled = logWasEnabled
    }

    @Test
    fun relayHostsMatchSubdomains() {
        // 实战域名几乎都是子域：vdownload.hembed.com / pix-1.phncdn.com / ev-h.phncdn.com。
        assertTrue(CdnRelay.isRelayHost("vdownload.hembed.com"))
        assertTrue(CdnRelay.isRelayHost("pix-1.phncdn.com"))
        assertTrue(CdnRelay.isRelayHost("ev-h.phncdn.com"))
        assertTrue(CdnRelay.isRelayHost("fourhoi.com"))
        assertTrue(CdnRelay.isRelayHost("www.fourhoi.com"))
        // 大小写不敏感（调用方虽已 lowercase，但表本身不该依赖这一点）。
        assertTrue(CdnRelay.isRelayHost("PHNCDN.COM"))
    }

    @Test
    fun relayHostsDoNotOverreach() {
        // 后缀匹配最容易犯的错：把「以它结尾的别的域名」也收进来。
        assertFalse(CdnRelay.isRelayHost("phncdn.com.evil.net"))
        assertFalse(CdnRelay.isRelayHost("notphncdn.com"))
        // 这三个是**故意**不进表的：surrit 走内置 IP 直连、njavtv 是主站、
        // wsrv.nl 是兜底中转自己（它绝不能再被中转一次）。
        assertFalse(CdnRelay.isRelayHost("surrit.com"))
        assertFalse(CdnRelay.isRelayHost("njavtv.com"))
        assertFalse(CdnRelay.isRelayHost("wsrv.nl"))
        assertFalse(CdnRelay.isRelayHost("empty"))
    }

    @Test
    fun onlyPornhubSkipsDirectAttempt() {
        // mustRelay = 跳过直连、直接中转。
        // 只有「实测在大陆任何网络下直连都不会成功」的域名可以进来。
        assertTrue(CdnRelay.mustRelay("pornhub.com"))
        assertTrue(CdnRelay.mustRelay("www.pornhub.com"))
        assertTrue(CdnRelay.mustRelay("pix-1.phncdn.com"))
        // ⚠️ hembed / fourhoi 在海外直连是**通的**，搬进 mustRelay 会让海外用户多绕一趟美国。
        assertFalse(CdnRelay.mustRelay("vdownload.hembed.com"))
        assertFalse(CdnRelay.mustRelay("fourhoi.com"))
    }

    @Test
    fun refererOnlyForPornhubCdn() {
        // Pornhub 的 .ts 分片不带 Referer 会 404。中转在线时由服务器按中转 URL 的 ?ref= 补；
        // 中转下线后只能由客户端自己补（见 PlaybackHeaders.pornhubSegmentReferer）。
        assertEquals("https://www.pornhub.com/", CdnRelay.refererFor("phncdn.com"))
        assertEquals("https://www.pornhub.com/", CdnRelay.refererFor("pix-1.phncdn.com"))
        assertEquals("https://www.pornhub.com/", CdnRelay.refererFor("EV-H.PHNCDN.COM"))
        assertEquals("https://www.pornhub.com/", CdnRelay.refererFor("phncdn.com."))
        // 主站与另两个域名**不该**补：实测主清单/子清单不要 Referer，带了是新变量。
        assertNull(CdnRelay.refererFor("pornhub.com"))
        assertNull(CdnRelay.refererFor("www.pornhub.com"))
        assertNull(CdnRelay.refererFor("fourhoi.com"))
        assertNull(CdnRelay.refererFor("vdownload.hembed.com"))
    }

    @Test
    fun directDeadMarkIsReversible() {
        // ⭐ 这个反向操作是新加的，它承载一条真实场景：用户中途挂上代理 / VPN 之后
        //    直连就通了。没有这条清除，「该 host 直连必死」会被永远记住，
        //    之后每个请求都先白绕一趟中转（而中转可能恰恰不在）。
        val host = "img.reversibility.test"
        assertFalse(CdnRelay.isKnownDead(host))

        CdnRelay.markKnownDead(host)
        assertTrue(CdnRelay.isKnownDead(host))
        assertTrue("大小写应不敏感", CdnRelay.isKnownDead(host.uppercase()))

        CdnRelay.markDirectAlive(host)
        assertFalse(CdnRelay.isKnownDead(host))
        // 撤销是幂等的：再调一次不该出错，也不该把它重新标上。
        CdnRelay.markDirectAlive(host)
        assertFalse(CdnRelay.isKnownDead(host))
    }
}
