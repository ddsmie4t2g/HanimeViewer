package io.github.daisukikaffuchino.han1meviewer.logic.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.daisukikaffuchino.han1meviewer.logic.entity.LocalListEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.LocalListItemEntity
import kotlinx.coroutines.flow.Flow

data class LocalPlaylistRow(
    val listCode: String,
    val title: String,
    val desc: String,
    val createdAt: Long,
    val updatedAt: Long,
    val total: Int,
    val coverUrl: String?,
)

@Dao
interface LocalListDao {

    /**
     * 列出「某一类」用户自建列表（`kind` 见 [io.github.daisukikaffuchino.han1meviewer.logic.LocalListRepository]）。
     *
     * ⭐ 9.0 起把 `kind` 变成参数：原来这里写死 `kind = 'playlist'`，于是「播放清单」
     * 与「收藏夹」只能共用一张表却没法各查各的。两者的行结构完全一样（标题 / 简介 /
     * 封面 / 条目数），只是分类不同 —— 所以查询也只需要换一个 `kind`。
     */
    @Query(
        """
        SELECT l.listCode, l.title, l.desc, l.createdAt, l.updatedAt,
               (SELECT COUNT(*) FROM LocalListItemEntity i WHERE i.listCode = l.listCode) AS total,
               (SELECT coverUrl FROM LocalListItemEntity i WHERE i.listCode = l.listCode
                ORDER BY addedAt DESC LIMIT 1) AS coverUrl
        FROM LocalListEntity l
        WHERE l.kind = :kind
        ORDER BY l.createdAt DESC
        """
    )
    fun observeListsByKind(kind: String): Flow<List<LocalPlaylistRow>>

    @Query(
        """
        SELECT l.listCode, l.title, l.desc, l.createdAt, l.updatedAt,
               (SELECT COUNT(*) FROM LocalListItemEntity i WHERE i.listCode = l.listCode) AS total,
               (SELECT coverUrl FROM LocalListItemEntity i WHERE i.listCode = l.listCode
                ORDER BY addedAt DESC LIMIT 1) AS coverUrl
        FROM LocalListEntity l
        WHERE l.kind = :kind
        ORDER BY l.createdAt DESC
        """
    )
    suspend fun getListsOnceByKind(kind: String): List<LocalPlaylistRow>

    @Query("SELECT * FROM LocalListEntity WHERE listCode = :listCode LIMIT 1")
    suspend fun getPlaylist(listCode: String): LocalListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(entity: LocalListEntity)

    @Query("DELETE FROM LocalListEntity WHERE listCode = :listCode")
    suspend fun deletePlaylist(listCode: String)

    @Query("DELETE FROM LocalListItemEntity WHERE listCode = :listCode")
    suspend fun deletePlaylistItems(listCode: String)

    @Query("DELETE FROM LocalListItemEntity")
    suspend fun deleteAllItems()

    @Query("DELETE FROM LocalListEntity")
    suspend fun deleteAllPlaylists()

    @Query("SELECT * FROM LocalListItemEntity WHERE listCode = :listCode ORDER BY addedAt DESC")
    fun observeItems(listCode: String): Flow<List<LocalListItemEntity>>

    @Query("SELECT * FROM LocalListItemEntity WHERE listCode = :listCode ORDER BY addedAt DESC")
    suspend fun getItems(listCode: String): List<LocalListItemEntity>

    @Query(
        "SELECT * FROM LocalListItemEntity WHERE listCode = :listCode AND videoCode = :videoCode LIMIT 1"
    )
    suspend fun findItem(listCode: String, videoCode: String): LocalListItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(entity: LocalListItemEntity)

    @Query("DELETE FROM LocalListItemEntity WHERE listCode = :listCode AND videoCode = :videoCode")
    suspend fun deleteItem(listCode: String, videoCode: String)

    @Query("SELECT DISTINCT listCode FROM LocalListItemEntity WHERE videoCode = :videoCode")
    fun observeListCodes(videoCode: String): Flow<List<String>>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM LocalListItemEntity WHERE listCode = 'likes' AND videoCode = :videoCode)"
    )
    fun observeIsFavorite(videoCode: String): Flow<Boolean>

    @Query(
        "SELECT EXISTS(SELECT 1 FROM LocalListItemEntity WHERE listCode = 'save' AND videoCode = :videoCode)"
    )
    fun observeIsWatchLater(videoCode: String): Flow<Boolean>
}
