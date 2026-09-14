package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource
import io.github.daisukikaffuchino.han1meviewer.logic.model.SubscriptionItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        /**
         * 身份键：**规范化后的主页地址**（见 [ArtistRef.identityKey]）。
         *
         * ⚠️ 26.8.3 起不再是「url 原样」。用户 2026-09-14 报的「同一位女优关注出两条」
         * 就是原样比较造成的：nJAV 同一张女优页有 `…/actresses/<名>`、
         * `…/dm288/cn/actresses/<名>`、`…?page=2` 等多种写法，原样比就是三个人。
         *
         * 老数据不用迁移：键是在读取时**算**出来的，存的是原始 url ——
         * 所以升级后两条老记录会自动算出同一个键，下一次写入时合并成一条（见 [dedup]）。
         */
        val key: String
            get() = ArtistRef.keyOf(name, url, toArtistRef().siteSource)

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

    /**
     * 当前关注的全部作者（按关注顺序）。
     *
     * ⚠️ 读的时候顺手按 [Item.key] 合并重复项（26.8.3）。老版本写下的数据里
     * 同一个人可能躺着两三条（不同 URL 写法各一条），不合并的话关注列表会长出两个
     * 一模一样的头像 —— 那正是用户看到的现象。
     */
    val all: List<Item>
        get() {
            val decoded = runCatching {
                json.decodeFromString<List<Item>>(SettingsRepository.followedArtistsJson)
            }.getOrDefault(emptyList())
            val (merged, changed) = dedup(decoded)
            if (changed) persist(merged)
            return merged
        }

    /** 关注页要的形态（作者页头部 / 抽屉入口都用它）。 */
    val asArtistRefs: List<ArtistRef>
        get() = all.map { it.toArtistRef() }

    /**
     * 按站点分组（订阅页要把 hanime / Pornhub / nJAV 画成三块互不混淆的分区）。
     *
     * 分组依据是 [ArtistRef.siteSource] —— 它优先看主页地址的路径形态，所以老记录
     * （没有 `site` 字段）也能落到正确的组里。
     */
    fun bySite(site: SiteSource): List<ArtistRef> =
        asArtistRefs.filter { it.siteSource == site }

    /**
     * ⭐ hanime 服务端订阅在本机的**副本**。
     *
     * 为什么要有它：hanime 的订阅只存在 hanime 的服务器上，**没登录就一点都看不到**。
     * 而用户要的是「订阅之后同步到我自己的账号，没登 hanime 时读我自己那份」——
     * 那份「自己」就是这里。写入见 [mergeSubscriptionItems]。
     */
    val hanimeRefs: List<ArtistRef>
        get() = bySite(SiteSource.Hanime1)

    /**
     * ⭐ 把 hanime 服务端订阅**并进**本机关注库（只增不改），返回新增条数。
     *
     * ## 语义
     * - **只增不删**：hanime 那边取关了，这里不清 —— 与 [AccountSync.merge] 的取舍一致
     *   （同步删数据的风险远大于多留一条；真要删得引墓碑标记）。
     *   所以「已登录时读服务端、未登录时读本机」两条路径看到的会是同一批人，
     *   只是取关后本机这份会多留一会儿。
     * - **按名字去重**（只跟本机的 hanime 条目比）：hanime 的订阅列表只有名字和头像，
     *   没有作者主页地址，所以身份键就是名字。同名但 url 不同的旧记录也不会被重复插入。
     * - 头像为空时**不覆盖**已有记录的头像。
     *
     * 写进本机之后，[AccountSync.snapshot] 会自然把它带上用户自建账号，
     * 于是「订一次、换任何设备都能看到」这件事就成立了。
     */
    suspend fun mergeSubscriptionItems(items: List<SubscriptionItem>): Int {
        if (items.isEmpty()) return 0

        val existing = all
        // 只跟「同样是 hanime 的人」比名字：别的站有同名作者不该拦着。
        val known = existing.filter { it.toArtistRef().siteSource == SiteSource.Hanime1 }
            .mapTo(mutableSetOf()) { it.name.trim().lowercase() }

        val additions = mutableListOf<Item>()
        items.forEach { item ->
            val name = item.artistName.trim()
            if (name.isEmpty()) return@forEach
            if (!known.add(name.lowercase())) return@forEach
            additions += Item(
                name = name,
                avatar = item.avatar,
                // 没有主页地址：hanime 站点上没有作者页，身份只能用名字。
                url = "",
                site = SiteSource.Hanime1.value,
            )
        }
        if (additions.isEmpty()) return 0
        save(existing + additions)
        return additions.size
    }

    fun isFollowed(key: String): Boolean {
        val k = key.trim()
        if (k.isEmpty()) return false
        return all.any { it.key == k }
    }

    /**
     * 有多少位 **nJAV** 关注者还缺头像（26.8.2）。
     *
     * 判据刻意只算 nJAV：nJAV 的**视频详情页给不出女优头像**（真头像只在女优一览 /
     * 排行页的卡片里），所以这批人是「天生没有头像」，需要专门去补；
     * 而 hanime / Pornhub 的详情页本来就带头像，为空只说明站点没给，补不了也不该补。
     */
    fun countMissingNjavAvatars(): Int = all.count { item ->
        item.avatar.isBlank() && item.toArtistRef().siteSource == SiteSource.Njav
    }

    /**
     * 用女优索引缓存把 nJAV 关注者的**空头像**补上（26.8.2），返回补了几条。
     *
     * 为什么不在这里发网络请求：取数属于仓库层的职责，而且「一次请求拿一页 52 个头像」
     * 这件事只有调用方知道该不该做（见 `NetworkRepo.warmUpNjavActressCache`）。
     * 这里只负责「拿现成的一批头像，把关注表补全并落盘」。
     *
     * ⚠️ 只补**空头像**，绝不覆盖已有的（用户可能已经从别处拿到了同一张图，
     * 或者站点换了图而旧的那张还能用）。
     *
     * @param avatarOf 按名字查头像；没有就返回空串
     */
    suspend fun fillMissingAvatars(avatarOf: (String) -> String): Int {
        val list = all
        var filled = 0
        val updated = list.map { item ->
            if (item.avatar.isNotBlank()) return@map item
            val ref = item.toArtistRef()
            if (ref.siteSource != SiteSource.Njav) return@map item
            val avatar = avatarOf(ref.name).trim()
            if (avatar.isEmpty()) return@map item
            filled++
            item.copy(avatar = avatar)
        }
        if (filled > 0) save(updated)
        return filled
    }

    /** 关注页要的形态（订阅页的作者格子用的就是它）。 */
    val asSubscriptionItems: List<SubscriptionItem>
        get() = all.map { SubscriptionItem(artistName = it.name, avatar = it.avatar) }

    /**
     * 给**已经在关注表里**的那位补资料（头像 / 作品数），不新增、不改关注顺序。
     *
     * 为什么不用 [toggle] 两下：那样会先删后加，把这个人从「关注顺序」里挪到末尾，
     * 用户在关注列表里看到的是「我什么都没干，顺序变了」。
     *
     * @return 真的改了才返回 true
     */
    suspend fun enrich(ref: ArtistRef): Boolean {
        val k = ref.followKey
        if (k.isEmpty()) return false
        val list = all
        var changed = false
        val updated = list.map { item ->
            if (!sameIdentity(item, k)) return@map item
            val merged = item.copy(
                name = item.name.ifBlank { ref.name },
                avatar = item.avatar.ifBlank { ref.avatar },
                url = item.url.ifBlank { ref.url },
                site = item.site.ifBlank { ref.site },
                videoCount = item.videoCount.ifBlank { ref.videoCount },
                subscriberCount = item.subscriberCount.ifBlank { ref.subscriberCount },
            )
            if (merged != item) changed = true
            merged
        }
        if (changed) save(updated)
        return changed
    }

    /**
     * 关注 / 取关，返回**新状态**（`true` = 现在已关注）。
     *
     * 取关按**规范化身份键**删（见 [Item.key]），顺带把「同一身份但写法变了」的
     * 旧记录也清掉，不然会出现「点已关注，但列表里还留着一个」。
     */
    suspend fun toggle(ref: ArtistRef): Boolean {
        val k = ref.followKey
        if (k.isEmpty()) return false
        val list = all
        val existing = list.any { sameIdentity(it, k) }
        val updated = if (existing) {
            list.filterNot { sameIdentity(it, k) }
        } else {
            // 已关注过但换了数据源/补齐了资料时，以最后一次看到的为准 ——
            // 保留旧记录只会让作者页头部一直显示过时的作品数。
            list.filterNot { sameIdentity(it, k) } + ref.toFollowedItem()
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

    /** 该条目是不是键 [key] 那位（两边都按 [ArtistRef.keyOf] 归一后比）。 */
    private fun sameIdentity(item: Item, key: String): Boolean {
        if (key.isEmpty()) return false
        return ArtistRef.keyOf(item.name, item.url, item.toArtistRef().siteSource) == key
    }

    /**
     * 把同一身份的多条记录合并成一条（26.8.3）。
     *
     * 只在读取时做：**保留第一条的位置与名字**，只把后来那条里「第一位没有的信息」
     * 填进去（头像 / 作品数 / 分类码……）—— 老版本写下的 url 可能更短、更新的那条
     * 可能更全。**绝不**因为「后来的更全」就改写已有的头像：那张图可能正被界面用着，
     * 而站点换图后旧地址往往还能用。
     *
     * @return 合并后的列表 + 有没有真的合并掉东西（有才需要落盘）
     */
    private fun dedup(list: List<Item>): Pair<List<Item>, Boolean> {
        if (list.size <= 1) return list to false
        val byIdentity = LinkedHashMap<String, Item>()
        var changed = false
        list.forEach { item ->
            val id = ArtistRef.keyOf(item.name, item.url, item.toArtistRef().siteSource)
            if (id.isEmpty()) {
                // 连名字都没有的脏数据：原样留着，但不能拿空键互相覆盖。
                byIdentity["\u0000" + item.hashCode()] = item
                return@forEach
            }
            val first = byIdentity[id]
            if (first == null) {
                byIdentity[id] = item
                return@forEach
            }
            val merged = first.copy(
                name = first.name.ifBlank { item.name },
                avatar = first.avatar.ifBlank { item.avatar },
                url = first.url.ifBlank { item.url },
                site = first.site.ifBlank { item.site },
                genre = first.genre.ifBlank { item.genre },
                videoCount = first.videoCount.ifBlank { item.videoCount },
                subscriberCount = first.subscriberCount.ifBlank { item.subscriberCount },
                genreKey = first.genreKey.ifBlank { item.genreKey },
            )
            if (merged != first) byIdentity[id] = merged
            // 无论如何这一条都算「变了」：它被合并掉了（哪怕一点新信息都没带），
            // 不置位的话下次读还会再看到这个重复项。
            changed = true
        }
        return byIdentity.values.toList() to changed
    }

    private suspend fun save(list: List<Item>) {
        persist(dedup(list).first)
    }

    /**
     * 直接落盘（**不再** dedup：调用方要么来自 [dedup] 之后的 [all]，要么自己已经合过了）。
     *
     * 单独留一个同步入口是因为 [all] 是同步属性 —— 合并掉重复项之后必须顺手落盘，
     * 否则下一次读还要再合一遍（界面也会跟着抖）。落盘本身挂起，所以只 `launch` 出去，
     * 不让调用方等它。
     */
    private fun persist(list: List<Item>) {
        persistScope.launch {
            runCatching {
                SettingsRepository.setFollowedArtistsJson(json.encodeToString(list))
            }
        }
    }

    /** [persist] 的落地 scope（与 [io.github.daisukikaffuchino.han1meviewer.logic.network.CdnRelay] 同一个套路）。 */
    private val persistScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
