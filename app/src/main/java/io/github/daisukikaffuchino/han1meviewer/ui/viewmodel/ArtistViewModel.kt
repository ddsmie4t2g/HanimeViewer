package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistProfile
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 作者页的状态。
 *
 * @param artist 目标作者（进入页面时由路由参数决定，之后不变）
 * @param profile 站点给的资料头（Pornhub 有，nJAV 没有）；null 时界面只用 [artist] 里的字段
 * @param videos 已累积的作品（第一页 + 后续「加载更多」）
 * @param state 当前这一页的加载态
 */
data class ArtistUiState(
    val artist: ArtistRef = ArtistRef(name = ""),
    val profile: ArtistProfile? = null,
    val videos: List<HanimeInfo> = emptyList(),
    val state: PageLoadingState<*> = PageLoadingState.Loading,
    val canLoadMore: Boolean = false,
)

/**
 * 作者页 ViewModel。
 *
 * 与 [MySubscriptionsViewModel] 同一套「累积 + 翻页」写法，但**不需要登录** ——
 * 它读的是站点公开的作者页，与账号体系无关（这正是用户要的「登录不跟网站挂钩」：
 * 关注与看作者不需要先登录任何一个站）。
 */
class ArtistViewModel : ViewModel() {

    private val _state = MutableStateFlow(ArtistUiState())
    val state: StateFlow<ArtistUiState> = _state.asStateFlow()

    private var currentPage = 1
    private var isLoading = false
    private var hasMore = true

    /** 已绑定过哪位作者：同一个作者重复 `bind` 不重新拉取（重组时会被调到很多次）。 */
    private var boundKey: String? = null

    /**
     * 绑定作者并拉第一页。
     *
     * 幂等：同一个 [ArtistRef.followKey] 再来一次直接返回 —— 界面在每次重组时都会调用它，
     * 不做这层短路就会「每帧发一次网络请求」。
     */
    fun bind(artist: ArtistRef) {
        val key = artist.followKey
        if (key.isEmpty() || key == boundKey) return
        boundKey = key
        currentPage = 1
        hasMore = true
        isLoading = false
        _state.value = ArtistUiState(artist = artist, state = PageLoadingState.Loading)
        loadPage()
    }

    fun retry() {
        if (boundKey == null) return
        if (isLoading) return
        _state.value = _state.value.copy(state = PageLoadingState.Loading)
        loadPage()
    }

    fun loadMore() {
        if (isLoading || !hasMore) return
        isLoading = true
        currentPage += 1
        loadPage()
    }

    private fun loadPage() {
        isLoading = true
        val artist = _state.value.artist
        val page = currentPage
        viewModelScope.launch {
            NetworkRepo.getArtistVideos(artist, page).collect { result ->
                when (result) {
                    is PageLoadingState.Success -> {
                        val info = result.info
                        val merged = if (page == 1) {
                            info.videos
                        } else {
                            val existing = _state.value.videos.map { it.videoCode }.toSet()
                            _state.value.videos + info.videos.filterNot { it.videoCode in existing }
                        }
                        // 这一页一条新数据都没有 → 到底了。比「看页数」可靠：
                        // 两个站点的空页/重复页形态都不一样。
                        val added = merged.size > _state.value.videos.size || page == 1
                        hasMore = added && info.videos.isNotEmpty()
                        _state.value = _state.value.copy(
                            profile = info.profile ?: _state.value.profile,
                            videos = merged,
                            state = PageLoadingState.Success(merged),
                            canLoadMore = hasMore,
                        )
                    }

                    is PageLoadingState.NoMoreData -> {
                        hasMore = false
                        _state.value = _state.value.copy(
                            state = if (_state.value.videos.isEmpty()) {
                                PageLoadingState.NoMoreData
                            } else {
                                PageLoadingState.Success(_state.value.videos)
                            },
                            canLoadMore = false,
                        )
                    }

                    is PageLoadingState.Loading -> {
                        _state.value = _state.value.copy(state = PageLoadingState.Loading)
                    }

                    is PageLoadingState.Error -> {
                        hasMore = false
                        _state.value = _state.value.copy(
                            state = PageLoadingState.Error(result.throwable),
                            canLoadMore = false,
                        )
                    }
                }
            }
            isLoading = false
        }
    }
}
