package io.github.daisukikaffuchino.han1meviewer.logic.model

import io.github.daisukikaffuchino.han1meviewer.logic.FollowedArtistStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 「一位作者」的可传递身份。
 *
 * ## 为什么要有这个类
 *
 * 26.6.2 之前，详情页点作者一律跳 `SearchRoute(query = 名字)` —— 对 hanime 是对的
 * （站点本来就没有作者页，服务端订阅之外只有搜索），但另外两个站点都有**真正的作者页**：
 *
 * | 站点 | 作者页 |
 * |---|---|
 * | Pornhub | `/pornstar/<slug>`、`/model/<slug>`（资料头 + 可分页的作品列表） |
 * | nJAV | `/cn/actresses/<编码名>`（女优作品列表页，真分页） |
 *
 * 于是就出现了用户说的那种体验：「点进对应的作者里面全都是视频」——
 * 看到的是一次**按名字的全文搜索**，既不知道作者是谁，也不保证结果只属于他。
 * [ArtistRef] 就是把「点的是谁」这件事完整地带到作者页：[name] 用于显示与兜底搜索，
 * [url] 用于定位站点的作者页，[site] 决定用哪个数据源去取。
 *
 * ## 为什么要序列化
 *
 * 导航路由（[io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.ArtistRoute]）
 * 只接受可序列化参数，所以它整份以 JSON 字符串塞进路由，和 `SearchRoute` 的
 * `advancedSearchJson` 同一个套路。
 *
 * ⚠️ [url] 必须**优先**用来认身份（见 [followKey]）：只按名字，跨站同名作者会撞在一起。
 */
