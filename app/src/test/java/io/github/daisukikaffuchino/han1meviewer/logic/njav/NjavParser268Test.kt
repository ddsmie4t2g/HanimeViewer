package io.github.daisukikaffuchino.han1meviewer.logic.njav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 26.8 nJAV 大修的回归测试：真实导航栏目、女优页资料头、无码判定。
 *
 * 夹具是 **2026-09-14 从线上抓下来的真实结构**（按要求删减，不改写）。
 * 这一批的价值在于「编译期零提示」：栏目路径写错、资料头选择器写错，
 * 编译器都不会吭声，只有把这些断言钉住才不会静默回归。
 */
class NjavParser268Test {

    // ── 首页栏目 = 站点真实导航里的影片类栏目 ─────────────────────────────

    @Test
    fun `首页栏目与站点真实导航一致`() {
        val paths = NjavParser.HOME_SECTIONS.map { it.second }
        assertEquals(
            listOf(
                "new", "release", "uncensored-leak", "chinese-subtitle",
                "weekly-hot", "today-hot", "monthly-hot", "genres/VR",
            ),
            paths,
        )
    }

    @Test
    fun `不被广告栏目污染`() {
        val paths = NjavParser.HOME_SECTIONS.map { it.second }
        // 色色主播 / 直播 / 漫画 / 换量互链都不是影片分类，用户明确点名不要。
        listOf("clive", "klive", "mycomic", "site/123av", "site/njav").forEach { banned ->
            assertTrue("首页栏目不该包含 $banned", paths.none { it.contains(banned) })
        }
    }

    @Test
    fun `更多标记支持真实路径与旧别名`() {
        // 新：标记就是真实路径
        assertEquals("uncensored-leak", NjavParser.pathForMarker("uncensored-leak"))
        assertEquals("genres/VR", NjavParser.pathForMarker("genres/VR"))
        // 旧：老版本存下来的中文别名仍要能翻译（否则点「更多」静默退回默认排序）
        assertEquals("new", NjavParser.pathForMarker("日本AV"))
        assertEquals("weekly-hot", NjavParser.pathForMarker("他們在看"))
        assertNull(NjavParser.pathForMarker(""))
        assertNull(NjavParser.pathForMarker("不认识的东西"))
    }

    // ── 女优页资料头（身材 / 生日）──────────────────────────────────────

    /** 有资料的女优（结构照 2026-09-14 线上页面，数据取自用户截图里的 JULIA）。 */
    private val profileHtml = """
        <html><body>
        <h1 class="text-center text-2xl text-nord4 mb-6">JULIA的 AV 影片库</h1>
        <div x-init="..." class="flex justify-center items-center space-x-4 lg:space-x-6 mb-6 p-6 rounded-md bg-norddark hero-pattern">
            <div class="bg-nord9 text-4xl text-nord4 rounded-full w-24 h-24">
                <div class="flex flex-col justify-center content-center h-full text-center">J</div>
            </div>
            <div class="font-medium text-lg leading-6">
                <h4 class="text-nord6">JULIA</h4>
                <div class="mt-2 text-sm xs:text-base text-nord9">
                    <p>158cm / 40J - 22 - 33</p>
                    <p>1987-05-25 （39岁）</p>
                </div>
                <div><button class="...">收藏</button></div>
            </div>
        </div>
        </body></html>
    """.trimIndent()

