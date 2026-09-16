package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource
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
        /**
         * **新作提醒**（9.0）：上次检查发现、而用户**还没看过**的作品数。
         *
         * 界面拿它画头像右上角的红点数字；`0` = 没有新作。
         * 用户打开该作者页后由 [markSeen] 清零。
         */
        val newCount: Int = 0,
        /**
         * **新作提醒**（9.0）：上次打开作者页时**第一页**的作品码（最多 12 个）。
         *
         * 判「新作」的办法就是拿站点第一页码跟它对差集 —— 只在本地存 12 个短字符串，
         * 比存「上次看到的时间点」可靠得多（站点给的时间粒度是「日期」，
         * 同一天传的多部片子分不出来）。
         *
         * ⚠️ **空 = 从未看过这个人**：此时 [ArtistUpdateChecker] 只做「把当前第一页
         * 记下来」，绝不把整页都报成新作（否则第一次开启提醒，所有关注者都会亮红点）。
         */
        val seenCodes: List<String> = emptyList(),
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
        get() = decode(SettingsRepository.followedArtistsJson)

    /**
     * 把一份关注表 JSON 解成条目列表（读取时合并重复项）。
     *
     * ⭐ 27.0.1 起界面自己拿着 `settings.followedArtistsJson` 调它，再交给
     * [isFollowed] 判断 —— 这样「读出来的那一份」与「刚写进去的那一份」是同一份数据，
     * 不会出现「读的走一条路、写的走另一条路」而对不上（用户 2026-09-16 报的
     * 「从关注列表点进去显示『取消关注』、从视频点进去却显示『关注』」就是这个）。
     */
    fun decode(raw: String): List<Item> = dedup(runCatching {
        json.decodeFromString<List<Item>>(raw)
    }.getOrDefault(emptyList()))

    /**
     * [ref] 在不在 [items] 里。
     *
     * 比较**不是字符串相等**，而是走 [ArtistRef.matchesIdentity] —— 站点自己会用
     * 显示名当别名（同一个人的女优页在不同语言下 slug 可能写成显示名），
     * 字符串相等会把同一个人认成两个，于是「关注了却显示没关注」。
     */
    fun isFollowed(ref: ArtistRef, items: List<Item> = all): Boolean =
        items.any { it.toArtistRef().matchesIdentity(ref) }

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
     *
     * ⚠️ 27.0.1 起整套「读 → 改 → 写」都在 [updateItems] 的**事务**里做：以前是
     * 「先读出来、在内存里改、再整份写回去」，两步之间别人写进去的关注会被这次回写抹掉。
     */
    suspend fun mergeSubscriptionItems(items: List<SubscriptionItem>): Int {
        if (items.isEmpty()) return 0

        var added = 0
        updateItems { existing ->
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
            added = additions.size
            existing + additions
        }
        return added
    }

    fun isFollowed(key: String): Boolean {
        val k = key.trim()
        if (k.isEmpty()) return false
        return all.any { it.key == k }
    }

    // ──────────────────────────────────── 新作提醒（9.0）

    /**
     * 未读新作数查询表：**身份键 → 数量**，并且**额外把小写名字也当键写一份**。
     *
     * 为什么要名字这一份：订阅页在「已登录 hanime」时显示的是**服务端订阅**
     * （[mergeSubscriptionItems] 存下来的那份只有名字和头像，没有作者主页地址，
     * 算不出身份键）。不给名字兜底，这些人的红点永远不会亮。
     *
     * ⚠️ 只在 `newCount > 0` 时才写入 ⇒ 值为 0 时查不到，调用方 `?: 0` 即可。
     * 名字冲突时**先来的赢**（[putIfAbsent] 语义）：宁可少报，也不要给不相干的人亮红点。
     *
     * 读一次会反序列化整份关注表，所以界面要**一次取完、循环里查 map**，
     * 不要每张卡片都来读它。
     */
    val unreadLookup: Map<String, Int>
        get() = buildMap {
            all.forEach { item ->
                if (item.newCount <= 0) return@forEach
                putIfAbsent(item.key, item.newCount)
                val name = item.name.trim().lowercase()
                if (name.isNotEmpty()) putIfAbsent(name, item.newCount)
            }
        }

    /**
     * 写入一轮「新作检查」的结果，返回真正被改动的条数。
     *
     * ⚠️ 27.0.1 起在事务里改：以前是「读一份 → 逐条改 → 整份写回」，
     * 如果这中间用户刚点了一个「取消关注」，回写会把它**还原**回来 ——
     * 也就是用户说的「取消了关注，一会儿又自己回来了」。
     *
     * @param results 身份键 → [UpdateResult]
     */
    suspend fun applyUpdateResults(results: Map<String, UpdateResult>): Int {
        if (results.isEmpty()) return 0
        var changed = 0
        updateItems { list ->
            list.map { item ->
                val result = results[item.key] ?: return@map item
                val merged = item.copy(
                    newCount = result.newCount,
                    // 只在「首次看到」时补种第一页；已有记录**不动** ——
                    // 动了就等于把新作当成看过了，红点当场消失。
                    seenCodes = result.seedCodes ?: item.seenCodes,
                )
                if (merged != item) {
                    changed++
                    merged
                } else {
                    item
                }
            }
        }
        return changed
    }

    /**
     * 标记「这位作者的新作都看过了」——打开作者页时调用，角标清零。
     *
     * @param firstPageCodes 当前第一页的作品码（作者页首页拿到的那些）
     *
     * ⚠️ 只在**内容真的变了**才落盘：作者页每次打开都会调它，无条件写会把
     * 关注表所在的那份 DataStore 频繁改写（并让所有订阅它的界面反复重组）。
     */
    suspend fun markSeen(ref: ArtistRef, firstPageCodes: List<String>) {
        val k = ref.followKey
        if (k.isEmpty()) return
        updateItems { list ->
            list.map { item ->
                // 认人用 matchesIdentity，不是字符串相等 —— 见 [isFollowed]。
                if (!item.toArtistRef().matchesIdentity(ref)) return@map item
                if (item.newCount == 0 && item.seenCodes == firstPageCodes) return@map item
                item.copy(newCount = 0, seenCodes = firstPageCodes)
            }
        }
    }

    /**
     * 一轮新作检查对**某一位作者**的结论。
     *
     * @param newCount 未读新作数
     * @param seedCodes 非 null 表示「这位从未记录过第一页」，用它补种；
     *   null 表示「保留原来那份」。
     */
    data class UpdateResult(
        val newCount: Int,
        val seedCodes: List<String>? = null,
    )

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
     * ⚠️ 27.0.1 起在事务里改：它由后台的 `LaunchedEffect` 触发，与用户的关注操作
     * 天然并发；不在事务里就会把并发发生的「取消关注」又补回去。
     *
     * @param avatarOf 按名字查头像；没有就返回空串
     */
    suspend fun fillMissingAvatars(avatarOf: (String) -> String): Int {
        var filled = 0
        updateItems { list ->
            list.map { item ->
                if (item.avatar.isNotBlank()) return@map item
                val ref = item.toArtistRef()
                if (ref.siteSource != SiteSource.Njav) return@map item
                val avatar = avatarOf(ref.name).trim()
                if (avatar.isEmpty()) return@map item
                filled++
                item.copy(avatar = avatar)
            }
        }
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
        var changed = false
        updateItems { list ->
            list.map { item ->
                if (!item.toArtistRef().matchesIdentity(ref)) return@map item
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
        }
        return changed
    }

    /**
     * 关注 / 取关，返回**新状态**（`true` = 现在已关注）。
     *
     * 取关按**身份**删（见 [ArtistRef.matchesIdentity]），顺带把「同一身份但写法变了」
     * 的旧记录也清掉，不然会出现「点已关注，但列表里还留着一个」。
     *
     * ⚠️ 27.0.1 起「读旧列表」与「写新列表」在**同一个事务**里完成
     * （[SettingsRepository.update]）：以前是「先读 → 判在不在 → 再写」，
     * 中间任何一次后台回写（头像补全 / 新作检查）都能把刚落盘的关注覆盖成旧列表，
     * 表现就是「点了关注，过一会儿又没了」。
     *
     * ⚠️ 调用方必须**等它返回之后**再去上传账号数据（见 `VideoViewModel` /
     * `ArtistViewModel`）：以前是先 `launch` 上传、再落盘，传上去的是**旧列表**。
     */
    suspend fun toggle(ref: ArtistRef): Boolean {
        val k = ref.followKey
        if (k.isEmpty()) return false
        var followed = false
        SettingsRepository.update { settings ->
            val list = decode(settings.followedArtistsJson)
            followed = !isFollowed(ref, list)
            val updated = if (followed) {
                // 以最后一次看到的资料为准 —— 保留旧记录只会让作者页头部显示过时的作品数。
                list + ref.toFollowedItem()
            } else {
                list.filterNot { it.toArtistRef().matchesIdentity(ref) }
            }
            settings.copy(followedArtistsJson = json.encodeToString(updated))
        }
        return followed
    }

    /**
     * 兼容入口：只给「手上只有名字/头像」的老调用方用（例如详情页的快速关注）。
     * 新代码请直接传 [ArtistRef]。
     */
    suspend fun toggle(url: String, name: String, avatar: String = ""): Boolean =
        toggle(ArtistRef(name = name, avatar = avatar, url = url))

    /**
     * 把同一身份的多条记录合并成一条（26.8.3）。
     *
     * 只在读取时做：**保留第一条的位置与名字**，只把后来那条里「第一位没有的信息」
     * 填进去（头像 / 作品数 / 分类码……）—— 老版本写下的 url 可能更短、更新的那条
     * 可能更全。**绝不**因为「后来的更全」就改写已有的头像：那张图可能正被界面用着，
     * 而站点换图后旧地址往往还能用。
     *
     * ⚠️ 合并的判据是 [ArtistRef.matchesIdentity] 而不是「键串相等」：站点给的显示名
     * 别名会让同一个人算出两个键，只用键相等就合不掉（这正是「同一位女优关注出两条」
     * 在修好身份键之后仍然偶发的原因）。
     *
     * ⚠️ 读取时**只合并、不落盘**：写入一律在 [updateItems] 的事务内完成，
     * 免得「顺手补写」把刚好发生的关注操作覆盖掉。
     */
    private fun dedup(list: List<Item>): List<Item> {
        if (list.size <= 1) return list
        val byIdentity = LinkedHashMap<String, Item>()
        list.forEach { item ->
            val id = ArtistRef.keyOf(item.name, item.url, item.toArtistRef().siteSource)
            if (id.isEmpty()) {
                // 连名字都没有的脏数据：原样留着，但不能拿空键互相覆盖。
                byIdentity["\u0000" + item.hashCode()] = item
                return@forEach
            }
            val existingId = byIdentity.entries.firstOrNull {
                it.value.toArtistRef().matchesIdentity(item.toArtistRef())
            }?.key ?: id
            val first = byIdentity[existingId]
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
                // 新作提醒（9.0）：两条同身份记录合并时，
                // 未读数**取大**（宁可多留一个红点，也不要让用户漏掉新作）；
                // 已看过的第一页取「非空」的那份。
                newCount = maxOf(first.newCount, item.newCount),
                seenCodes = first.seenCodes.ifEmpty { item.seenCodes },
            )
            if (merged != first) byIdentity[existingId] = merged
        }
        return byIdentity.values.toList()
    }

    /**
     * **唯一的写入口**：把「读当前值 → 改 → 写回」整体放进 [SettingsRepository.update]
     * 的事务里，写完顺手 dedup。
     *
     * 为什么必须这样：以前每个入口各自「先 `all` 读一份 → 在内存里改 → `save` 整份写回」，
     * 两个后台任务（头像补全、新作检查）与用户的关注操作同时进行时，
     * 后写的那个会把先写的**整份**覆盖掉 —— 表现就是「刚关注完，关注又变回去了」。
     */
    private suspend fun updateItems(transform: (List<Item>) -> List<Item>) {
        SettingsRepository.update { settings ->
            val items = decode(settings.followedArtistsJson)
            val updated = transform(items)
            if (updated == items) settings
            else settings.copy(followedArtistsJson = json.encodeToString(dedup(updated)))
        }
    }
}
