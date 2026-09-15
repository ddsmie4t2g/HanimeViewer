package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.han1meviewer.logic.dao.LocalListDao
import io.github.daisukikaffuchino.han1meviewer.logic.dao.LocalListDatabase
import io.github.daisukikaffuchino.han1meviewer.logic.dao.LocalPlaylistRow
import io.github.daisukikaffuchino.han1meviewer.logic.entity.LocalListEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.LocalListItemEntity
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeVideo
import io.github.daisukikaffuchino.han1meviewer.logic.model.ListItemExport
import io.github.daisukikaffuchino.han1meviewer.logic.model.ListsExport
import io.github.daisukikaffuchino.han1meviewer.logic.model.PlaylistExport
import io.github.daisukikaffuchino.han1meviewer.logic.model.Playlists
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 免登录本地列表仓库：稍后再看 / 我喜欢的影片 / 自定义播放清单 / 收藏夹。
 *
 * ⭐ 9.0 起「播放清单」与「收藏夹」共用同一张表（`LocalListEntity`），靠 `kind` 区分：
 * 两者的行结构完全一致（自定义标题 + 简介 + 一组影片），只是**用途/入口不同** ——
 * 播放清单是连着看的播放队列，收藏夹是「按主题收起来的最爱」。分成两张表会让
 * 增删改查全部翻倍，而分不清的地方只有一处：列表查询要按 `kind` 过滤。
 */
object LocalListRepository {

    const val FAVORITE_CODE = "likes"
    const val WATCH_LATER_CODE = "save"
    const val PLAYLIST_KIND = "playlist"

    /**
     * **收藏夹**（9.0 新增）的分类值。
     *
     * 与 [PLAYLIST_KIND] 平级、互不可见：抽屉里是两个独立入口，
     * 视频详情页「加入清单」弹窗里会分两段显示。
     */
    const val FAVORITE_COLLECTION_KIND = "favorite_collection"

    private val dao: LocalListDao = LocalListDatabase.instance.localListDao
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun observeWatchLater(): Flow<List<HanimeInfo>> =
        dao.observeItems(WATCH_LATER_CODE).map { rows -> rows.map { it.toHanimeInfo() } }

    fun observeFavorites(): Flow<List<HanimeInfo>> =
        dao.observeItems(FAVORITE_CODE).map { rows -> rows.map { it.toHanimeInfo() } }

    fun observePlaylistItems(listCode: String): Flow<List<HanimeInfo>> =
        dao.observeItems(listCode).map { rows -> rows.map { it.toHanimeInfo() } }

    /** 某一类用户自建列表（`kind` = [PLAYLIST_KIND] / [FAVORITE_COLLECTION_KIND]）。 */
    fun observeListsByKind(kind: String): Flow<List<Playlists.Playlist>> =
        dao.observeListsByKind(kind).map { rows -> rows.map { it.toPlaylist() } }

    fun observePlaylists(): Flow<List<Playlists.Playlist>> =
        observeListsByKind(PLAYLIST_KIND)

    /** **收藏夹**列表（9.0）。 */
    fun observeFavoriteCollections(): Flow<List<Playlists.Playlist>> =
        observeListsByKind(FAVORITE_COLLECTION_KIND)

    fun observeIsFavorite(videoCode: String): Flow<Boolean> =
        dao.observeIsFavorite(videoCode)

    fun observeIsWatchLater(videoCode: String): Flow<Boolean> =
        dao.observeIsWatchLater(videoCode)

    fun observeListCodes(videoCode: String): Flow<List<String>> =
        dao.observeListCodes(videoCode)

    suspend fun isFavorite(videoCode: String): Boolean =
        dao.findItem(FAVORITE_CODE, videoCode) != null

    suspend fun isWatchLater(videoCode: String): Boolean =
        dao.findItem(WATCH_LATER_CODE, videoCode) != null

    suspend fun setFavorite(videoCode: String, video: HanimeVideo, add: Boolean) =
        setItem(FAVORITE_CODE, videoCode, video, add)

