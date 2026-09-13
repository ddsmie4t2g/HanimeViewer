package io.github.daisukikaffuchino.han1meviewer.logic.account

import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AccountSync] 的**纯逻辑**测试（合并规则 + 编解码）。
 *
 * 这些是同步里最危险的部分：合并写错就是**用户数据丢失**，而且只在「两台设备都动过」
 * 时才暴露。所以它们必须在 JVM 上被测到（`./gradlew :app:testDebugUnitTest`），
 * 而不是等真机上出问题 —— 与 nJAV 那批解析测试同一个纪律。
 *
 * ⚠️ 只测**不碰 Room / DataStore** 的函数（merge / encode / decode / diff）。
 * snapshot() 与 apply() 要真数据库，属于仪器测试的范畴。
 */
class AccountSyncTest {

    private fun ref(name: String, url: String, site: String = "pornhub", avatar: String = "") =
        ArtistRef(name = name, url = url, site = site, avatar = avatar)

    private fun history(code: String, progress: Long, watchDate: Long, title: String = code) =
        HistorySnapshot(
            videoCode = code, title = title, coverUrl = "cover/$code",
            releaseDate = 0L, watchDate = watchDate, progress = progress,
        )

    private fun item(code: String, addedAt: Long) = ItemSnapshot(
        listCode = "likes", videoCode = code, title = code, coverUrl = "c/$code", addedAt = addedAt,
    )

    // ── 关注作者：并集 ─────────────────────────────────────────────

    @Test
    fun `合并关注是并集`() {
        val a = AccountSnapshot(followedArtists = listOf(ref("Tru Kait", "/pornstar/tru-kait")))
        val b = AccountSnapshot(followedArtists = listOf(ref("持野蓬", "https://njavtv.com/actresses/x", "njav")))
        val merged = AccountSync.merge(a, b)
        assertEquals(2, merged.followedArtists.size)
        assertEquals(setOf("/pornstar/tru-kait", "https://njavtv.com/actresses/x"),
            merged.followedArtists.map { it.followKey }.toSet())
    }

    @Test
    fun `同一个作者只留一条 且信息更全的那条胜出`() {
        val sparse = AccountSnapshot(followedArtists = listOf(ref("Tru Kait", "/pornstar/tru-kait")))
        val rich = AccountSnapshot(
            followedArtists = listOf(ref("Tru Kait", "/pornstar/tru-kait", avatar = "http://a.jpg"))
        )
        // 顺序反过来也要得到同一条（合并必须可交换，否则冲突重试不会收敛）
        assertEquals("http://a.jpg", AccountSync.merge(sparse, rich).followedArtists.single().avatar)
        assertEquals("http://a.jpg", AccountSync.merge(rich, sparse).followedArtists.single().avatar)
    }

    @Test
    fun `只有名字没有地址的作者也能去重`() {
        val a = AccountSnapshot(followedArtists = listOf(ref("无名氏", "")))
        val b = AccountSnapshot(followedArtists = listOf(ref("无名氏", "")))
        assertEquals(1, AccountSync.merge(a, b).followedArtists.size)
    }

    // ── 观看记录：取 max(progress) + max(watchDate)（9.1 的老语义）────

    @Test
    fun `观看记录取更大进度与更晚时间`() {
        val a = AccountSnapshot(watchHistory = listOf(history("v1", progress = 100, watchDate = 1000)))
        val b = AccountSnapshot(watchHistory = listOf(history("v1", progress = 300, watchDate = 500)))
        val merged = AccountSync.merge(a, b).watchHistory.single()
        // 进度来自 b（300），时间来自 a（1000）：两个维度各取各的，不是整条二选一
        assertEquals(300L, merged.progress)
        assertEquals(1000L, merged.watchDate)
    }

    @Test
    fun `观看记录标题取看得更晚那次的`() {
        val a = AccountSnapshot(watchHistory = listOf(history("v1", 10, 100, title = "旧标题")))
        val b = AccountSnapshot(watchHistory = listOf(history("v1", 10, 900, title = "新标题")))
        assertEquals("新标题", AccountSync.merge(a, b).watchHistory.single().title)
        assertEquals("新标题", AccountSync.merge(b, a).watchHistory.single().title)
    }

    @Test
    fun `不同影片各留一条`() {
        val a = AccountSnapshot(watchHistory = listOf(history("v1", 1, 1)))
        val b = AccountSnapshot(watchHistory = listOf(history("v2", 1, 1)))
        assertEquals(2, AccountSync.merge(a, b).watchHistory.size)
    }

