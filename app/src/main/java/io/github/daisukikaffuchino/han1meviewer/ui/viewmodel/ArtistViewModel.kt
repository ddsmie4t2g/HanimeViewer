package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.daisukikaffuchino.han1meviewer.R
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
 * ## ⭐⭐ 26.9.4：分页重做 —— **一页 = 站点自己的一页**
 *
 * 26.8 起是「应用内固定 12 条一页」（2 列 × 6 行），总页数用「站点公布的作品数 ÷ 12」倒推。
 * 用户在 26.9.3 上把三个毛病一起报了上来（详见 [ArtistPaging] 的类注释）：
 *
 * - **末页 404**：「不足以支持 12 个的那一页」要凑满 12 条就得去要站点**下一页**，
 *   而那一页已经不存在 ⇒ 站点 404 ⇒ 用户明明还有 6 部作品却翻不过去；
 * - **作品遗失**：估算出来的总页数可能比真实少，末页连入口都没有；
 * - **跳页 = 一路翻页**：应用页与站点页没有对应关系，点第 34 页只能把 1..33 全拉一遍
 *   （Pornhub 一页 1.2 MB，几十次请求）。
 *
 * 现在**站点的一页就是应用的一页**：
 *
 * | | 以前 | 现在 |
 * |---|---|---|
 * | 一页几条 | 固定 12 | **站点说了算**（hanime 41/59、Pornhub 40–49、nJAV 视站点） |
 * | 跳页 | 从当前页一路拉到目标页 | **直接要那一页**，1 次请求 |
 * | 总页数 | 作品数 ÷ 12 | 站点自己说的（页码条）→ 作品数 ÷ **实测站点一页条数** → 按已翻到的页数长 |
 * | 末页不满 | 被判成 404 | 本来就是站点的一页，照常显示 |
 *
 * 页面缓存按**站点页号**存（[sitePages]），所以来回翻页是零请求；搜索框（[setQuery]）
 * 在**已经拉回来的作品**里筛，不发一次请求。
 *
 * ## ⚠️ 两种「页」在这里已经统一
 *
 * [page] 既是站点页号、也是界面上的页号 —— 不再有 26.9.3 那种「站点一页 59 条、
 * 应用一页 12 条」的换算（那套换算正是上面的三个 bug 的来源）。
 *
 * @param videos **当前这一页**的作品
 * @param page 当前页（1 起，与站点页号一一对应）
 * @param totalPages 总页数：站点口径优先，拿不到就按已翻到的页数长
 * @param numbers 分页条要画的页码（`null` = 省略号）；见 [ArtistPaging.strip]
 * @param canPrev / [canNext] 翻页按钮是否可用（[canNext] **只**看站点那边还有没有下一页）
 * @param isPaging 正在拉这一页（界面显示小转圈，而不是整页 loading）
 * @param query 作品搜索框里的字（只筛本地已加载的作品，nJAV 女优页用得多）
 * @param loadedCount / [matchCount] 本地已加载 / 命中筛选 的作品数
 */
data class ArtistUiState(
    val artist: ArtistRef = ArtistRef(name = ""),
    val profile: ArtistProfile? = null,
    val videos: List<HanimeInfo> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val numbers: List<Int?> = emptyList(),
    val canPrev: Boolean = false,
    val canNext: Boolean = false,
    val state: PageLoadingState<*> = PageLoadingState.Loading,
    val isPaging: Boolean = false,
    /** nJAV 女优页排序（`sort=`），null = 站点默认。其它站点恒为 null。 */
    val sort: String? = null,
    /** nJAV 女优页筛选（`filters=`），null = 全部。其它站点恒为 null。 */
    val filter: String? = null,
    /** 作品搜索框的内容（三个站点都能用；筛选只在本地已加载的作品里做）。 */
    val query: String = "",
    val loadedCount: Int = 0,
    val matchCount: Int = 0,
) {
    /** 搜索框里有没有在筛。 */
    val isSearching: Boolean get() = query.isNotBlank()
}

/**
 * 作者页 ViewModel。
 *
 * 与任何站点账号无关：它读的是站点公开的作者页，不需要登录。
 *
 * ⭐ 26.9.9 起是 [AndroidViewModel]（而不是裸 `ViewModel`）：补头像时要把
 * 「5669 部影片」这句**本地化**文案写进 [io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef.videoCount]，
 * 而 ViewModel 里没有 `stringResource`。用 `application.getString` 取资源，
 * 与 `ActressGridCard` / `ActressGalleryRoute` 共用同一份 `R.string.actress_video_count`。
 * `viewModel()` 默认走 `AndroidViewModelFactory`，构造方式不需要改。
 */
class ArtistViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ArtistUiState())
    val state: StateFlow<ArtistUiState> = _state.asStateFlow()

    /**
     * 已经拉回来的**站点页**：页号 → 那一页的作品。
     *
     * `emptyList()` 也是一个合法值（拉过、站点说这一页没有）—— 有它才不会再拉第二次。
     */
    private val sitePages = HashMap<Int, List<HanimeInfo>>()

    /** 本地已加载的全部作品（按加载顺序去重），搜索框在它上面筛。 */
    private val pool = LinkedHashMap<String, HanimeInfo>()

    /** 已经**拿到过内容**的最大站点页（一页都没拿到就是 0）。 */
    private var maxLoadedPage = 0

    /**
     * 站点自己公布的这份列表**一共几页**（拿不到为 null）。
     *
     * 两条来源：
     * 1. **页码条**：hanime 的搜索页是 Laravel 分页、nJAV 女优页也有自己的页码条
     *    （`NetworkRepo` 解析成 `ArtistVideosPage.siteTotalPages`）；
     * 2. **作品数 ÷ 实测站点一页条数**（[ArtistPaging.pagesFromCountText]）——
     *    Pornhub 只有「87 Videos」这句文案，用它配合实测页大小换算。
     *
     * ⚠️ nJAV **不认作品数文案**（女优页卡片上那个数是站点全站口径），但**认页码条** ——
     * 这正是用户说的「njav 的女优界面存在自己的那一套页数，直接用它那套」。
     */
    private var siteTotalPages: Int? = null

    /**
     * 实测「站点一页几条」（拉回来的站点页里最大的那一页）。
     *
     * 取**最大**而不是第一页：站点末页常常不满，拿末页的条数当页容量会把总页数算多。
     */
    private var sitePageSize = 0

    /**
     * 站点在**已翻到的最远处**之后还可能有下一页。
     *
     * ⚠️ 这是「能不能前进」的唯一闸门（见 [ArtistPaging.resolve] 的 hasNext 说明），
     * **不要**再去和总页数比较 —— 总数一旦是估算值，那样就会在末页锁死自己。
     */
    private var siteHasMore = true

    /** 正在拉的那一页（失败后重试要用同一个目标）。 */
    private var pendingPage: Int? = null

    private var loading = false

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

    /**
     * 作品搜索框（26.9.4）。
     *
     * ⭐ **只在本地已加载的作品里筛，一次请求都不发**：输入过程中每敲一个字就联网，
     * 既慢又容易被站点限流（nJAV 有反爬挑战页）。
     *
     * 对 nJAV 女优页尤其有用 —— 女优页一次就把作品全铺出来（站点自己不分页），
     * 作品多的女优（几百部）只能靠搜。筛的是[pool]（跨已翻过的页），
     * 所以「已加载 N 部 / 命中 M 部」两个数要在界面上如实说出来。
     */
    fun setQuery(value: String) {
        if (_state.value.query == value) return
        _state.value = _state.value.copy(query = value)
        _state.value = pageState(_state.value.page)
    }

    /** 清空已加载内容后重新开始（排序 / 筛选变化，以及首次绑定时用）。 */
    private fun restart(keepSort: Boolean) {
        sitePages.clear()
        pool.clear()
        maxLoadedPage = 0
        siteTotalPages = null
        sitePageSize = 0
        siteHasMore = true
        loading = false
        pendingPage = null
        val current = _state.value
        _state.value = current.copy(
            videos = emptyList(),
            page = 1,
            totalPages = 1,
            numbers = emptyList(),
            canPrev = false,
            canNext = false,
            state = PageLoadingState.Loading,
            isPaging = false,
            sort = if (keepSort) current.sort else null,
            filter = if (keepSort) current.filter else null,
            // 搜索词跟着人/排序走：换一位作者还留着上一位的关键词，只会让人以为没作品。
            query = "",
            loadedCount = 0,
            matchCount = 0,
        )
        fetchPage(1)
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
                // 与 `ActressGridCard` / `ActressGalleryRoute` 同一份资源。
                // 26.9.9 之前这里写死 `"$it 部影片"` ⇒ 英文 / 繁中界面照样印简体中文。
                videoCount?.let { getApplication<Application>().getString(R.string.actress_video_count, it) }
                    .orEmpty()
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

    /** 重试（首屏失败、以及翻页失败都走它）。失败时 [pendingPage] 没被清掉，所以重试的是**同一页**。 */
    fun retry() {
        if (boundKey == null || loading) return
        val target = pendingPage ?: _state.value.page
        fetchPage(target)
    }

    fun nextPage() = goToPage(_state.value.page + 1)

    fun prevPage() = goToPage(_state.value.page - 1)

    /**
     * 跳到第 [target] 页。
     *
     * ⭐ 26.9.4 起这里是**真跳页**：应用页号 = 站点页号，所以「去第 34 页」= 要站点第 34 页，
     * **一次请求**。26.9.3 及以前是「从当前页一路拉到目标页」（Pornhub 一页 1.2 MB，
     * 跳一次几十次请求）—— 用户原话「现在的跳转其实还是翻页……这样不是请求变多了吗」。
     *
     * 三种情形：
     * - **这一页已经拉回来过**：直接换页，**零请求**（来回翻页不该联网）；
     * - **还没拉过**：记下目标，交给 [fetchPage]（1 次请求，成不成都只试这一次）；
     * - **站点那边确认没有了**：不再往后放人（往前翻仍然允许，那些页在缓存里）。
     */
    fun goToPage(target: Int) {
        val current = _state.value
        if (loading) return
        val safe = target.coerceAtLeast(1)
        if (safe == current.page) return
        if (sitePages.containsKey(safe)) {
            pendingPage = null
            _state.value = pageState(safe)
            return
        }
        if (safe > current.page && !current.canNext) return
        pendingPage = safe
        fetchPage(safe)
    }

    /**
     * 拉取站点第 [target] 页。
     *
     * 只拉这一页 —— 不预取、不连拉（跳页次数由用户决定，每次 1 个请求）。
     */
    private fun fetchPage(target: Int) {
        if (loading) return
        val artist = _state.value.artist
        val sort = _state.value.sort
        val filter = _state.value.filter
        loading = true
        pendingPage = target
        _state.value = _state.value.copy(
            // 手里已经有内容时不清空画面（翻页只是补一页，不该整页转圈）。
            state = if (sitePages.isEmpty()) PageLoadingState.Loading else _state.value.state,
            isPaging = sitePages.isNotEmpty(),
        )
        viewModelScope.launch {
            var failure: Throwable? = null
            NetworkRepo.getArtistVideos(artist, target, sort, filter).collect { result ->
                when (result) {
                    is PageLoadingState.Success -> {
                        val info = result.info
                        val items = info.videos.distinctBy { it.videoCode }
                        if (items.isNotEmpty()) {
                            // 只有**满页**才代表站点页容量；末页不满很正常，别把容量记小。
                            sitePageSize = maxOf(sitePageSize, items.size)
                            maxLoadedPage = maxOf(maxLoadedPage, target)
                            pool.putAll(items.associateBy { it.videoCode })
                        }
                        sitePages[target] = items
                        info.siteTotalPages?.takeIf { it > 0 }?.let { siteTotalPages = it }
                        siteHasMore = hasNextAfter(target, items, info.hasNext)
                        pendingPage = null
                        if (items.isEmpty() && target > 1) {
                            // 站点这一页是空的（页号越界 / 反爬空页）⇒ 列表到头了，
                            // 把人放回**真正有内容的最后一页**，而不是让他看一张空白页。
                            clampToLastPage()
                        } else {
                            val next = pageState(target)
                            _state.value = info.profile?.let { next.copy(profile = it) } ?: next
                            if (target == 1) markSeen(items)
                        }
                    }

                    is PageLoadingState.NoMoreData -> {
                        sitePages[target] = emptyList()
                        siteHasMore = false
                        pendingPage = null
                        // 站点说这一页没有 ⇒ 列表就到这里，估计出来的总页数要收回来。
                        siteTotalPages = maxOf(maxLoadedPage, 1).takeIf { maxLoadedPage > 0 }
                        clampToLastPage()
                    }

                    is PageLoadingState.Error -> {
                        // ⚠️ 不清 pendingPage：重试要重试**同一页**。
                        failure = result.throwable
                    }

                    is PageLoadingState.Loading -> Unit
                }
            }
            loading = false
            val error = failure
            if (error != null) {
                // 保留画面上的那一页（videos 不动），错误只挂在列表末尾 ——
                // 「翻不动」和「这一页没了」在界面上必须长得不一样。
                _state.value = _state.value.copy(
                    state = PageLoadingState.Error(error),
                    isPaging = false,
                )
            } else {
                _state.value = _state.value.copy(isPaging = false)
            }
        }
    }

    /** 站点这一页之后还有没有下一页（[siteHasMore] 的取值）。 */
    private fun hasNextAfter(target: Int, items: List<HanimeInfo>, siteSays: Boolean?): Boolean {
        // 站点自己说得很清楚（`rel=next` 有没有）就听它的。
        if (siteSays != null) return siteSays
        if (items.isEmpty()) return false
        val known = knownTotalPages()
        return known == null || target < known
    }

    /** 夹回「真正有内容的最后一页」（越界页 / 空页的兜底）。 */
    private fun clampToLastPage() {
        val back = lastPageWithItems().coerceAtLeast(1)
        _state.value = pageState(back)
    }

    /** 本地缓存里最后一个**有作品**的页号（一页都没有就是 1）。 */
    private fun lastPageWithItems(): Int =
        sitePages.entries.filter { it.value.isNotEmpty() }.maxOfOrNull { it.key } ?: 1

    /**
     * 站点公布的「一共几页」，两条来源取大的那个（宁可估大也不估小 —— 估小会让用户
     * 以为已经到最后一页了）。
     *
     * ⚠️ nJAV **不认作品数文案**（女优页卡片上那个数是站点全站口径），但**认页码条**
     * （[siteTotalPages]）—— 用户要的正是「用它自己那一套页数」。
     */
    private fun knownTotalPages(): Int? {
        val byCountText = if (_state.value.artist.siteSource == SiteSource.Njav) {
            null
        } else {
            ArtistPaging.pagesFromCountText(
                _state.value.profile?.videoCount?.takeIf { it.isNotBlank() }
                    ?: _state.value.artist.videoCount.takeIf { it.isNotBlank() },
                // 除数必须是**实测的站点一页条数**（第一页回来之后就有了）。
                sitePageSize,
            )
        }
        return listOfNotNull(byCountText, siteTotalPages).maxOrNull()
    }

    /** 搜索框当前命中哪些作品（在[pool]上做，**不联网**）。 */
    private fun searchResults(query: String): List<HanimeInfo> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return pool.values.filter { info ->
            info.videoCode.contains(needle, ignoreCase = true) ||
                    info.title.contains(needle, ignoreCase = true)
        }
    }

    /**
     * 由缓存算出「第 [page] 页」的状态。
     *
     * 口径全部在 [ArtistPaging.resolve] 里（纯数学、有单测）：
     * 总页数用站点口径（页码条 / 作品数 ÷ 实测站点页条数），拿不到就按已翻到的页数长；
     * 「下一页」只看站点那边还有没有，**不**看「到没到总页数」——
     * 那正是 26.9.0「最多十页」的死锁来源。
     */
    private fun pageState(page: Int): ArtistUiState {
        val query = _state.value.query.trim()
        val searching = query.isNotEmpty()
        val items = if (searching) searchResults(query) else sitePages[page].orEmpty()
        // 搜索是在本地池子上筛的，和「第几页」没有关系 ⇒ 搜索时把翻页闸门全关掉
        // （界面会整个收起分页条），但 **page 保持不动** —— 清空搜索词就回到原来那一页。
        val canNext = !searching && (page < maxLoadedPage || siteHasMore)
        val verdict = ArtistPaging.resolve(
            page = page,
            maxLoadedPage = maxLoadedPage,
            knownTotalPages = knownTotalPages(),
            hasNext = canNext,
        )
        return _state.value.copy(
            videos = items,
            page = verdict.page,
            totalPages = verdict.totalPages,
            numbers = verdict.numbers,
            canPrev = !searching && verdict.canPrev,
            canNext = canNext,
            query = _state.value.query,
            loadedCount = pool.size,
            matchCount = if (searching) items.size else pool.size,
            state = when {
                items.isNotEmpty() -> PageLoadingState.Success(items)
                searching -> PageLoadingState.Success(emptyList<HanimeInfo>())
                sitePages.isEmpty() -> PageLoadingState.Loading
                else -> PageLoadingState.NoMoreData
            },
        )
    }

    /** 打开作者页第一页 = 这位作者的新作都知道了 ⇒ 角标清零。只在站点第 1 页做。 */
    private fun markSeen(items: List<HanimeInfo>) {
        if (items.isEmpty()) return
        val artist = _state.value.artist
        val codes = items.take(FIRST_PAGE_SEEN_LIMIT).map { it.videoCode }
        viewModelScope.launch {
            runCatching { FollowedArtistStore.markSeen(artist, codes) }
        }
    }

    companion object {
        /** ⭐ 2 列 —— 封面上的编号/标题看得清（9.0 从 3 列改过来）。 */
        const val COLUMNS = 2

        /**
         * 清零新作角标时最多记多少条。
         *
         * 站点一页 40–59 条，全记下来会把关注表写得很肥；用户打开作者页看到的也就是第一页。
         */
        private const val FIRST_PAGE_SEEN_LIMIT = 20
    }
}
