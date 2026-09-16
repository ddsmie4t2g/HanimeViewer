package io.github.daisukikaffuchino.han1meviewer.logic.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「同一个人」的身份键回归测试（26.8.3）。
 *
 * 用户报的是：**在女优一览里关注一位女优，再在视频详情页的作者那里关注同一个人，
 * 关注列表里会多出一条一模一样的记录**。根因是身份键直接拿 url 原样比，
 * 而 nJAV 同一张女优页有不止一种写法（域名 / 语言段 / 会变的 `dm###` 前缀 / query 都在变）。
 *
 * 这组测试钉住的就是「哪些写法必须算同一个人」「哪些必须分开」。
 *
 * ⭐ 27.0.1 追加：站点会用**显示名**当另一种 slug（同一个人在别的语言下写成另一个名字），
 * 光比键串认不出来，于是同一个人的关注按钮在两个入口显示相反的状态。
 * 现在由 [ArtistRef.matchesIdentity] 兜住，下面的
 * [njavActressAliasMatchesAcrossLinkVariants] 钉着它。
 */
class ArtistIdentityKeyTest {

    /**
     * 同一位女优、两套写法：站点给出的显示名既是名字、又可能被写进 slug。
     *
     * 这里 `釋アリス`（url 里的写法）与 `释アリス`（另一处给出的显示名）在**键串上不相等**
     * （刻意的：不做繁简转换，见 [nameOnlyEntryDoesNotTransliterateSimplifiedToTraditional]），
     * 但**站点已经明说这两个字符串指的是同一个人** —— 一边的 slug 正好等于另一边的显示名。
     * 所以 [ArtistRef.matchesIdentity] 必须认。
     */
    @Test
    fun njavActressAliasMatchesAcrossLinkVariants() {
        val a = ArtistRef("释アリス", url = "https://njavtv.com/cn/actresses/釋アリス", site = "njav")
        val b = ArtistRef("释アリス", url = "https://njavtv.com/cn/actresses/释アリス", site = "njav")
        // 键串确实不同（不做繁简转换），所以「相等」这条不能退化成真。
        assertNotEquals(a.followKey, b.followKey)
        assertTrue(a.matchesIdentity(b))
        assertTrue(b.matchesIdentity(a))
        // 不相关的人、以及别的站点，绝不能被认成同一个人。
        assertTrue(!a.matchesIdentity(ArtistRef("Other", url = "https://njavtv.com/actresses/Other", site = "njav")))
        assertTrue(!a.matchesIdentity(ArtistRef("释アリス", url = "/model/example", site = "pornhub")))
    }

    /**
     * ⭐ Pornhub 的身份**只用主页路径**，显示名怎么变都算同一个人。
     *
     * 用户 2026-09-16 报的「从关注列表点进去显示『取消关注』、从视频点进去显示『关注』」，
     * 一半的原因就在这里：以前键里还塞了显示名，而显示名是跟着站点语言走的。
     */
    @Test
    fun pornhubProfileIdentityDoesNotDependOnDisplayName() {
        assertEquals(
            key("Tru Kait", "/pornstar/tru-kait", SiteSource.Pornhub),
            key("TRU KAIT Official", "https://www.pornhub.com/pornstar/tru-kait/", SiteSource.Pornhub),
        )
    }

    /** 女优一览卡片给的地址：带会变的 `dm###` 前缀与语言段。 */
    private val fromGallery =
        "https://njavtv.com/dm288/cn/actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3"

    /** 视频详情页「女優」链接给的地址：裸路径。 */
    private val fromVideo =
        "https://njavtv.com/actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3"

    /** 女优页自己翻页时给出的地址：多一段 query。 */
    private val fromPaging =
        "https://njavtv.com/dm8128/cn/actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3?page=3&sort=videos"

    private fun key(name: String, url: String, site: SiteSource = SiteSource.Njav) =
        ArtistRef.keyOf(name, url, site)

    /**
     * 三个入口拿到的其实是同一张女优页 —— 必须算同一个人。
     *
     * ⚠️ 这条如果红了，关注列表就会重新长出重复项（用户报的就是这个）。
     * 名字用**站点 href 里那种写法**（繁体「結」）—— 键取的是 url 里的那一段。
     */
    @Test
    fun njavUrlVariantsOfSameActressShareOneIdentity() {
        val a = key("波多野结衣", fromGallery)
        val b = key("波多野结衣", fromVideo)
        val c = key("波多野结衣", fromPaging)

        assertEquals(a, b)
        assertEquals(b, c)
        assertEquals("actresses/波多野結衣", a.removePrefix("njav|"))
    }

    /**
     * 同一个名字，一处写成百分号编码、一处直接写汉字，也要算同一个人 ——
     * 站点两处都出现过（`href` 编码、`h4` 明文）。
     */
    @Test
    fun percentEncodedAndPlainNameAreTheSameIdentity() {
        assertEquals(
            key("波多野結衣", "https://njavtv.com/actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3"),
            key("波多野結衣", "https://njavtv.com/actresses/波多野結衣"),
        )
    }

