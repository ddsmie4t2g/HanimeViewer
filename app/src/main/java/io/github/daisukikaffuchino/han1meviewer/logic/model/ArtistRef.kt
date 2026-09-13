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
) {
    /** 本地关注用的身份键：有主页地址就用地址，没有才退回名字。 */
    val followKey: String get() = url.trim().ifEmpty { name.trim() }

    /**
     * 实际用哪个数据源取作者页。
     *
     * [site] 为空是**老数据的常态**：26.6.2 写进 `followedArtistsJson` 的条目没有这个字段
     * （`Json { ignoreUnknownKeys = true }` 会把它读成默认空串）。这时按 [url] 反推 ——
     * 猜错的后果是「作者页取不到数据」，比「整条关注记录读不出来」轻得多。
     */
    val siteSource: SiteSource
        get() = when {
            site.isNotBlank() -> SiteSource.fromValue(site)
            url.contains("njavtv", ignoreCase = true) -> SiteSource.Njav
            url.contains("pornhub", ignoreCase = true) -> SiteSource.Pornhub
            else -> SiteSource.Hanime1
        }

    /**
     * 这个作者有没有**站点自带的作者页**。
     *
     * hanime 没有（只有服务端订阅 + 搜索），所以它的作者仍然走 `SearchRoute`；
     * 另外两个站点有，才值得进作者页。
     */
    val hasArtistPage: Boolean
        get() = when (siteSource) {
            SiteSource.Pornhub -> url.startsWith("/pornstar/") ||
                    url.startsWith("/model/") ||
                    url.contains("/pornstar/") ||
                    url.contains("/model/")
            SiteSource.Njav -> url.contains("/actresses/")
            SiteSource.Hanime1 -> false
        }

    fun toFollowedItem(): FollowedArtistStore.Item = FollowedArtistStore.Item(
        name = name,
        avatar = avatar,
        url = url,
        site = siteSource.value,
        genre = genre,
        videoCount = videoCount,
        subscriberCount = subscriberCount,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun from(artist: HanimeVideo.Artist, site: SiteSource): ArtistRef = ArtistRef(
            name = artist.name,
            avatar = artist.avatarUrl,
            url = artist.url,
            genre = artist.genre,
            videoCount = artist.videoCount,
            subscriberCount = artist.subscriberCount,
            site = site.value,
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
)

/**
 * 作者页一页的产物。
 *
 * [profile] 只在第一页有值（Pornhub 的作者页把资料头和作品列表放在同一个 HTML 里，
 * 一次请求就能拿到两样）；后续页为 null，界面保留第一页那份。
 */
data class ArtistVideosPage(
    val profile: ArtistProfile? = null,
    val videos: List<HanimeInfo> = emptyList(),
)
