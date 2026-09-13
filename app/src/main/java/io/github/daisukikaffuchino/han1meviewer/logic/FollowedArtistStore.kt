package io.github.daisukikaffuchino.han1meviewer.logic

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
 * （见 `SubscriptionScreen`），点一下就是按名字搜索 —— 也就是"看该作者的作品"
 * （[io.github.daisukikaffuchino.han1meviewer.ui.screen.video.VideoRouteActions.openArtistSearch]）。
 *
 * 落盘位置：[SettingsRepository.followedArtistsJson]（一个 JSON 字符串，
 * 与 `pinnedSearchesJson` 同一个套路）。
 */
object FollowedArtistStore {

    /**
     * @param name 显示名（也是搜索时用的关键词）
     * @param avatar 头像地址（可能为空：站点有的作者不给头像）
     * @param url 作者主页地址，用来认身份；为空时退回用名字
     */
    @Serializable
    data class Item(
        val name: String,
        val avatar: String = "",
        val url: String = "",
    ) {
        /** 身份键：**优先主页地址** —— 只按名字，跨站同名作者会撞在一起。 */
        val key: String get() = url.trim().ifEmpty { name.trim() }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 当前关注的全部作者（按关注顺序）。 */
    val all: List<Item>
        get() = runCatching {
            json.decodeFromString<List<Item>>(SettingsRepository.followedArtistsJson)
        }.getOrDefault(emptyList())

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
    suspend fun toggle(url: String, name: String, avatar: String = ""): Boolean {
        val k = url.trim().ifEmpty { name.trim() }
        val list = all
        val existing = list.any { it.key == k }
        val updated = if (existing) {
            list.filterNot { it.key == k }
        } else {
            list + Item(name = name.trim(), avatar = avatar.trim(), url = url.trim())
        }
        save(updated)
        return !existing
    }

    private suspend fun save(list: List<Item>) {
        SettingsRepository.setFollowedArtistsJson(json.encodeToString(list))
    }
}