    /**
     * ⚠️ **已知边界**：不做繁简转换。
     *
     * 显示名（`h4` 给简体「波多野结衣」）与 `href` 里的名字（繁体「波多野結衣」）不是同一个
     * 字符串。本键不把它们当同一个人 —— 要做这件事得引一张繁简对照表（OpenCC 那个量级），
     * 而**对「关注重复」这个问题不必要**：两个入口给出的 url 里是同一段编码，
     * 归一化之后已经能对上（见上一条测试）。这里把它写成测试，是为了让这个边界**可见**，
     * 而不是被误以为已经处理了。
     */
    @Test
    fun nameOnlyEntryDoesNotTransliterateSimplifiedToTraditional() {
        assertNotEquals(
            key("波多野结衣", fromVideo),
            key("波多野結衣", ""),
        )
    }

    /**
     * ⚠️ **已知边界 2**：只有名字的老记录，与「带 url 的同一个人」**不**合并。
     *
     * 名字-only 的键是 `njav|<名字>`，带 url 的键是 `njav|actresses|<名字>` ——
     * 两边的名字还可能一个简体一个繁体（见上一条）。这是刻意的取舍：
     * 想把它们并起来，就得在「只有名字」的情况下猜一个 url，或者引繁简转换表，
     * 两者都会带来把**两个不同的人**合并的风险（那是删用户数据，比多留一条严重得多）。
     *
     * 实际影响很小：老记录一旦被打开过作者页、走过
     * `FollowedArtistStore.enrich` 补资料，url 就被写进去了，那之后两条会合并成一条。
     */
    @Test
    fun nameOnlyEntryStaysSeparateFromUrlEntry() {
        assertNotEquals(
            key("波多野結衣", fromVideo),
            key("波多野結衣", "", SiteSource.Njav),
        )
        // 但两条「只有名字」的记录（写法一致）必须合并 —— 这是老数据升级时的主路径。
        assertEquals(
            key("波多野結衣", "", SiteSource.Njav),
            key(" 波多野結衣 ", "", SiteSource.Njav),
        )
    }

    /** 不同的人必须分开 —— 这条守的是「别把归一化做成一把大锤」。 */
    @Test
    fun differentActressesStaySeparate() {
        assertNotEquals(
            key("波多野结衣", fromVideo),
            key("JULIA", "https://njavtv.com/actresses/JULIA"),
        )
    }

    /** 榜单 / 分类不是「某个人」：抠不出女优路径时退回名字，绝不与真人撞键。 */
    @Test
    fun rankingPathIsNotAnActress() {
        val ranking = key("排行榜", "https://njavtv.com/cn/actresses/ranking")
        assertTrue(ranking.startsWith("njav|"))
        assertTrue(!ranking.contains("actresses/ranking"))
    }

    /**
     * Pornhub 的两类作者页**不能**合并：`/pornstar/<slug>` 是挂牌演员、
     * `/users/<name>` 是素人上传者，站点上本来就是两种东西。
     */
    @Test
    fun pornhubPornstarAndUserAreDifferent() {
        assertNotEquals(
            key("Tru Kait", "/pornstar/tru-kait", SiteSource.Pornhub),
            key("Tru Kait", "/users/tru-kait", SiteSource.Pornhub),
        )
    }

    /** 同一个 Pornhub 作者页只多了 query / 结尾斜杠时，仍然是一个人。 */
    @Test
    fun pornhubUrlVariantsShareOneIdentity() {
        assertEquals(
            key("Tru Kait", "/pornstar/tru-kait", SiteSource.Pornhub),
            key("Tru Kait", "https://www.pornhub.com/pornstar/tru-kait?o=rl", SiteSource.Pornhub),
        )
    }

    /**
     * `site` 与 `url` 打架时以 **url** 为准（老记录没有 `site` 字段，
     * 而它是在某个站点页面上被写下的）—— 与 [ArtistRef.siteSource] 的口径一致。
     */
    @Test
    fun urlWinsOverStoredSite() {
        assertEquals(
            key("波多野结衣", fromVideo, SiteSource.Njav),
            key("波多野结衣", fromVideo, SiteSource.Hanime1),
        )
    }

    /** 连名字都没有的脏数据不能算出同一个空键（否则会被合并成一条）。 */
    @Test
    fun emptyIdentityIsEmpty() {
        assertEquals("", key("", "", SiteSource.Hanime1))
    }

    /** [ArtistRef.followKey] 与 [ArtistRef.keyOf] 必须永远一致 —— 界面按前者查表。 */
    @Test
    fun followKeyUsesCanonicalIdentity() {
        val ref = ArtistRef(
            name = "波多野结衣",
            url = fromGallery,
            site = SiteSource.Njav.value,
        )
        assertEquals(key("波多野结衣", fromGallery), ref.followKey)
    }
}
