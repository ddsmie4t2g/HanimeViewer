package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.daisukikaffuchino.han1meviewer.logic.FollowedArtistStore
import io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistProfile
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavActressCache
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 作者页的状态。
 *
 * ## 26.8：作品列表改成**翻页**（12 条/页，3 列 × 4 行）
 *
 * 之前是「一次加载、无限往下滚」：站点一页给 30–49 条，用户看到一条长瀑布，
 * 既不知道「到底有多少」，也没法回到「刚才那一页」。现在固定 **一页 12 条**
 * （3 列 × 4 行）＋ 上一页/下一页，页数随加载增长。
 *
 * ⚠️ 两种「页」是分开的，别混：
 * - **应用内一页 12 条**：[page]（用户看到的、也是唯一该给用户看的口径）；
 * - **站点一页 30–49 条**：内部累积用，用户翻到第 12 条之外时自动去续拉。
 *
 * @param videos **当前这一页**的 12 条
 * @param page 当前页（1 起）
 * @param totalPages 目前**已知**的页数（= 已加载条数 / 12，向上取整）
 * @param canPrev / [canNext] 翻页按钮是否可用（[canNext] 还包含「站点那边可能还有」）
 * @param isPaging 正在为翻页补拉站点数据（界面显示小转圈，而不是整页 loading）
 */
data class ArtistUiState(
    val artist: ArtistRef = ArtistRef(name = ""),
    val profile: ArtistProfile? = null,
    val videos: List<HanimeInfo> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val canPrev: Boolean = false,
    val canNext: Boolean = false,
    val state: PageLoadingState<*> = PageLoadingState.Loading,
    val isPaging: Boolean = false,
    /** nJAV 女优页排序（`sort=`），null = 站点默认。其它站点恒为 null。 */
    val sort: String? = null,
    /** nJAV 女优页筛选（`filters=`），null = 全部。其它站点恒为 null。 */
    val filter: String? = null,
)

/**
 * 作者页 ViewModel。
 *
 * 与任何站点账号无关：它读的是站点公开的作者页，不需要登录。
 */
class ArtistViewModel : ViewModel() {

    private val _state = MutableStateFlow(ArtistUiState())
    val state: StateFlow<ArtistUiState> = _state.asStateFlow()

    /** 已从站点累积下来的全部作品（跨多个「站点页」）。 */
    private var loaded: List<HanimeInfo> = emptyList()

    /** 站点那边的页码（内部累积用，与用户看到的 [ArtistUiState.page] 不是一回事）。 */
    private var remotePage = 1
    private var remoteHasMore = true
    private var loading = false

    /** 拉到新一批后要跳到第几页（null = 停在原地）。 */
    private var pendingPage: Int? = null

    private var boundKey: String? = null

    fun bind(artist: ArtistRef) {
        val key = artist.followKey
        if (key.isEmpty() || key == boundKey) return
        boundKey = key
        _state.value = ArtistUiState(artist = artist, state = PageLoadingState.Loading)
        restart(keepSort = false)
        resolveMissingAvatarIfNeeded()
    }

    /** nJAV 的排序（`sort=`）。切换会重头开始翻页 —— 换了排序，之前的页就不作数了。 */
    fun setSort(value: String?) {
        if (_state.value.sort == value) return
        _state.value = _state.value.copy(sort = value)
        restart(keepSort = true)
    }

    /** nJAV 的筛选（`filters=`）。 */
    fun setFilter(value: String?) {
        if (_state.value.filter == value) return
        _state.value = _state.value.copy(filter = value)
        restart(keepSort = true)
    }

    /** 清空已加载内容后重新开始（排序/筛选变化，以及首次绑定时用）。 */
    private fun restart(keepSort: Boolean) {
        loaded = emptyList()
        remotePage = 1
        remoteHasMore = true
        loading = false
        pendingPage = null
        val current = _state.value
        _state.value = current.copy(
            videos = emptyList(),
            page = 1,
            totalPages = 1,
            canPrev = false,
            canNext = false,
            state = PageLoadingState.Loading,
            isPaging = false,
            sort = if (keepSort) current.sort else null,
            filter = if (keepSort) current.filter else null,
        )
        loadRemotePage()
    }

    /**
     * nJAV 的头像补齐（26.8 新增，26.8.2 提速）。
     *
     * 视频详情页只给女优名字、女优页顶部也是首字占位符 ⇒ 从 nJAV 关注过来的作者**天生没有头像**，
     * 关注列表里就是一排空白。这里按名字去**女优索引**里找一次（最多 3 页），
     * 找到就补进页面头部，并且 —— 如果这个人已在关注表里 —— **顺手写回关注表**，
     * 让「关注列表」也有头像（一次补齐，之后不必再找）。
     *
     * ⭐ 26.8.2 的顺序刻意是「**同步查本地缓存 → 不行才联网**」：
     * 缓存是同步的（读内存里的那份设置），命中时头像和资料头**同帧**就画出来了，
     * 不会出现「先占位符、过一秒才变头像」的闪动。用户报的正是这一点。
     */
    private fun resolveMissingAvatarIfNeeded() {
        val artist = _state.value.artist
        if (artist.siteSource != SiteSource.Njav) return
        if (artist.avatar.isNotBlank() || artist.name.isBlank()) return

        // 1) 本地缓存：浏览过女优一览 / 排行之后必中，0 网络、0 延迟。
        NjavActressCache.find(artist.name)?.let { cached ->
            applyResolvedAvatar(cached.avatarUrl, cached.videoCount)
            return
        }

        // 2) 未命中才联网（内部并行翻页 + 回填缓存，见 NetworkRepo.findNjavActress）。
        viewModelScope.launch {
            val found = runCatching { NetworkRepo.findNjavActress(artist.name) }.getOrNull() ?: return@launch
            applyResolvedAvatar(found.avatarUrl, found.videoCount)
        }
    }

