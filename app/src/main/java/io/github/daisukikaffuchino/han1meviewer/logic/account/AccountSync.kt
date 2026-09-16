package io.github.daisukikaffuchino.han1meviewer.logic.account

import io.github.daisukikaffuchino.han1meviewer.logic.FollowedArtistStore
import io.github.daisukikaffuchino.han1meviewer.logic.LocalListRepository
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.dao.HistoryDatabase
import io.github.daisukikaffuchino.han1meviewer.logic.dao.LocalListDatabase
import io.github.daisukikaffuchino.han1meviewer.logic.entity.LocalListEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.LocalListItemEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.WatchHistoryEntity
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 账号要同步的**那份数据**，以及它的合并规则。
 *
 * ## 为什么是「一份文档」而不是一张张表
 *
 * 同步的输入输出都是这一个 [AccountSnapshot]（服务端只把它当**不透明 JSON** 存，
 * 根本不解析）。好处是加字段不用改服务端、也不用改协议版本协商 ——
 * 客户端多一个字段，老服务端照样原样存回来。
 *
 * ## ⭐ 合并语义：并集 + 取新（**永不丢数据**）
 *
 * | 数据 | 规则 |
 * |---|---|
 * | 关注作者 | 按身份键（url，退回名字）并集 |
 * | 清单 | 按 listCode 并集；清单内条目按 (listCode, videoCode) 并集，`addedAt` 取大 |
 * | 观看记录 | 按 videoCode 并集；`progress` 与 `watchDate` **各取最大** |
 *
 * 这与 9.1 的「观看记录合并导入」是同一套语义（同片取 max(progress) + max(watchDate)），
 * 用户已经熟悉。代价是**删除不会同步**：在 A 机取消关注，B 机仍然保留（下一次同步还会并回来）。
 * 这是刻意的取舍 —— 「同步时把别人机器上的东西删掉」的风险远大于「多留一条」，
 * 真要做删除得引入墓碑标记，那是另一轮的事。
 */
@Serializable
data class AccountSnapshot(
    val schema: Int = SCHEMA,
    val exportedAt: Long = 0L,
    val followedArtists: List<ArtistRef> = emptyList(),
    val lists: List<ListSnapshot> = emptyList(),
    val watchHistory: List<HistorySnapshot> = emptyList(),
) {
    companion object {
        /** 文档结构版本。加字段可以不改（都是可选的），改语义才需要动它。 */
        const val SCHEMA = 1
    }
}

@Serializable
data class ListSnapshot(
    val listCode: String,
    val kind: String,
    val title: String,
    val desc: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val items: List<ItemSnapshot> = emptyList(),
)

@Serializable
data class ItemSnapshot(
    val listCode: String,
    val videoCode: String,
    val title: String,
    val coverUrl: String,
    val duration: String? = null,
    val views: String? = null,
    val uploadTime: String? = null,
    val genre: String? = null,
    val reviews: String? = null,
    val currentArtist: String? = null,
    val addedAt: Long = 0L,
)

@Serializable
data class HistorySnapshot(
    val videoCode: String,
    val title: String,
    val coverUrl: String,
    val releaseDate: Long = 0L,
    val watchDate: Long = 0L,
    val progress: Long = 0L,
)

object AccountSync {

    /** 本地内置的两个列表：它们没有 [LocalListEntity] 元数据，但条目要一起同步。 */
    private const val CODE_FAVORITE = "likes"
    private const val CODE_WATCH_LATER = "save"

    /**
     * 自建列表的「本地 kind」→「快照里的 kind」（9.0）。
     *
     * 快照里的名字与本地**故意不完全一样**：`playlist` 两边同名是历史原因，
     * 收藏夹则另起 `favorite_collection`。老客户端读到不认识的 kind 只会
     * 原样留在 JSON 里（服务端把它当不透明数据），不会误当成播放清单写回本机。
     */
    private val KINDS_WITH_CODE = listOf(
        LocalListRepository.PLAYLIST_KIND to "playlist",
        LocalListRepository.FAVORITE_COLLECTION_KIND to "favorite_collection",
    )

    /** [KINDS_WITH_CODE] 的反查表：[apply] 写回本机时用。 */
    private val CODE_TO_KIND = KINDS_WITH_CODE.associate { (local, code) -> code to local }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val localListDao get() = LocalListDatabase.instance.localListDao
    private val historyDao get() = HistoryDatabase.instance.watchHistory

    // ────────────────────────────────────────────────────────── 读取本机