@Serializable
data class ArtistRef(
    val name: String,
    /** 头像直链；站点不给就留空（界面上画占位）。 */
    val avatar: String = "",
    /**
     * 作者主页地址。Pornhub 形如 `/pornstar/tru-kait`（**相对地址**，站点原样给的），
     * nJAV 是绝对地址 `https://njavtv.com/actresses/%E6%8C%81%E9%87%8E%E8%93%AC`。
     */
    val url: String = "",
    /** 分类（Pornhub 的 `Amateur` 之类），拿不到就空。 */
    val genre: String = "",
    /** 「87 Videos」这种**原样文案**，站点给什么显示什么。 */
    val videoCount: String = "",
    /** 「448K Subscribers」这种原样文案。 */
    val subscriberCount: String = "",
    /** 数据源：`SiteSource.value`。为空时按 [url] 猜（老数据兼容，见 [siteSource]）。 */
    val site: String = "",
    /**
     * hanime 兜底搜索用的「类型检索键」。
     *
     * hanime 站点没有作者页，作者页对它是**合成**的：拿名字去搜。而只按名字搜，
     * 短名字会混进一堆无关片子 —— 原来的 `openArtistSearch` 就是这么处理的：
     * 把作者带的分类（如「里番」）映射成搜索用的 genre key 一起带上。
     *
     * 那个映射需要首页的 `List<SearchOption>`（只有 UI 层有），所以在这里存**映射结果**，
     * 由 `VideoRouteActions` 在跳转时填 —— 仓库层就不用再去猜这些文案了。
     */
    val genreKey: String = "",
) {
    /**
     * 关注用的身份键（26.8.3 起是**规范化**的，见 [identityKey]）。
     *
     * ⚠️ 不要退回「url 原样 / 名字」那种写法。用户 2026-09-14 报的
     * 「女优一览关注一次、视频页作者再关注一次，关注列表里出现两个同一个人」
     * 就是它造成的：同一张女优页在站点上有多种写法（`njavtv.com/actresses/<名>`、
     * `/dm288/cn/actresses/<名>`、`/cn/actresses/<名>?sort=`…），
     * 谁出现在哪个入口取决于当时点的是哪个链接，原样比较就必然认成两个人。
     */
    val followKey: String get() = identityKey()

    /**
     * 实际用哪个数据源取作者页。
     *
     * ## ⭐⭐ 为什么这里必须**先看 url 的路径形态**，不能只看 [site]
     *
     * 26.6.2 写进 `followedArtistsJson` 的条目**没有 [site] 字段**（那是 26.6.3 才加的）。
     * 而 Pornhub 的作者 url 是**相对路径**（`/pornstar/tru-kait`，站点原样给的），
     * 里面不含 "pornhub" 字样 —— 只按域名关键词猜，它会掉进 `else` 被当成 hanime，
     * 于是「在 hanime 域名下点一个 Pornhub 关注的人」会跑去 hanime 搜一个欧美名字，
     * 结果就是 404（用户 2026-09-13 报的那个 bug）。
     *
     * 所以判据按可信度排序：
     * 1. url 里的**路径形态**（`/pornstar/`、`/model/`、`/actresses/` …）—— 最可靠，
     *    Pornhub 与 nJAV 的作者页路径不会和 hanime 撞车；
     * 2. url 里的域名关键词；
     * 3. 最后才是存下来的 [site]。
     *
     * ⚠️ 顺序刻意是「url 优先于 site」：url 是站点自己给的原始地址，而 `site` 是**我们**在
     * 关注那一刻用「当时的站点」写下的 —— 用户在 Pornhub 页面上关注、随后切到 hanime，
     * 这个字段本来是准的；但更早的老记录压根没有它。让 url 说话两边都对。
     */
    val siteSource: SiteSource
        get() {
            val path = url.trim().lowercase()
            return when {
                path.contains("/actresses/") || path.contains("njavtv") -> SiteSource.Njav
                path.contains("/pornstar/") || path.contains("/model/") ||
                        path.contains("/pornhub") || path.contains("/channels/") -> SiteSource.Pornhub
                path.contains("/users/") -> SiteSource.Pornhub
                site.isNotBlank() -> SiteSource.fromValue(site)
                else -> SiteSource.Hanime1
            }
        }

    /**
     * 站点**真的**有作者页吗？
     *
     * - `true`：能拿到「只属于这位作者」的作品列表（Pornhub `/pornstar|/model`、nJAV `/actresses`）；
     * - `false`：作者页是**合成**的 —— hanime 站点上没有作者页，Pornhub 的 `/users/…` 上传者页
     *   对游客不可用，这两种情况只能退化成「按名字搜索」，结果里可能混进同名作者。
     *
     * ⭐ 26.6.5 起作者页对**三个站点一律可进**（不再有「点作者跳搜索页」这种分流），
     * 但界面要靠这个属性**如实说明**下面那份列表是怎么来的 —— 宁可说清楚，也不要让人
     * 以为「这就是该作者的全部作品」。
     */
    val hasRealArtistPage: Boolean
        get() {
            val path = url.trim().lowercase()
            return when (siteSource) {
                SiteSource.Pornhub -> path.contains("/pornstar/") || path.contains("/model/")
                SiteSource.Njav -> path.contains("/actresses/")
                SiteSource.Hanime1 -> false
            }
        }

    /** 站点短名（作者卡片上的角标用）。 */
    val siteLabelRes: Int
        get() = when (siteSource) {
            SiteSource.Hanime1 -> io.github.daisukikaffuchino.han1meviewer.R.string.site_badge_hanime
            SiteSource.Njav -> io.github.daisukikaffuchino.han1meviewer.R.string.site_badge_njav
            SiteSource.Pornhub -> io.github.daisukikaffuchino.han1meviewer.R.string.site_badge_pornhub
        }

    fun toFollowedItem(): FollowedArtistStore.Item = FollowedArtistStore.Item(
        name = name,
        avatar = avatar,
        url = url,
        site = siteSource.value,
        genre = genre,
        videoCount = videoCount,
        subscriberCount = subscriberCount,
        genreKey = genreKey,
    )

    /**
     * **规范化的身份键**（26.8.3 新增）。
     *
     * ## 它解决的是什么
     *
     * 关注这件事必须「同一个人在哪儿点都只算一条」。而站点给的作者地址**写法不止一种**：
     *
     * | 入口 | 拿到的地址 |
     * |---|---|
     * | nJAV 视频详情页的「女優」链接 | `https://njavtv.com/actresses/%E6%8C%81...` |
     * | nJAV 女优一览卡片 | `https://njavtv.com/dm288/cn/actresses/%E6%8C%81...` |
     * | nJAV 女优页自己的分页链接 | `…/actresses/%E6%8C%81...?page=2` |
     *
     * 原样比较时它们是三个不同的字符串 ⇒ 关注列表里同一个人出现三次
     * （用户 2026-09-14 报的就是这个）。[identityKey] 把「会变的包装」剥掉，只留站点侧
     * 真正标识这个人的那一小段：
     *
     * - **nJAV**：`actresses/<编码名>` —— 丢掉域名、语言段、会变的 `dm###` 前缀与 query，
     *   再把百分号编码**解回文字**（同一个名字可能一处写成 `%E6%8C%81`、一处直接写汉字）；
     * - **Pornhub / 其它**：`路径::名字` —— 丢掉域名与语言段，但**不**碰路径本身
     *   （Pornhub 的 `/pornstar/<slug>` 与 `/users/<name>` 是两类东西，绝不能合并）；
     * - 没有地址时退回名字。
     *
     * ⚠️ 名字在键里**只当兜底**，不当主键：跨站同名作者靠 [url] 区分（见 [siteSource]）。
     */
    fun identityKey(): String = keyOf(name, url, siteSource)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * [identityKey] 的实现。做成静态函数是为了让 [FollowedArtistStore.Item]
         * 这种「已经不是 [ArtistRef]」的老数据也能算出同一个键。
         */
        fun keyOf(name: String, url: String, site: SiteSource): String {
            val rawUrl = url.trim()
            if (rawUrl.isEmpty() && name.isBlank()) return ""
            val host = runCatching { java.net.URI(rawUrl).host.orEmpty() }.getOrDefault("")
            val nJavUrl = host.contains("njavtv") ||
                    rawUrl.contains("njavtv") ||
                    rawUrl.contains("/actresses/")
            if (site == SiteSource.Njav || nJavUrl) {
                val actressKey = actressKeyOf(rawUrl)
                // nJAV 上「同一个人」也可能只给名字（老记录 / 关注表里只有名字）——
                // 那就直接用归一化后的名字，一样能跟带地址的写法对上。
                return "njav|" + (actressKey ?: normalizeName(name))
            }
            // ⚠️ site 与 url 可能互相矛盾（`site` 是关注那一刻的「当前站点」，
            // 而 url 是站点自己给的）。以 url 为准 —— 与 [siteSource] 的判据一致。
            val pathKey = urlPathKey(rawUrl)
            if (pathKey.isEmpty()) return "${site.value}|" + normalizeName(name)
            return "${site.value}|$pathKey|" + normalizeName(name)
        }

        /**
         * nJAV 女优地址 → `actresses/<解码后的名字>`；不是女优地址就返回 null。
         *
         * 三种写法都要吃得下（见 [identityKey] 的表），并且：
         * - `dm###`（会变的随机数字前缀，`/dm539/` 自己都能 301 到别处）与 `cn` / `en`
         *   这类语言段一律丢掉；
         * - `ranking` / `genres` 不是「某个人」，返回 null 让调用方退回名字。
         */
        private fun actressKeyOf(rawUrl: String): String? {
            if (rawUrl.isEmpty()) return null
            val marker = "/actresses/"
            val where = rawUrl.indexOf(marker)
            if (where < 0) return null
            // 百分号编码是**原样**抄下来的（站点给什么就是什么），所以先解码再归一 ——
            // 同一个名字一处写成 `%E6%8C%81`、一处直接写汉字，两边都落到同一个键上。
            val tail = rawUrl.substring(where + marker.length)
                .substringBefore('?')
                .substringBefore('#')
                .trim('/')
            if (tail.isEmpty()) return null
            val name = runCatching { java.net.URLDecoder.decode(tail, "UTF-8") }
                .getOrDefault(tail)
                .trim()
                .removePrefix("dm")
                .substringAfter('/')
                .trimStart('/')
                .trim()
            if (name.isEmpty()) return null
            // `ranking` / `genres` / `cn` 这些不是「某个人」，交给调用方退回名字。
            if (name.lowercase() in ACTRESS_RESERVED) return null
            return "actresses/" + normalizeName(name)
        }

        /**
         * `https://www.pornhub.com/pornstar/tru-kait?x=1` → `pornstar/tru-kait`。
         *
         * 只丢域名、协议与 query，**不碰路径内容** —— `/pornstar/<slug>` 与 `/users/<name>`
         * 是两类不同的东西，合并了就会把两个人认成一个。
         */
        private fun urlPathKey(rawUrl: String): String {
            if (rawUrl.isEmpty()) return ""
            val path = runCatching { java.net.URI(rawUrl).path.orEmpty() }
                .getOrDefault("")
                .ifEmpty {
                    // 不是绝对地址（Pornhub 给过相对路径）：直接当路径用。
                    rawUrl.substringAfter("://", rawUrl).substringAfter('/', "")
                }
            return path.substringBefore('?').trim('/').lowercase()
        }

        /**
         * 名字归一化：NFC + 去空白 + 小写。
         *
         * 与 [io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavActressCache] 同一套口径
         * （它那边还要按名字查头像，两边不一致就会出现「关注认得出、头像补不上」）。
         * **不做繁简转换**：那需要一张大表，而站点自己的 href 里已经是同一种写法。
         */
        private fun normalizeName(name: String): String = runCatching {
            java.text.Normalizer.normalize(name.trim(), java.text.Normalizer.Form.NFC)
                .lowercase()
                .filterNot { it.isWhitespace() }
        }.getOrDefault(name.trim().lowercase())

        /** nJAV 上不是「某个人」的保留尾段（榜单 / 分类 / 语言段）。 */
        private val ACTRESS_RESERVED = setOf("ranking", "genres", "cn", "en", "tw", "ja")

        fun from(
            artist: HanimeVideo.Artist,
            site: SiteSource,
            genreKey: String = "",
        ): ArtistRef = ArtistRef(
            name = artist.name,
            avatar = artist.avatarUrl,
            url = artist.url,
            genre = artist.genre,
            videoCount = artist.videoCount,
            subscriberCount = artist.subscriberCount,
            site = site.value,
            genreKey = genreKey,
        )

        /** 路由参数用：整份编码成 JSON（见 [io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.ArtistRoute]）。 */
        fun encode(ref: ArtistRef): String = json.encodeToString(ref)

        /**
         * 路由参数用：解不出来返回 null。
         *
         * **不抛异常**是刻意的：这个字符串来自导航参数（进程重建后由导航库还原），
         * 一旦解码失败就崩，用户看到的是「点作者闪退」；返回 null 至少能画个空态。
         */
        fun decodeOrNull(raw: String): ArtistRef? = runCatching {
            json.decodeFromString<ArtistRef>(raw)
        }.getOrNull()
    }
}

