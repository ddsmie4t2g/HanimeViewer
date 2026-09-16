package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.han1meviewer.logic.model.AppSettings
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.logic.model.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Before
import org.junit.Test

/**
 * [FollowedArtistStore] 的**并发与身份判定**回归测试（27.0.1）。
 *
 * 钉住的是用户 2026-09-16 报的那一串：
 * 从关注列表进作者页显示「取消关注」、从视频详情页进却显示「关注」；
 * 关注完过一会儿又变回去；取关了又被后台的头像补全/新作检查"复活"。
 *
 * 根因都在这里：写入不是事务性的、认人只比键串。
 * ⚠️ 用一个带 50 ms 延迟的假 store 来放大竞态 —— 真 DataStore 也是串行的，只是没那么好复现。
 */
class FollowedArtistStoreTest {
    companion object {
        private val store = object : SettingsStore {
            override val settings = MutableStateFlow(AppSettings())
            override suspend fun update(transform: (AppSettings) -> AppSettings) {
                delay(50)
                settings.value = transform(settings.value)
            }
        }

        @JvmStatic
        @BeforeClass
        fun installStore() = SettingsRepository.install(store)
    }

    @Before
    fun reset() { store.settings.value = AppSettings() }

    /**
     * ⭐ 老关注记录（只有站点给的显示名那一种写法）必须能从**另一个入口**认出已关注，
     * 并且能从那一个入口取关掉。
     *
     * 这就是「从关注列表点进去显示『取消关注』、从视频点进去显示『关注』」的反面。
     */
    @Test
    fun legacyFollowIsVisibleFromTranslatedVideoAuthorAndCanBeRemoved() = runBlocking {
        val gallery = ArtistRef("释アリス", url = "https://njavtv.com/dm288/cn/actresses/釋アリス", site = "njav")
        val video = ArtistRef("释アリス", url = "https://njavtv.com/cn/actresses/释アリス", site = "njav")
        assertTrue(FollowedArtistStore.toggle(gallery))
        assertTrue(FollowedArtistStore.isFollowed(video))
        assertFalse(FollowedArtistStore.toggle(video))
        assertFalse(FollowedArtistStore.isFollowed(gallery))
        assertTrue(FollowedArtistStore.all.isEmpty())
    }

    /**
     * ⚠️ 两次关注**同时**发生（用户连点两下、或两个界面各自关注一个人）时，
     * 两条都必须留下来 —— 以前是「读一份 → 改 → 整份写回」，后写的会吃掉先写的。
     */
    @Test
    fun concurrentFollowsPreserveBothAuthors() = runBlocking {
        listOf("first", "second").map { name ->
            async { FollowedArtistStore.toggle(ArtistRef(name, url = "/model/$name", site = "pornhub")) }
        }.awaitAll()
        assertEquals(2, FollowedArtistStore.all.size)
    }

    /**
     * ⚠️ 后台的「补资料」绝不能把刚发生的取关**补回来**。
     *
     * `enrich` 由作者页加载完触发，与用户的取关天然并发。
     */
    @Test
    fun avatarRefreshCannotRestoreAnUnfollowedAuthor() = runBlocking {
        val author = ArtistRef("Example", url = "/model/example", site = "pornhub")
        FollowedArtistStore.toggle(author)
        val unfollow = async { FollowedArtistStore.toggle(author) }
        val refresh = async { FollowedArtistStore.enrich(author.copy(avatar = "https://example.com/avatar.jpg")) }
        unfollow.await()
        refresh.await()
        assertFalse(FollowedArtistStore.isFollowed(author))
    }

    /**
     * ⭐ 关注/取关必须**落盘完成后才返回**：调用方紧接着要做的是「上传账号数据」
     * 和「跳去别的界面」，返回时没写完就会把旧列表传上去、或让新界面读到旧状态。
     */
    @Test
    fun followAndUnfollowAreCommittedBeforeReturningToAnotherScreen() = runBlocking {
        val author = ArtistRef("Example", url = "/model/example", site = "pornhub")
        assertTrue(FollowedArtistStore.toggle(author))
        assertTrue("Another screen must immediately see the committed follow", FollowedArtistStore.isFollowed(author.followKey))
        assertFalse(FollowedArtistStore.toggle(author))
        assertFalse(FollowedArtistStore.isFollowed(author.followKey))
    }
}