    /** 把本机数据打成一个快照。 */
    suspend fun snapshot(): AccountSnapshot {
        // ⭐ 9.0：自建列表现在有两类（播放清单 / 收藏夹），一起同步 ——
        // 不然换台机器登录，「收藏夹」会凭空消失。
        val lists = buildList {
            for ((kind, code) in KINDS_WITH_CODE) {
                localListDao.getListsOnceByKind(kind).forEach { row ->
                    add(
                        ListSnapshot(
                            listCode = row.listCode,
                            kind = code,
                            title = row.title,
                            desc = row.desc,
                            createdAt = row.createdAt,
                            updatedAt = row.updatedAt,
                            items = localListDao.getItems(row.listCode).map { it.toSnapshot() },
                        )
                    )
                }
            }
            // 内置列表：没有元数据行，但**条目必须同步**（它们就是「我喜欢」与「稍后再看」）。
            listOf(CODE_FAVORITE to "favorite", CODE_WATCH_LATER to "watchLater").forEach { (code, kind) ->
                val items = localListDao.getItems(code)
                if (items.isNotEmpty()) {
                    add(
                        ListSnapshot(
                            listCode = code,
                            kind = kind,
                            title = "",
                            items = items.map { it.toSnapshot() },
                        )
                    )
                }
            }
        }
        return AccountSnapshot(
            exportedAt = System.currentTimeMillis(),
            followedArtists = FollowedArtistStore.asArtistRefs,
            lists = lists,
            watchHistory = historyDao.getAll().map {
                HistorySnapshot(
                    videoCode = it.videoCode,
                    title = it.title,
                    coverUrl = it.coverUrl,
                    releaseDate = it.releaseDate,
                    watchDate = it.watchDate,
                    progress = it.progress,
                )
            },
        )
    }

    fun encode(snapshot: AccountSnapshot): String = json.encodeToString(snapshot)

    fun decode(raw: String): AccountSnapshot = runCatching {
        json.decodeFromString<AccountSnapshot>(raw.ifBlank { "{}" })
    }.getOrDefault(AccountSnapshot())

    private fun LocalListItemEntity.toSnapshot() = ItemSnapshot(
        listCode = listCode,
        videoCode = videoCode,
        title = title,
        coverUrl = coverUrl,
        duration = duration,
        views = views,
        uploadTime = uploadTime,
        genre = genre,
        reviews = reviews,
        currentArtist = currentArtist,
        addedAt = addedAt,
    )

    // ────────────────────────────────────────────────────────── 合并

    /**
     * 合并两份快照：并集，冲突时**取新/取大**。顺序无关（可交换），所以
     * 「先本地后云端」和「先云端后本地」结果一致 —— 这是冲突重试能收敛的前提。
     */
    fun merge(a: AccountSnapshot, b: AccountSnapshot): AccountSnapshot {
        val follows = LinkedHashMap<String, ArtistRef>()
        (a.followedArtists + b.followedArtists).forEach { ref ->
            val key = ref.followKey
            if (key.isEmpty()) return@forEach
            // ⚠️ 归并的判据是「是不是同一个人」而不是「键串相等」：站点给的显示名别名
            //    会让同一个人算出两个键，只按键归并就会把一位关注者同步成两条。
            val existingKey = follows.entries.firstOrNull { it.value.matchesIdentity(ref) }?.key ?: key
            val existing = follows[existingKey]
            // 信息更全的那条胜出（老记录可能没有作品数/头像，新的补上）。
            follows[existingKey] = when {
                existing == null -> ref
                score(ref) >= score(existing) -> ref
                else -> existing
            }
        }

        val lists = LinkedHashMap<String, ListSnapshot>()
        (a.lists + b.lists).forEach { list ->
            val existing = lists[list.listCode]
            if (existing == null) {
                lists[list.listCode] = list
                return@forEach
            }
            val newer = if (list.updatedAt >= existing.updatedAt) list else existing
            val items = LinkedHashMap<String, ItemSnapshot>()
            (existing.items + list.items).forEach { item ->
                val key = item.listCode + "\u0000" + item.videoCode
                val old = items[key]
                items[key] = if (old == null || item.addedAt >= old.addedAt) item else old
            }
            lists[list.listCode] = newer.copy(items = items.values.toList())
        }

        val history = LinkedHashMap<String, HistorySnapshot>()
        (a.watchHistory + b.watchHistory).forEach { entry ->
            val existing = history[entry.videoCode]
            history[entry.videoCode] = if (existing == null) {
                entry
            } else {
                HistorySnapshot(
                    videoCode = entry.videoCode,
                    // 标题/封面取**看得更晚**的那次（内容可能被站点改过）。
                    title = if (entry.watchDate >= existing.watchDate) entry.title else existing.title,
                    coverUrl = if (entry.watchDate >= existing.watchDate) entry.coverUrl else existing.coverUrl,
                    releaseDate = maxOf(entry.releaseDate, existing.releaseDate),
                    watchDate = maxOf(entry.watchDate, existing.watchDate),
                    progress = maxOf(entry.progress, existing.progress),
                )
            }
        }

        return AccountSnapshot(
            schema = maxOf(a.schema, b.schema),
            exportedAt = maxOf(a.exportedAt, b.exportedAt),
            followedArtists = follows.values.toList(),
            lists = lists.values.toList(),
            watchHistory = history.values.toList(),
        )
    }