    suspend fun setWatchLater(videoCode: String, video: HanimeVideo, add: Boolean) =
        setItem(WATCH_LATER_CODE, videoCode, video, add)

    /**
     * 把影片放进 / 移出**任意自建列表**（播放清单、收藏夹都走这里）。
     *
     * 判据只有 `listCode` —— 表里没有「类型」以外的东西，所以收藏夹不需要另一套方法。
     */
    suspend fun setListContains(
        listCode: String,
        videoCode: String,
        video: HanimeVideo,
        add: Boolean,
    ) = setItem(listCode, videoCode, video, add)

    suspend fun setPlaylistContains(
        listCode: String,
        videoCode: String,
        video: HanimeVideo,
        add: Boolean,
    ) = setItem(listCode, videoCode, video, add)

    suspend fun removeItem(listCode: String, videoCode: String) =
        dao.deleteItem(listCode, videoCode)

    /** 新建一个自建列表；[kind] 决定它出现在「播放清单」还是「收藏夹」页。 */
    suspend fun createList(kind: String, title: String, desc: String): Playlists.Playlist {
        val now = System.currentTimeMillis()
        val code = "local_" + UUID.randomUUID().toString().replace("-", "")
        dao.upsertPlaylist(
            LocalListEntity(
                listCode = code,
                kind = kind,
                title = title,
                desc = desc,
                createdAt = now,
                updatedAt = now,
            )
        )
        return Playlists.Playlist(listCode = code, title = title, total = 0, coverUrl = null)
    }

    suspend fun createPlaylist(title: String, desc: String): Playlists.Playlist =
        createList(PLAYLIST_KIND, title, desc)

    /** 新建一个**收藏夹**。 */
    suspend fun createFavoriteCollection(title: String, desc: String): Playlists.Playlist =
        createList(FAVORITE_COLLECTION_KIND, title, desc)