    /** 没资料的女优：站点只留空的 `<p></p>`（这是绝大多数女优的真实形态）。 */
    private val emptyProfileHtml = """
        <html><body>
        <h1 class="text-center text-2xl text-nord4 mb-6">持野蓬的 AV 影片库</h1>
        <div x-init="..." class="flex justify-center items-center space-x-4 mb-6 p-6 rounded-md bg-norddark hero-pattern">
            <div class="bg-nord9 text-4xl text-nord4 rounded-full w-24 h-24">
                <div class="flex flex-col justify-center content-center h-full text-center">持</div>
            </div>
            <div class="font-medium text-lg leading-6">
                <h4 class="text-nord6">持野蓬</h4>
                <div class="mt-2 text-sm xs:text-base text-nord9">
                    <p> </p>
                    <p> </p>
                </div>
            </div>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `资料头解析出身材与生日`() {
        val profile = NjavParser.actressProfile(profileHtml)
        assertNotNull(profile)
        assertEquals("JULIA", profile!!.name)
        assertEquals("158cm / 40J - 22 - 33", profile.measurements)
        assertTrue("生日应包含日期", profile.birthday.contains("1987-05-25"))
        assertTrue("年龄原样保留", profile.birthday.contains("39"))
    }

    @Test
    fun `没资料的女优解析出空字段而不是假数据`() {
        val profile = NjavParser.actressProfile(emptyProfileHtml)
        assertNotNull(profile)
        assertEquals("持野蓬", profile!!.name)
        assertEquals("", profile.measurements)
        assertEquals("", profile.birthday)
    }

    @Test
    fun `没有资料头结构时返回 null`() {
        assertNull(NjavParser.actressProfile("<html><body><h1>x</h1></body></html>"))
    }

    @Test
    fun `简介里的数字不会被当成身材`() {
        val html = """
            <div class="hero-pattern">
                <h4 class="text-nord6">某人</h4>
                <div class="mt-2 text-nord9">
                    <p>身高 158 公分，三围 40 - 22 - 33</p>
                    <p>1987-05-25 （39岁）</p>
                </div>
            </div>
        """.trimIndent()
        val profile = NjavParser.actressProfile(html)
        assertNotNull(profile)
        // 没有 `cm /` 这种形态就不认身材 —— 宁可空着，也别把简介里的数字当三围。
        assertEquals("", profile!!.measurements)
        assertTrue(profile.birthday.contains("1987-05-25"))
    }

    // ── 无码判定 ────────────────────────────────────────────────────────

    @Test
    fun `无码片的 slug 能被识别`() {
        val html = """
            <div class="thumbnail group">
                <a href="https://njavtv.com/gvh-879-uncensored-leak" alt="gvh-879-uncensored-leak">
                    <img class="lozad w-full" data-src="https://fourhoi.com/gvh-879-uncensored-leak/cover-t.jpg">
                </a>
                <a href="x"><span class="absolute bottom-1 right-1">1:58:48</span></a>
                <div class="my-2"><a href="x">GVH-879 标题</a></div>
            </div>
        """.trimIndent()
        val list = NjavParser.videoList(html)
        assertEquals(1, list.size)
        assertTrue("uncensored-leak 应判为无码", list.first().isUncensored)
    }

    @Test
    fun `普通片不判为无码`() {
        val html = """
            <div class="thumbnail group">
                <a href="https://njavtv.com/gvh-879" alt="gvh-879">
                    <img class="lozad w-full" data-src="https://fourhoi.com/gvh-879/cover-t.jpg">
                </a>
                <a href="x"><span class="absolute bottom-1 right-1">1:58:48</span></a>
                <div class="my-2"><a href="x">GVH-879 标题</a></div>
            </div>
        """.trimIndent()
        val list = NjavParser.videoList(html)
        assertEquals(1, list.size)
        assertTrue("普通片不该标无码", !list.first().isUncensored)
    }

    @Test
    fun `角标写着无码也算`() {
        val html = """
            <div class="thumbnail group">
                <a href="https://njavtv.com/abc-123" alt="abc-123">
                    <img class="lozad w-full" data-src="https://fourhoi.com/abc-123/cover-t.jpg">
                </a>
                <a href="x"><span class="absolute bottom-1 left-1">無修正</span></a>
                <a href="x"><span class="absolute bottom-1 right-1">2:00:00</span></a>
                <div class="my-2"><a href="x">ABC-123 标题</a></div>
            </div>
        """.trimIndent()
        val list = NjavParser.videoList(html)
        assertEquals(1, list.size)
        assertTrue("角标写了無修正也算无码", list.first().isUncensored)
    }

    // ── 排序 / 筛选的 URL 契约 ──────────────────────────────────────────

    @Test
    fun `女优页排序与筛选拼进原生 query`() {
        val path = "actresses/test"
        assertEquals(
            "https://njavtv.com/cn/actresses/test",
            NjavNetwork.actressUrl(path, 1, null, null),
        )
        assertEquals(
            "https://njavtv.com/cn/actresses/test?page=2&sort=views&filters=individual",
            NjavNetwork.actressUrl(path, 2, "views", "individual"),
        )
        assertEquals(
            "https://njavtv.com/cn/actresses/test?sort=saved",
            NjavNetwork.actressUrl(path, 1, "saved", null),
        )
    }
}