    /** 「这条关注记录有多全」——合并时用来挑更好的那条，纯启发式，不影响正确性。 */
    private fun score(ref: ArtistRef): Int =
        (if (ref.avatar.isNotBlank()) 1 else 0) +
                (if (ref.url.isNotBlank()) 1 else 0) +
                (if (ref.videoCount.isNotBlank()) 1 else 0) +
                (if (ref.subscriberCount.isNotBlank()) 1 else 0) +
                (if (ref.site.isNotBlank()) 1 else 0)

    // ────────────────────────────────────────────────────────── 写回本机

    /**
     * 把合并结果写回本机（幂等：同一份写两次结果一样）。
     *
     * 只**写入/更新**，不删除本机已有的东西 —— 与 [merge] 的取舍一致。
     */
    suspend fun apply(snapshot: AccountSnapshot) {
        SettingsRepository.setFollowedArtistsJson(
            json.encodeToString(
                snapshot.followedArtists.map { it.toFollowedItem() }
            )
        )

        snapshot.lists.forEach { list ->
            val localKind = CODE_TO_KIND[list.kind]
            if (localKind != null && list.listCode.isNotBlank()) {
                localListDao.upsertPlaylist(
                    LocalListEntity(
                        listCode = list.listCode,
                        kind = localKind,
                        title = list.title,
                        desc = list.desc,
                        createdAt = if (list.createdAt > 0) list.createdAt else System.currentTimeMillis(),
                        updatedAt = if (list.updatedAt > 0) list.updatedAt else System.currentTimeMillis(),
                    )
                )
            }
            list.items.forEach { item ->
                localListDao.upsertItem(
                    LocalListItemEntity(
                        listCode = item.listCode,
                        videoCode = item.videoCode,
                        title = item.title,
                        coverUrl = item.coverUrl,
                        duration = item.duration,
                        views = item.views,
                        uploadTime = item.uploadTime,
                        genre = item.genre,
                        reviews = item.reviews,
                        currentArtist = item.currentArtist,
                        addedAt = item.addedAt,
                    )
                )
            }
        }

        snapshot.watchHistory.forEach { entry ->
            val existing = historyDao.findBy(entry.videoCode)
            historyDao.insertOrUpdate(
                WatchHistoryEntity(
                    coverUrl = entry.coverUrl.ifBlank { existing?.coverUrl.orEmpty() },
                    title = entry.title.ifBlank { existing?.title.orEmpty() },
                    releaseDate = maxOf(entry.releaseDate, existing?.releaseDate ?: 0L),
                    watchDate = maxOf(entry.watchDate, existing?.watchDate ?: 0L),
                    videoCode = entry.videoCode,
                    progress = maxOf(entry.progress, existing?.progress ?: 0L),
                    // 复用本机那条的 id：insertOrUpdate 对已有记录是 UPDATE，别让它插出第二行。
                    id = existing?.id ?: 0,
                )
            )
        }
    }

    /**
     * 差异统计（给界面显示「这次同步做了什么」）。
     *
     * 只算**云端有、本机没有**的部分 —— 用户看的是「同步会不会给我带来东西」。
     */
    fun diff(local: AccountSnapshot, merged: AccountSnapshot): Diff {
        val localFollows = local.followedArtists.mapTo(mutableSetOf()) { it.followKey }
        val localHistory = local.watchHistory.mapTo(mutableSetOf()) { it.videoCode }
        val localItems = local.lists.flatMap { list -> list.items.map { it.listCode + "/" + it.videoCode } }
            .toMutableSet()
        return Diff(
            newFollows = merged.followedArtists.count { it.followKey !in localFollows },
            newHistory = merged.watchHistory.count { it.videoCode !in localHistory },
            newListItems = merged.lists.sumOf { list ->
                list.items.count { (it.listCode + "/" + it.videoCode) !in localItems }
            },
        )
    }

    data class Diff(val newFollows: Int, val newHistory: Int, val newListItems: Int) {
        val isEmpty: Boolean get() = newFollows == 0 && newHistory == 0 && newListItems == 0
    }
}
