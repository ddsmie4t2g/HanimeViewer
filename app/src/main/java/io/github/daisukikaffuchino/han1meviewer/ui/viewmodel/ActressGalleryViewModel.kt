package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo
import io.github.daisukikaffuchino.han1meviewer.logic.model.NjavActress
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavNetwork
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 女优页的两个标签。
 *
 * 站点上这两个是**并列**的两个页面（首页导航里同在「女优」这一组下）：
 * - [Index]：`/cn/actresses` —— 「女优一览」，按作品数或出道年排，**1400+ 页**，能一直往下翻；
 * - [Ranking]：`/cn/actresses/ranking` —— 「女优排行」，站点给的是**当月**榜，固定 100 条、没有翻页。
 */
enum class ActressGalleryTab { Index, Ranking }

/**
 * 女优一览 / 排行页的状态。
 *
 * @param tab 当前标签
 * @param sort 一览页的排序（`videos` / `debut`）；排行页无意义
 * @param period 排行页的周期文案（`SEP 2026`），一览页恒为空
 * @param actresses 当前标签已加载的条目（**未过滤**）
 * @param keyword 本地名字过滤词
 * @param state 最近一次加载的结果（只有「列表为空」时才影响整页显示）
 * @param isLoadingMore 正在为「加载更多」补拉（界面显示小转圈而不是整页 loading）
 * @param hasMore 一览页还可能有没有加载的页
 * @param loadFailed ⭐ **翻页失败**。界面靠它**停掉自动续页** —— 见下面的注释，
 *   不记这一位的话「失败 → isPaging 变 false → 滚到底又触发 → 又失败」会变成
 *   一个每秒发一次请求的死循环。
 */
data class ActressGalleryUiState(
    val tab: ActressGalleryTab = ActressGalleryTab.Index,
    val sort: String = NjavNetwork.ACTRESS_SORT_VIDEOS,
    val period: String = "",
    val actresses: List<NjavActress> = emptyList(),
    val keyword: String = "",
    val state: PageLoadingState<*> = PageLoadingState.Loading,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val loadFailed: Boolean = false,
) {
    /** 过滤后的列表 —— 站点没有名字检索，所以名字过滤只能在**已加载**的条目上做。 */
    val filtered: List<NjavActress>
        get() {
            val key = keyword.trim()
            if (key.isEmpty()) return actresses
            return actresses.filter { it.name.contains(key, ignoreCase = true) }
        }
}

/**
 * 女优一览 / 排行页的 ViewModel（26.8.2 新增）。
 *
 * ## 数据来源与「只增不删」的翻页
 *
 * 一览页每页 24 人、站点那边有 1400+ 页，所以这里是**滚动续页**：往 [index] 里累积，
 * `hasMore` 的判据是「这一页有没有解析出人」（比「站点说没说还有下一页」可靠 ——
 * 空页 / 重复页在两个站点的形态都不一样，与 `ArtistViewModel` 同一套做法）。
 *
 * 排行页只有一页，拉一次就够，之后切标签不会重复请求。
 */
class ActressGalleryViewModel : ViewModel() {

    private val _state = MutableStateFlow(ActressGalleryUiState())
    val state: StateFlow<ActressGalleryUiState> = _state.asStateFlow()

    /** 一览页累积下来的条目（与用户正在看的那一页无关，就是「已加载的全部」）。 */
    private val index = mutableListOf<NjavActress>()

    /** 排行页的条目（一次性）。 */
    private val ranking = mutableListOf<NjavActress>()

    private var indexPage = 1
    private var indexSort: String? = null
    private var indexHasMore = true
    private var rankingPeriod = ""
    private var rankingLoaded = false
    private var loading = false

    init {
        loadIndex(reset = true)
    }

    fun selectTab(tab: ActressGalleryTab) {
        if (_state.value.tab == tab) return
        _state.value = _state.value.copy(tab = tab, keyword = "")
        publish(tab)
        when (tab) {
            ActressGalleryTab.Index -> if (index.isEmpty()) loadIndex(reset = true)
            ActressGalleryTab.Ranking -> if (!rankingLoaded) loadRanking()
        }
    }

    /** 一览页排序（`?sort=`）。换排序 = 之前翻的页全作废，必须重头来。 */
    fun setSort(value: String) {
        if (indexSort == value) return
        loadIndex(reset = true, sort = value)
    }

    fun setKeyword(value: String) {
        _state.value = _state.value.copy(keyword = value)
    }

    /** 加载更多（一览页专用；排行页只有一页，什么也不做）。 */
    fun loadMore() {
        if (_state.value.tab != ActressGalleryTab.Index) return
        if (!indexHasMore || loading) return
        loadIndex(reset = false)
    }

