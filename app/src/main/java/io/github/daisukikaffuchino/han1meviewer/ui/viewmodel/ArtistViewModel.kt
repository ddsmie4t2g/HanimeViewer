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
 * ## 修「最多只显示十页」
 *
 * 用户报的是：**不管作者还是女优有多少视频，最多显示十页**。两个原因叠在一起，
 * 都在 [ArtistPaging] 那边钉着（那份纯数学有单测）：
 *
 * 1. [knownTotalPages] 拗不出数字（`87 Videos` / `5668 部影片` 里抠出来的字符串带尾巴，
 *    `toIntOrNull()` 恒 null）⇒ 总页数**只剩「已加载条数 / 12」这一个来源**；
 * 2. 「下一页」当时要求「还没到总页数」⇒ 站在最后一页就**死锁**了：想加载更多必须先点下一页，
 *    想点下一页又必须先加载更多。已加载条数正好是 12 的整数倍时（Pornhub 作者页 40–49 条/页
 *    ×3 ≈ 120 条 = 10 页）就正好卡在十页。
 *
 * 现在：总数照站点公布的值算，「下一页」只看「本地有没有下一页 / 站点还有没有」。
 *
 * ## 总页数的两条来源（第二条是这次补上的）
 *
 * 1. **作品数文案**（`87 Videos` / `5668 部影片`）—— Pornhub 详情页的主模特块、nJAV 女优卡片带；
 * 2. **站点自己的总页数** —— hanime 的合成作者页两条文案都没有，改成读搜索页页码条里的末页号
 *    （`ArtistVideosPage.siteTotalPages` + [ArtistPaging.pagesFromSitePages]，是上界估计）。
 *
 * 两条都拿不到时按「已加载条数」长，且**永远能继续翻**（这就是上面那个死锁的修法）。
 *
 * ⚠️ 两种「页」是分开的，别混：
 * - **应用内一页 12 条**：[page]（用户看到的、也是唯一该给用户看的口径）；
 * - **站点一页 30–59 条**：内部累积用，用户翻到第 12 条之外时自动去续拉。
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

    /**
     * 站点那边这份列表**一共几页**（站点自己的分页口径；拿不到为 null）。
     *
     * 目前只有 hanime 的合成作者页给（搜索页页码条里的末页号，见
     * [NetworkRepo.getArtistVideos] → `Parser.hanimeSearchTotalPages`）——
     * Pornhub / nJAV 的作者页头部有「共 N 部影片」，那条线走 [knownTotalPages] 的文案解析。
     */
    private var siteTotalPages: Int? = null

    /**
     * 实测「站点一页几条」（拉回来的站点页里最大的那一页）。
     *
     * 把站点页数换算成应用内页数要用它（站点不给这个数）。取**最大**而不是第一页：
     * 站点页被解析器去重/过滤后条数会偏小，取最大才接近站点自己的一页容量。
     */
    private var sitePageSize = 0

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
        // 换人 / 换排序 / 换筛选都作废：站点总页数与实测站点页条数都是「上一份列表」的。
        siteTotalPages = null
        sitePageSize = 0
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
     * 跳到第 [target] 页（分页条用它，[nextPage] / [prevPage] 也是）。
     *
     * 三种情形：
     * - **往回翻**：本地已经有那 12 条，直接换页，不联网；
     * - **往前翻、本地已攒够**：同上；
     * - **往前翻、本地不够**（用户直接点了第 5 页）：记下目标，交给 [loadRemotePage]
     *   **连着**拉够为止 —— 这是 26.8 会「点了没反应」的地方。
     *
     * ⚠️ 这里的 [ArtistUiState.canNext] 是**唯一**的前进闸门，它现在只看
     * 「本地有没有下一页 / 站点还有没有」，不再看「到没到总页数」——
     * 后者会让用户永远停在最后一页（见 [ArtistPaging] 与类注释里的「最多十页」）。
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
     * ⚠️ 一次「跳到第 n 页」可能需要连着拉**好几个站点页**：站点一页给 30–59 条，
     * 而应用内一页只有 [PAGE_SIZE] 条。所以这里是个循环，直到
     * 「本地攒够了第 [pendingPage] 页要的条数」或「站点那边没有了」为止。
     * 26.8 的版本只拉一次，于是用户从第 1 页直接点第 5 页时会停在第 1 页不动
     * （`pendingPage` 被丢掉），看起来就是「点了没反应」。
     *
     * ⚠️ 但一次跳页**最多补拉 [MAX_SITE_PAGES_PER_JUMP] 个站点页**：现在总页数可能来自
     * 站点公布的作品总数（几千条 ⇒ 上百页），页码条上的数字点一下就一路拉到底的话，
     * 那是几十次 1.2 MB（Pornhub 作者页）的请求 —— 站点不乐意，用户也只看到转圈。
     * 到上限就停在「现在能画出来的最后一页」，用户再点一次继续（分页条会如实显示进度）。
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
        // ⭐ 「用户想去第几页」在发请求前**定格**，整个手势都用它。
        //    collect 里会把 pendingPage 清掉，若每轮循环都重新读它，第二轮读到的就是 null
        //    ⇒ 循环提前 break，一次跳页**只拉两批**（26.8 那句「点了没反应」的另一半：
        //    即使按钮点得动，点第 40 页也只前进两三页）。
        //    不会有别的操作在补拉期间改它：`loading` 为真时 goToPage / loadRemotePage 直接返回。
        val appTarget = pendingPage
        _state.value = _state.value.copy(
            state = if (loaded.isEmpty()) PageLoadingState.Loading else _state.value.state,
            isPaging = loaded.isNotEmpty(),
        )
        viewModelScope.launch {
            var fetchedPages = 0
            while (true) {
                val remoteTarget = remotePage
                var stop = false
                fetchedPages++
                NetworkRepo.getArtistVideos(artist, remoteTarget, sort, filter).collect { result ->
                    when (result) {
                        is PageLoadingState.Success -> {
                            val info = result.info
                            val existing = loaded.mapTo(mutableSetOf()) { it.videoCode }
                            val fresh = info.videos.filterNot { it.videoCode in existing }
                            loaded = loaded + fresh
                            // ⭐ 站点总页数 / 实测站点页条数：分页条「一共多少页」的另一条来源
                            // （hanime 的作者页没有「共 N 部影片」文案，只有这一条）。见 knownTotalPages。
                            info.siteTotalPages?.takeIf { it > 0 }?.let { siteTotalPages = it }
                            sitePageSize = maxOf(sitePageSize, info.videos.size)
                            // 「这一批有没有新东西」比「站点说没说还有下一页」可靠：
                            // 两个站点的空页/重复页形态都不一样。
                            remoteHasMore = info.videos.isNotEmpty() && fresh.isNotEmpty()
                            remotePage = remoteTarget + 1
                            pendingPage = null
                            val next = pageState(appTarget ?: _state.value.page)
                            // ⚠️ 这里**不**清 isPaging：一次跳页可能要连着补拉好几批
                            // （见函数注释的上限），第一批回来就熄灯的话，用户会以为已经好了，
                            // 而后面几批还在路上。统一在循环结束后清。
                            _state.value = next.copy(
                                profile = info.profile ?: _state.value.profile,
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
                // 见函数注释：一次跳页的补拉上限。
                if (fetchedPages >= MAX_SITE_PAGES_PER_JUMP) break
            }
            loading = false
            // ⭐ 小转圈到这里才熄：一批回来就熄会让用户以为「已经好了」，而后面几批还在路上。
            _state.value = _state.value.copy(isPaging = false)
        }
    }

    /**
     * 由 [loaded] 算出「第 page 页」的状态（12 条 + 翻页可用性）。
     *
     * 口径全部在 [ArtistPaging.resolve] 里（纯数学、有单测）：总数优先用站点公布的作品数，
     * 拿不到就按已加载条数算；「下一页」只看「本地有没有 / 站点还有没有」，
     * **不**看「到没到总页数」—— 那正是「最多十页」的死锁来源。
     */
    private fun pageState(page: Int): ArtistUiState {
        val verdict = ArtistPaging.resolve(
            requested = page,
            loadedCount = loaded.size,
            knownTotalPages = knownTotalPages(),
            remoteHasMore = remoteHasMore,
            pageSize = PAGE_SIZE,
        )
        val slice = loaded.drop((verdict.page - 1) * PAGE_SIZE).take(PAGE_SIZE)
        return _state.value.copy(
            videos = slice,
            page = verdict.page,
            totalPages = verdict.totalPages,
            canPrev = verdict.canPrev,
            canNext = verdict.canNext,
            state = if (loaded.isEmpty()) PageLoadingState.NoMoreData
            else PageLoadingState.Success(loaded),
        )
    }

    /**
     * 站点公布的作品数 / 站点自己的页数 → 总页数；两条都拿不到就是 null。
     *
     * 作者页头部那点信息比「已加载条数」权威得多 —— 它是站点自己算的，
     * 而且**第一页就拿到了**，所以分页条一进页面就能画出完整的「1 2 3 … N」。
     *
     * 两条来源（取大的那个，宁可估大也不估小 —— 估小会让用户以为「已经到最后一页了」）：
     * 1. **作品数文案**（`87 Videos` / `5668 部影片`）：Pornhub、nJAV 的卡片带这句；
     *    文案怎么解析（含为什么 `1.2K` 不能信）见 [ArtistPaging.pagesFromCountText]。
     * 2. **站点自己的总页数**（hanime 搜索页页码条里的末页号）⇒ [ArtistPaging.pagesFromSitePages]。
     *    hanime 的合成作者页两条文案都没有，只有这一条。
     */
    private fun knownTotalPages(): Int? {
        // ⚠️ nJAV 的女优页站点不给分页（`?page=` 只回反爬挑战页，见 NetworkRepo.njavArtistFlow），
        // 而卡片上那个「5668 部影片」是**站点全站**的口径 —— 拿它画页码条，用户点第 12 页
        // 只会得到一次「点了没反应」。这条路不认作品数文案。
        val byCountText = if (_state.value.artist.siteSource == SiteSource.Njav) {
            null
        } else {
            ArtistPaging.pagesFromCountText(
                _state.value.profile?.videoCount?.takeIf { it.isNotBlank() }
                    ?: _state.value.artist.videoCount.takeIf { it.isNotBlank() },
                PAGE_SIZE,
            )
        }
        val bySitePages = ArtistPaging.pagesFromSitePages(siteTotalPages, sitePageSize, PAGE_SIZE)
        return listOfNotNull(byCountText, bySitePages).maxOrNull()
    }

    companion object {
        /** ⭐ 一页 12 条。 */
        const val PAGE_SIZE = 12

        /** ⭐ 2 列 —— 12 条正好 6 行。9.0 从 3 列改过来：3 列时封面太窄，标题看不清。 */
        const val COLUMNS = 2

        /**
         * ⭐ 一次跳页最多补拉几个**站点页**（站点一页 30–59 条）。
         *
         * 站点公布的作品总数可能是几千条 ⇒ 上百页，页码条点一下就一路拉到底的话，
         * 那是几十次 1.2 MB（Pornhub 作者页）的请求。到上限先停在「现在能画出来的最后一页」，
         * 用户再点一次继续 —— 比「转圈转到天荒地老」和「点了没反应」都好。
         */
        private const val MAX_SITE_PAGES_PER_JUMP = 20
    }
}
