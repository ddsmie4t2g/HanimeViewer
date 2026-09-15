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
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavNetwork
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 作者页的状态。
 *
 * ## 26.8：作品列表改成**翻页**（12 条/页）
 *
 * 之前是「一次加载、无限往下滚」：站点一页给 30–49 条，用户看到一条长瀑布，
 * 既不知道「到底有多少」，也没法回到「刚才那一页」。现在固定 **一页 12 条**
 * ＋ 上一页/下一页。
 *
 * ## 9.0 两处改动
 *
 * 1. **排版改成 2 列 × 6 行**（原来是 3 列 × 4 行）。3 列时每格封面被压得很窄，
 *    封面上的编号/标题看不清 —— 用户明确要求「一行两个，一页六行」。
 * 2. **总页数一次算准**：总页数改为优先取**站点公布的作品总数**（见 [knownTotalPages]），
 *    不再只按「已加载条数 / 12」一页页长出来。旧口径下用户站在第 1 页永远看到
 *    「1 / 1」，翻一页变「2 / 2」，「到底有多少页」这件事从头到尾没人告诉他，
 *    分页条也就没法画出「1 2 3 … N」。
 *
 * ⚠️ 两种「页」是分开的，别混：
 * - **应用内一页 12 条**：[page]（用户看到的、也是唯一该给用户看的口径）；
 * - **站点一页 30–49 条**：内部累积用，用户翻到第 12 条之外时自动去续拉。
 *
 * @param videos **当前这一页**的 12 条
 * @param page 当前页（1 起）
 * @param totalPages 总页数：优先由站点公布的作品总数算出，拿不到才退回「已加载条数 / 12」
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
     * nJAV 的头像补齐（26.8 新增，26.8.2 提速，26.8.3 改成**双路查缓存**）。
     *
     * 视频详情页只给女优名字、女优页顶部也是首字占位符 ⇒ 从 nJAV 过来的作者**天生没有头像**，
     * 关注列表里就是一排空白。这里是三条依次尝试的路径：
     *
     * 1. **先按女优路径查缓存**（26.8.3 新增）：作者页的 url 里就带着
     *    `…/actresses/<编码名>`，而浏览过女优一览 / 排行之后缓存里就有同一段路径。
     *    这比按名字查可靠 —— 详情页给的名字和索引页可能是繁简两种写法。
     * 2. 再按名字查缓存（26.8.2 的老路径，老记录 / 只有名字的条目走这条）。
     * 3. 都没有才联网翻索引页（内部并行 + 回填缓存，见 `NetworkRepo.findNjavActress`）。
     *
     * 命中缓存时是**同步**的：头像和资料头同帧画出来，不会出现「先占位符、过一秒才变头像」。
     *
     * ⚠️ 别把「联网那一步」去掉：第一次进作者页时缓存很可能是空的
     * （`NjavActressCache` 只装浏览过的女优），那时只能靠它去补。
     */
    private fun resolveMissingAvatarIfNeeded() {
        val artist = _state.value.artist
        if (artist.siteSource != SiteSource.Njav) return
        if (artist.avatar.isNotBlank() || artist.name.isBlank()) return

        // 1) 本地缓存 · 按女优路径（最稳）
        val path = runCatching { NjavNetwork.actressPathFrom(artist.url) }.getOrNull()
        path?.let { p ->
            NjavActressCache.findByPath(p)?.let { cached ->
                applyResolvedAvatar(cached.avatarUrl, cached.videoCount)
                return
            }
        }

        // 2) 本地缓存 · 按名字
        NjavActressCache.find(artist.name)?.let { cached ->
            applyResolvedAvatar(cached.avatarUrl, cached.videoCount)
            return
        }

        // 3) 未命中才联网：先抓当月排行（一页 100 位）再翻索引页。
        viewModelScope.launch {
            val found = runCatching { NetworkRepo.findNjavActress(artist.name) }.getOrNull() ?: return@launch
            // 详情页给的名字与卡片上的写法可能不同（繁简）—— 把这次查到的头像
            // 也记到这个写法下，下次进作者页就是命中缓存、同帧出图。
            runCatching { NjavActressCache.rememberAlias(artist.name, found) }
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
        // ⚠️ 用 enrich（就地补资料）而不是 toggle 两下：后者会先删后加，
        //    把这个人从关注顺序里挪到末尾 —— 用户会看到「什么都没干、顺序变了」。
        viewModelScope.launch {
            runCatching { FollowedArtistStore.enrich(enriched) }
        }
    }

    /** 重试（首页失败、以及翻页补拉失败都走它）。 */
    fun retry() {
        if (boundKey == null || loading) return
        _state.value = _state.value.copy(state = PageLoadingState.Loading)
        loadRemotePage()
    }

    fun nextPage() = goToPage(_state.value.page + 1)

    fun prevPage() = goToPage(_state.value.page - 1)

    /**
     * 跳到第 [target] 页（9.0 的分页条用它，[nextPage] / [prevPage] 也是）。
     *
     * 三种情形：
     * - **往回翻**：本地已经有那 12 条，直接换页，不联网；
     * - **往前翻、本地已攒够**：同上；
     * - **往前翻、本地不够**（用户直接点了第 5 页）：记下目标，交给 [loadRemotePage]
     *   **连着**拉够为止 —— 这是 26.8 会「点了没反应」的地方。
     */
    fun goToPage(target: Int) {
        val current = _state.value
        if (loading) return
        val safe = target.coerceAtLeast(1)
        if (safe == current.page) return
        if (safe < current.page) {
            // 往回翻永远不需要联网：已经看过的那几页一定还在 loaded 里。
            _state.value = pageState(safe)
            return
        }
        if (!current.canNext) return
        if (loaded.size >= safe * PAGE_SIZE || !remoteHasMore) {
            _state.value = pageState(safe)
            return
        }
        pendingPage = safe
        loadRemotePage()
    }

    /**
     * 拉取 / 续拉站点数据。
     *
     * ⚠️ 一次「跳到第 n 页」可能需要连着拉**好几个站点页**：站点一页给 30–49 条，
     * 而应用内一页只有 [PAGE_SIZE] 条。所以这里是个循环，直到
     * 「本地攒够了第 [pendingPage] 页要的条数」或「站点那边没有了」为止。
     * 26.8 的版本只拉一次，于是用户从第 1 页直接点第 5 页时会停在第 1 页不动
     * （`pendingPage` 被丢掉），看起来就是「点了没反应」。
     */
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
        _state.value = _state.value.copy(
            state = if (loaded.isEmpty()) PageLoadingState.Loading else _state.value.state,
            isPaging = loaded.isNotEmpty(),
        )
        viewModelScope.launch {
            while (true) {
                // 「拉哪个站点页」「拉完要跳到第几页」都在发请求前定格 ——
                // collect 里会把 pendingPage 清掉，循环条件得用这两个快照。
                val remoteTarget = remotePage
                val appTarget = pendingPage
                var stop = false
                NetworkRepo.getArtistVideos(artist, remoteTarget, sort, filter).collect { result ->
                    when (result) {
                        is PageLoadingState.Success -> {
                            val info = result.info
                            val existing = loaded.mapTo(mutableSetOf()) { it.videoCode }
                            val fresh = info.videos.filterNot { it.videoCode in existing }
                            loaded = loaded + fresh
                            // 「这一批有没有新东西」比「站点说没说还有下一页」可靠：
                            // 两个站点的空页/重复页形态都不一样。
                            remoteHasMore = info.videos.isNotEmpty() && fresh.isNotEmpty()
                            remotePage = remoteTarget + 1
                            pendingPage = null
                            val next = pageState(appTarget ?: _state.value.page)
                            _state.value = next.copy(
                                profile = info.profile ?: _state.value.profile,
                                isPaging = false,
                            )
                            // ⭐ 9.0：打开作者页第一页 = 这位作者的新作都知道了 ⇒ 角标清零。
                            // 只在站点第 1 页做，往后翻页不该反复改写关注表。
                            if (remoteTarget == 1) {
                                val codes = loaded.take(PAGE_SIZE).map { it.videoCode }
                                viewModelScope.launch {
                                    runCatching { FollowedArtistStore.markSeen(artist, codes) }
                                }
                            }
                        }

                        is PageLoadingState.NoMoreData -> {
                            remoteHasMore = false
                            pendingPage = null
                            val profile = _state.value.profile
                            _state.value = if (loaded.isEmpty() && appTarget == null) {
                                _state.value.copy(state = PageLoadingState.NoMoreData, isPaging = false)
                            } else {
                                pageState(appTarget ?: _state.value.page).copy(
                                    profile = profile,
                                    isPaging = false,
                                )
                            }
                            stop = true
                        }

                        is PageLoadingState.Error -> {
                            pendingPage = null
                            _state.value = _state.value.copy(
                                state = PageLoadingState.Error(result.throwable),
                                isPaging = false,
                            )
                            stop = true
                        }

                        is PageLoadingState.Loading -> Unit
                    }
                }
                if (stop) break
                // 只有「用户点了一个更远的页、而本地还不够」时才继续拉。
                val want = appTarget ?: break
                if (!remoteHasMore || loaded.size >= want * PAGE_SIZE) break
            }
            loading = false
        }
    }

    /**
     * 由 [loaded] 算出「第 page 页」的状态（12 条 + 翻页可用性）。
     *
     * 总页数见 [knownTotalPages]：站点公布了作品总数就用它一次算准，
     * 否则退回「已加载条数 / 12」（至少不会比现实小，[maxOf] 保证）。
     */
    private fun pageState(page: Int): ArtistUiState {
        val loadedPages = maxOf(1, (loaded.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val displayTotal = maxOf(knownTotalPages() ?: 1, loadedPages)
        // 站点给不出那么多页时（作品总数含未上架的 / 站点自己有分页上限），
        // 别把用户送进一个空白页 —— 退到「本地实际拿得到的最后一页」。
        val reachable = if (remoteHasMore) displayTotal else loadedPages
        val safePage = page.coerceIn(1, maxOf(1, reachable))
        val slice = loaded.drop((safePage - 1) * PAGE_SIZE).take(PAGE_SIZE)
        return _state.value.copy(
            videos = slice,
            page = safePage,
            totalPages = displayTotal,
            canPrev = safePage > 1,
            canNext = safePage < displayTotal && (remoteHasMore || loaded.size > safePage * PAGE_SIZE),
            state = if (loaded.isEmpty()) PageLoadingState.NoMoreData
            else PageLoadingState.Success(loaded),
        )
    }

    /**
     * 站点公布的「共 N 部影片」→ 总页数；拿不到 / 解析不出数字就是 null。
     *
     * 作者页头部的作品数比「已加载条数」权威得多 —— 它是站点自己算的，
     * 而且**第一页就拿到了**，所以分页条一进页面就能画出完整的「1 2 3 … N」。
     *
     * ⚠️ 只抽数字是刻意的：站点文案五花八门（`1,234` / `1234 部影片` / `1234 videos`），
     * 去猜格式反而会在站点改文案时出错；抽不出数字或抽到 0 就当「不知道」，
     * 让调用方退回旧口径。
     */
    private fun knownTotalPages(): Int? {
        val raw = _state.value.profile?.videoCount?.takeIf { it.isNotBlank() }
            ?: _state.value.artist.videoCount.takeIf { it.isNotBlank() }
            ?: return null
        val digits = COUNT_DIGITS.find(raw)?.groupValues?.get(1)?.take(9) ?: return null
        val count = digits.toIntOrNull() ?: return null
        if (count <= 0) return null
        return (count + PAGE_SIZE - 1) / PAGE_SIZE
    }

    companion object {
        /** ⭐ 一页 12 条。 */
        const val PAGE_SIZE = 12

        /** ⭐ 2 列 —— 12 条正好 6 行。9.0 从 3 列改过来：3 列时封面太窄，标题看不清。 */
        const val COLUMNS = 2

        /**
         * 作品数文案里的第一个数字串（允许 `,` / 空格 / `.` 作千分位分隔符）。
         * 见 [knownTotalPages] 里关于「为什么不猜格式」的说明。
         */
        private val COUNT_DIGITS = Regex("""(\d[\d,\s.]*)""")
    }
}