    suspend fun updatePlaylist(listCode: String, title: String, desc: String) {
        val current = dao.getPlaylist(listCode) ?: return
        dao.upsertPlaylist(
            current.copy(
                title = title,
                desc = desc,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deletePlaylist(listCode: String) {
        dao.deletePlaylistItems(listCode)
        dao.deletePlaylist(listCode)
    }

    suspend fun getListDesc(listCode: String): String? =
        dao.getPlaylist(listCode)?.desc

    suspend fun getPlaylistDesc(listCode: String): String? =
        getListDesc(listCode)

    /** 某一类自建列表（[PLAYLIST_KIND] / [FAVORITE_COLLECTION_KIND]）。 */
    suspend fun getListsOnceByKind(kind: String): List<Playlists.Playlist> =
        dao.getListsOnceByKind(kind).map { it.toPlaylist() }

    suspend fun getPlaylistsOnce(): List<Playlists.Playlist> =
        getListsOnceByKind(PLAYLIST_KIND)

    /** **收藏夹**列表（9.0）。 */
    suspend fun getFavoriteCollectionsOnce(): List<Playlists.Playlist> =
        getListsOnceByKind(FAVORITE_COLLECTION_KIND)

    suspend fun getPlaylistItemsOnce(listCode: String): List<HanimeInfo> =
        dao.getItems(listCode).map { it.toHanimeInfo() }

    suspend fun exportLocalLists(): ListsExport =
        ListsExport(
            watchLater = dao.getItems(WATCH_LATER_CODE).map { it.toExport() },
            favorites = dao.getItems(FAVORITE_CODE).map { it.toExport() },
            playlists = exportKind(PLAYLIST_KIND),
            favoriteCollections = exportKind(FAVORITE_COLLECTION_KIND),
        )

    private suspend fun exportKind(kind: String): List<PlaylistExport> =
        dao.getListsOnceByKind(kind).map { row ->
            PlaylistExport(
                title = row.title,
                desc = row.desc,
                items = dao.getItems(row.listCode).map { it.toExport() },
            )
        }

    suspend fun exportLocalListsJson(): String =
        json.encodeToString(exportLocalLists())

    suspend fun importLocalListsJson(jsonText: String, merge: Boolean = true) =
        importLocalLists(json.decodeFromString<ListsExport>(jsonText), merge)

    /**
     * ⚠️ `merge = false` 是**覆盖式恢复**：会先清空本机全部自建列表（播放清单 **和** 收藏夹）
     * 再按备份写回。之所以能把两者一起清，是因为 [exportLocalLists] 已经把收藏夹
     * 一起导出了 —— 否则一次「恢复备份」就会把收藏夹清空。
     */
    suspend fun importLocalLists(data: ListsExport, merge: Boolean) {
        if (!merge) {
            dao.deleteAllItems()
            dao.deleteAllPlaylists()
        }
        val now = System.currentTimeMillis()
        importKind(data.playlists, PLAYLIST_KIND, merge, now)
        importKind(data.favoriteCollections, FAVORITE_COLLECTION_KIND, merge, now)
        data.watchLater.forEachIndexed { index, item ->
            dao.upsertItem(item.toEntity(WATCH_LATER_CODE, now - index))
        }
        data.favorites.forEachIndexed { index, item ->
            dao.upsertItem(item.toEntity(FAVORITE_CODE, now - index))
        }
    }

    /**
     * 按 `kind` 写回一组列表，同名列表按标题合并（只增不删）。
     */
    private suspend fun importKind(
        lists: List<PlaylistExport>,
        kind: String,
        merge: Boolean,
        now: Long,
    ) {
        val existing = if (merge) {
            dao.getListsOnceByKind(kind).associateBy { it.title }
        } else {
            emptyMap()
        }
        val createdCodes = mutableMapOf<String, String>()
        lists.forEach { list ->
            val listCode = createdCodes[list.title]
                ?: existing[list.title]?.listCode
                ?: createList(kind, list.title, list.desc).listCode
            createdCodes[list.title] = listCode
            list.items.forEachIndexed { index, item ->
                dao.upsertItem(item.toEntity(listCode, now - index))
            }
        }
    }

    private suspend fun setItem(
        listCode: String,
        videoCode: String,
        video: HanimeVideo,
        add: Boolean,
    ) {
        if (add) {
            dao.upsertItem(video.toLocalListItem(listCode, videoCode))
        } else {
            dao.deleteItem(listCode, videoCode)
        }
    }

    private fun LocalListItemEntity.toHanimeInfo(): HanimeInfo =
        HanimeInfo(
            title = title,
            coverUrl = coverUrl,
            videoCode = videoCode,
            duration = duration,
            views = views,
            uploadTime = uploadTime,
            genre = genre,
            isPlaying = false,
            itemType = HanimeInfo.NORMAL,
            reviews = reviews ?: "",
            currentArtist = currentArtist ?: "",
            watched = false,
        )

    private fun HanimeVideo.toLocalListItem(
        listCode: String,
        videoCode: String,
    ): LocalListItemEntity =
        LocalListItemEntity(
            listCode = listCode,
            videoCode = videoCode,
            title = title,
            coverUrl = coverUrl,
            duration = null,
            views = views,
            uploadTime = uploadTime?.toString(),
            genre = null,
            reviews = null,
            currentArtist = artist?.name,
            addedAt = System.currentTimeMillis(),
        )

    private fun LocalPlaylistRow.toPlaylist(): Playlists.Playlist =
        Playlists.Playlist(
            listCode = listCode,
            title = title,
            total = total,
            coverUrl = coverUrl,
        )

    private fun LocalListItemEntity.toExport(): ListItemExport =
        ListItemExport(
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

    private fun ListItemExport.toEntity(
        listCode: String,
        fallbackAddedAt: Long,
    ): LocalListItemEntity =
        LocalListItemEntity(
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
            addedAt = if (addedAt > 0) addedAt else fallbackAddedAt,
        )
}