    /** 把查到的头像 / 作品数贴进页面头部，并在已关注时写回关注表。 */
    private fun applyResolvedAvatar(avatarUrl: String, videoCount: Int?) {
        val avatar = avatarUrl.trim()
        if (avatar.isEmpty()) return
        val current = _state.value.artist
        if (current.avatar == avatar) return
        val enriched = current.copy(
            avatar = avatar,
            videoCount = current.videoCount.ifBlank {
                videoCount?.let { "$it 部影片" }.orEmpty()
            },
        )
        _state.value = _state.value.copy(artist = enriched)
        // 已在关注表里 → 写回，让关注列表也拿到头像。
        // ⚠️ 写回是挂起操作（要落盘），所以必须回到协程里；界面上的头像**不等它**。
        viewModelScope.launch {
            runCatching {
                if (FollowedArtistStore.isFollowed(enriched.followKey)) {
                    FollowedArtistStore.toggle(enriched)   // 已关注 → 先删
                    FollowedArtistStore.toggle(enriched)   // 再按新资料加回（等价于「更新」）
                }
            }
        }
    }

    /** 重试（首页失败、以及翻页补拉失败都走它）。 */
    fun retry() {
        if (boundKey == null || loading) return
        _state.value = _state.value.copy(state = PageLoadingState.Loading)
        loadRemotePage()
    }

    fun nextPage() {
        val current = _state.value
        if (loading || !current.canNext) return
        val target = current.page + 1
        if (loaded.size >= target * PAGE_SIZE || !remoteHasMore) {
            _state.value = pageState(target)   // 本地已经攒够了，直接翻
            return
        }
        pendingPage = target
        loadRemotePage()
    }

    fun prevPage() {
        val current = _state.value
        if (loading || !current.canPrev) return
        _state.value = pageState(current.page - 1)
    }

    private fun loadRemotePage() {
        if (loading || !remoteHasMore) {
            pendingPage?.let { target ->
                pendingPage = null
                _state.value = pageState(target)
            }
            return
        }
        loading = true
        val artist = _state.value.artist
        val sort = _state.value.sort
        val filter = _state.value.filter
        val page = remotePage
        _state.value = _state.value.copy(
            state = if (loaded.isEmpty()) PageLoadingState.Loading else _state.value.state,
            isPaging = loaded.isNotEmpty(),
        )
        viewModelScope.launch {
            NetworkRepo.getArtistVideos(artist, page, sort, filter).collect { result ->
                when (result) {
                    is PageLoadingState.Success -> {
                        val info = result.info
                        val existing = loaded.mapTo(mutableSetOf()) { it.videoCode }
                        val fresh = info.videos.filterNot { it.videoCode in existing }
                        loaded = loaded + fresh
                        // 「这一批有没有新东西」比「站点说没说还有下一页」可靠：
                        // 两个站点的空页/重复页形态都不一样。
                        remoteHasMore = info.videos.isNotEmpty() && fresh.isNotEmpty()
                        remotePage = page + 1
                        val target = pendingPage
                        pendingPage = null
                        val next = pageState(target ?: _state.value.page)
                        _state.value = next.copy(
                            profile = info.profile ?: _state.value.profile,
                            isPaging = false,
                        )
                    }

                    is PageLoadingState.NoMoreData -> {
                        remoteHasMore = false
                        val target = pendingPage
                        pendingPage = null
                        val profile = _state.value.profile
                        _state.value = if (loaded.isEmpty() && target == null) {
                            _state.value.copy(state = PageLoadingState.NoMoreData, isPaging = false)
                        } else {
                            pageState(target ?: _state.value.page).copy(
                                profile = profile,
                                isPaging = false,
                            )
                        }
                    }

                    is PageLoadingState.Error -> {
                        pendingPage = null
                        _state.value = _state.value.copy(
                            state = PageLoadingState.Error(result.throwable),
                            isPaging = false,
                        )
                    }

                    is PageLoadingState.Loading -> Unit
                }
            }
            loading = false
        }
    }

    /** 由 [loaded] 算出「第 page 页」的状态（12 条 + 翻页可用性）。 */
    private fun pageState(page: Int): ArtistUiState {
        val safePage = page.coerceAtLeast(1)
        val totalPages = maxOf(1, (loaded.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val slice = loaded.drop((safePage - 1) * PAGE_SIZE).take(PAGE_SIZE)
        return _state.value.copy(
            videos = slice,
            page = safePage,
            totalPages = totalPages,
            canPrev = safePage > 1,
            canNext = safePage * PAGE_SIZE < loaded.size || remoteHasMore,
            state = if (loaded.isEmpty()) PageLoadingState.NoMoreData
            else PageLoadingState.Success(loaded),
        )
    }

    companion object {
        /** ⭐ 一页 12 条。 */
        const val PAGE_SIZE = 12

        /** ⭐ 3 列 —— 12 条正好 4 行。 */
        const val COLUMNS = 3
    }
}