    // ── 清单：按 listCode + videoCode 并集 ─────────────────────────

    @Test
    fun `清单条目并集且同一条目取更新的 addedAt`() {
        val a = AccountSnapshot(lists = listOf(
            ListSnapshot("likes", "favorite", "我喜欢", items = listOf(item("v1", 100), item("v2", 200)))
        ))
        val b = AccountSnapshot(lists = listOf(
            ListSnapshot("likes", "favorite", "我喜欢", items = listOf(item("v1", 999), item("v3", 300)))
        ))
        val list = AccountSync.merge(a, b).lists.single()
        assertEquals(setOf("v1", "v2", "v3"), list.items.map { it.videoCode }.toSet())
        assertEquals(999L, list.items.first { it.videoCode == "v1" }.addedAt)
    }

    @Test
    fun `自定义清单按 listCode 合并且元数据取 updatedAt 较大的`() {
        val a = AccountSnapshot(lists = listOf(
            ListSnapshot("local_1", "playlist", "旧名", updatedAt = 10, items = listOf(item("v1", 1)))
        ))
        val b = AccountSnapshot(lists = listOf(
            ListSnapshot("local_1", "playlist", "新名", updatedAt = 20, items = listOf(item("v2", 1)))
        ))
        val list = AccountSync.merge(a, b).lists.single()
        assertEquals("新名", list.title)
        assertEquals(setOf("v1", "v2"), list.items.map { it.videoCode }.toSet())
    }

    // ── 编解码与幂等 ───────────────────────────────────────────────

    @Test
    fun `编解码往返不丢字段`() {
        val snapshot = AccountSnapshot(
            followedArtists = listOf(ref("Tru Kait", "/pornstar/tru-kait")),
            lists = listOf(ListSnapshot("likes", "favorite", "我喜欢", items = listOf(item("v1", 5)))),
            watchHistory = listOf(history("v1", 42, 7)),
        )
        val round = AccountSync.decode(AccountSync.encode(snapshot))
        assertEquals(snapshot.followedArtists, round.followedArtists)
        assertEquals(snapshot.lists, round.lists)
        assertEquals(snapshot.watchHistory, round.watchHistory)
    }

    @Test
    fun `未知字段不影响解码（服务端只当不透明文档 客户端可以加字段）`() {
        val json = """{"schema":1,"followedArtists":[{"name":"A","url":"/pornstar/a","site":"pornhub","futureField":123}],"brandNewSection":{"x":1}}"""
        val decoded = AccountSync.decode(json)
        assertEquals(1, decoded.followedArtists.size)
        assertEquals("/pornstar/a", decoded.followedArtists.single().url)
    }

    @Test
    fun `空文档解码成空快照而不是抛异常`() {
        assertTrue(AccountSync.decode("").followedArtists.isEmpty())
        assertTrue(AccountSync.decode("{}").watchHistory.isEmpty())
    }

    @Test
    fun `合并是幂等的`() {
        val a = AccountSnapshot(
            followedArtists = listOf(ref("A", "/pornstar/a")),
            watchHistory = listOf(history("v1", 10, 100)),
        )
        val merged = AccountSync.merge(a, a)
        assertEquals(AccountSync.encode(AccountSync.merge(merged, merged)), AccountSync.encode(merged))
        assertEquals(merged.followedArtists, AccountSync.merge(merged, merged).followedArtists)
    }

    // ── 差异统计（界面那句「新增了 N 条」）─────────────────────────

    @Test
    fun `差异只统计云端带来的新增`() {
        val local = AccountSnapshot(
            followedArtists = listOf(ref("A", "/pornstar/a")),
            watchHistory = listOf(history("v1", 1, 1)),
        )
        val merged = AccountSync.merge(
            local,
            AccountSnapshot(
                followedArtists = listOf(ref("A", "/pornstar/a"), ref("B", "/pornstar/b")),
                watchHistory = listOf(history("v1", 5, 2), history("v2", 1, 1)),
                lists = listOf(ListSnapshot("likes", "favorite", "", items = listOf(item("v9", 1)))),
            ),
        )
        val diff = AccountSync.diff(local, merged)
        assertEquals(1, diff.newFollows)
        assertEquals(1, diff.newHistory)
        assertEquals(1, diff.newListItems)
    }

    @Test
    fun `本机就是全部时差异为空`() {
        val local = AccountSnapshot(followedArtists = listOf(ref("A", "/pornstar/a")))
        assertTrue(AccountSync.diff(local, local).isEmpty)
    }
}