    /** 整页重试：列表空时重新拉第一页，非空时补拉下一页。 */
    fun retry() {
        when (_state.value.tab) {
            ActressGalleryTab.Index -> loadIndex(reset = index.isEmpty())
            ActressGalleryTab.Ranking -> loadRanking()
        }
    }

    /** 翻页失败后手动重试（底部按钮）—— 与 [retry] 等价，但语义是「接着往下翻」。 */
    fun retryLoadMore() {
        if (_state.value.tab != ActressGalleryTab.Index) return
        if (loading) return
        loadIndex(reset = false)
    }

    private fun loadIndex(reset: Boolean, sort: String? = null) {
        if (loading) return
        val targetSort = sort ?: indexSort
        if (reset) {
            index.clear()
            indexPage = 1
            indexHasMore = true
            indexSort = targetSort
        }
        val requestedPage = indexPage
        loading = true
        _state.value = _state.value.copy(
            sort = targetSort ?: NjavNetwork.ACTRESS_SORT_VIDEOS,
            state = if (reset) PageLoadingState.Loading else _state.value.state,
            isLoadingMore = !reset,
            hasMore = indexHasMore,
            loadFailed = false,
        )
        viewModelScope.launch {
            NetworkRepo.getNjavActressIndex(requestedPage, targetSort).collect { result ->
                when (result) {
                    is PageLoadingState.Success -> {
                        val fresh = result.info.filterNot { a -> index.any { it.path == a.path } }
                        index += fresh
                        indexPage = requestedPage + 1
                        // 「这一页有没有人」比「站点说没说还有下一页」可靠。
                        indexHasMore = result.info.isNotEmpty()
                        loading = false
                        publish(ActressGalleryTab.Index)
                    }

                    is PageLoadingState.NoMoreData -> {
                        indexHasMore = false
                        loading = false
                        if (index.isEmpty()) {
                            _state.value = _state.value.copy(
                                state = PageLoadingState.NoMoreData,
                                isLoadingMore = false,
                                hasMore = false,
                            )
                        } else {
                            publish(ActressGalleryTab.Index)
                        }
                    }

                    is PageLoadingState.Error -> {
                        loading = false
                        _state.value = _state.value.copy(
                            state = PageLoadingState.Error(result.throwable),
                            isLoadingMore = false,
                            // ⭐ 必须记下来：界面据此停掉自动续页。
                            // 不记的话 —— 失败 → isPaging 变 false → 滚到底的监听重新触发 →
                            // 又去请求 → 又失败，就是一个不看内容的死循环。
                            loadFailed = true,
                        )
                    }

                    PageLoadingState.Loading -> Unit
                }
            }
        }
    }

    private fun loadRanking() {
        if (loading) return
        loading = true
        _state.value = _state.value.copy(
            state = PageLoadingState.Loading,
            isLoadingMore = false,
            hasMore = false,
            loadFailed = false,
        )
        viewModelScope.launch {
            NetworkRepo.getNjavActressRanking().collect { result ->
                when (result) {
                    is PageLoadingState.Success -> {
                        ranking.clear()
                        ranking += result.info.actresses
                        rankingPeriod = result.info.period
                        rankingLoaded = true
                        loading = false
                        publish(ActressGalleryTab.Ranking)
                    }

                    is PageLoadingState.NoMoreData -> {
                        rankingLoaded = true
                        loading = false
                        _state.value = _state.value.copy(state = PageLoadingState.NoMoreData)
                    }

                    is PageLoadingState.Error -> {
                        loading = false
                        _state.value = _state.value.copy(state = PageLoadingState.Error(result.throwable))
                    }

                    PageLoadingState.Loading -> Unit
                }
            }
        }
    }

    /**
     * 把「当前标签的那份列表」推到界面上。
     *
     * ⚠️ 只推**当前标签**：翻页是异步的，用户完全可能在翻页还没回来时切了标签 ——
     * 那时把一览的数据塞进排行标签，界面就会闪出另一份列表。
     */
    private fun publish(tab: ActressGalleryTab) {
        if (_state.value.tab != tab) return
        val list = when (tab) {
            ActressGalleryTab.Index -> index.toList()
            ActressGalleryTab.Ranking -> ranking.toList()
        }
        _state.value = _state.value.copy(
            actresses = list,
            period = if (tab == ActressGalleryTab.Ranking) rankingPeriod else "",
            hasMore = tab == ActressGalleryTab.Index && indexHasMore,
            isLoadingMore = false,
            loadFailed = false,
            state = if (list.isEmpty()) PageLoadingState.NoMoreData else PageLoadingState.Success(list),
        )
    }
}