/**
 * 作者页头部那份「资料」。
 *
 * 只有站点真的给了才有值：[ArtistRef] 里已有的（名字 / 头像 / 作品数 / 关注者数）先画上去，
 * 再从作者页的 HTML 里补更权威的一份（Pornhub 的作者页带封面图、观看总量、简介）。
 */
data class ArtistProfile(
    val name: String = "",
    val avatarUrl: String = "",
    val coverUrl: String = "",
    val videoCount: String = "",
    val subscriberCount: String = "",
    val viewCount: String = "",
    /**
     * 身材数据，**原样文案**：nJAV 女优页给的是 `158cm / 40J - 22 - 33` 这种一行字符串。
     * 不做结构化解析（身高/罩杯/三围拆开再拼回去，只会把站点哪天改格式变成崩溃）。
     */
    val measurements: String = "",
    /** 生日与年龄，同样原样：`1987-05-25 （39岁）`。 */
    val birthday: String = "",
)

/**
 * 作者页一页的产物。
 *
 * [profile] 只在第一页有值（Pornhub 的作者页把资料头和作品列表放在同一个 HTML 里，
 * 一次请求就能拿到两样）；后续页为 null，界面保留第一页那份。
 *
 * @param siteTotalPages **站点那边这份列表一共有几页**（站点自己的分页口径）。
 *
 *   为什么需要它：作者页头部那句「共 N 部影片」只有 Pornhub / nJAV 的卡片才带，
 *   **hanime 的合成作者页（= 按名字搜索）两者都没有** —— 没有它，分页条就只能
 *   「翻一页长一页」，用户看不到「一共多少页」。hanime 的搜索页是 Laravel 分页，
 *   页码条里直接写着末页号（实测 `search?query=…` 20 页、带分类的简化模板 13 页），
 *   所以这一条能拿到。
 *
 *   ⚠️ 这是**站点页数**，不是应用内页数（应用内一页 12 条，站点一页 30–59 条），
 *   换算见 `ArtistPaging.pagesFromSitePages`。换算要用「实测的站点页条数」，
 *   所以它只是个**上界估计**（末页通常不满），界面上最多多出末页那一格。
 *
 *   拿不到就留 null（nJAV 女优页**没有分页**：`?page=` 只回反爬挑战页；
 *   Pornhub 作者页的 `div.pagination3` 同页混着别的列表的页码，
 *   实测 p2 里能出现 18 / 25 —— 取 max 会串页，宁可不给）。
 */
data class ArtistVideosPage(
    val profile: ArtistProfile? = null,
    val videos: List<HanimeInfo> = emptyList(),
    val siteTotalPages: Int? = null,
)
