package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.logic.model.SubscriptionItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **本地关注** —— 给没有订阅接口的站点（Pornhub / nJAV）用。
 *
 * hanime 的作者有服务端订阅（见 [NetworkRepo.subscribeArtist]，靠页面里的
 * `input[name=subscribe-status]` 拿到 `userId` / `artistId`），另外两个数据源没有：
 * 站点的订阅要登录，接口也没公开。所以这里做一份存在本机的关注表。
 *
 * 它同时是**关注列表**的数据源：订阅页会把本地关注的人一起画出来
 * （见 `SubscriptionScreen`），点一下进**作者页**（26.6.5 起三站一律进作者页，
 * 谁能拿到「只属于该作者」的作品由 [ArtistRef.hasRealArtistPage] 说明）。
 * —— 也就是"看该作者的作品"。
 *
 * 落盘位置：[SettingsRepository.followedArtistsJson]（一个 JSON 字符串，
 * 与 `pinnedSearchesJson` 同一个套路）。
 *
 * ⚠️ **字段只增不改、且都要有默认值**：这是用户设备上已经存在的数据，
 * 加字段时老记录必须还能读出来（`ignoreUnknownKeys` 只解决"多字段"这一半，
 * 新加的字段必须给默认值才能解决"少字段"那一半）。
 */
object FollowedArtistStore {

    /**
     * @param name 显示名（也是搜索时的兜底关键词）
     * @param avatar 头像地址（可能为空：站点有的作者不给头像）
     * @param url 作者主页地址，用来认身份；为空时退回用名字
     * @param site 数据源 `SiteSource.value`。**26.6.2 的老记录没有这个字段**，
     *   读出来是空串 —— 由 [ArtistRef.siteSource] 按 [url] 反推，不要在这里强行补默认值。
     * @param genre / [videoCount] / [subscriberCount] 只为把作者页头部画完整，
     *   缺失不影响关注本身。
     */
    @Serializable
    data class Item(
        val name: String,
        val avatar: String = "",
        val url: String = "",
        val site: String = "",
        val genre: String = "",
        val videoCount: String = "",
        val subscriberCount: String = "",
        /** hanime 兜底搜索用的类型检索键（见 [ArtistRef.genreKey]）。 */
        val genreKey: String = "",
    ) {
        /** 身份键：**优先主页地址** —— 只按名字，跨站同名作者会撞在一起。 */
        val key: String get() = url.trim().ifEmpty { name.trim() }

        fun toArtistRef(): ArtistRef = ArtistRef(
            name = name,
            avatar = avatar,
            url = url,
            genre = genre,
            videoCount = videoCount,
            subscriberCount = subscriberCount,
            site = site,
            genreKey = genreKey,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 当前关注的全部作者（按关注顺序）。 */
    val all: List<Item>
        get() = runCatching {
            json.decodeFromString<List<Item>>(SettingsRepository.followedArtistsJson)
        }.getOrDefault(emptyList())

    /** 关注页要的形态（作者页头部 / 抽屉入口都用它）。 */
    val asArtistRefs: List<ArtistRef>
        get() = all.map { it.toArtistRef() }

    fun isFollowed(key: String): Boolean {
        val k = key.trim()
        if (k.isEmpty()) return false
        return all.any { it.key == k }
    }

    /** 关注页要的形态（订阅页的作者格子用的就是它）。 */
    val asSubscriptionItems: List<SubscriptionItem>
        get() = all.map { SubscriptionItem(artistName = it.name, avatar = it.avatar) }

    /**
     * 关注 / 取关，返回**新状态**（`true` = 现在已关注）。
     *
     * 取关按身份键删，顺带把「同一身份但名字写法变了」的旧记录也清掉，
     * 不然会出现「点已关注，但列表里还留着一个」。
     */
    suspend fun toggle(ref: ArtistRef): Boolean {
        val k = ref.followKey
        val list = all
        val existing = list.any { it.key == k }
        val updated = if (existing) {
            list.filterNot { it.key == k }
        } else {
            // 已关注过但换了数据源/补齐了资料时，以最后一次看到的为准 ——
            // 保留旧记录只会让作者页头部一直显示过时的作品数。
            list.filterNot { it.key == k } + ref.toFollowedItem()
        }
        save(updated)
        return !existing
    }

    /**
     * 兼容入口：只给「手上只有名字/头像」的老调用方用（例如详情页的快速关注）。
     * 新代码请直接传 [ArtistRef]。
     */
    suspend fun toggle(url: String, name: String, avatar: String = ""): Boolean =
        toggle(ArtistRef(name = name, avatar = avatar, url = url))

    private suspend fun save(list: List<Item>) {
        SettingsRepository.setFollowedArtistsJson(json.encodeToString(list))
    }
}
