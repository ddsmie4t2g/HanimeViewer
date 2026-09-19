package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel

import io.github.daisukikaffuchino.utils.LogUtil
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.github.daisukikaffuchino.han1meviewer.EMPTY_STRING
import io.github.daisukikaffuchino.han1meviewer.HanimeResolution
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.DatabaseRepo
import io.github.daisukikaffuchino.han1meviewer.logic.FollowedArtistStore
import io.github.daisukikaffuchino.han1meviewer.logic.LocalListRepository
import io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.account.AccountRepository
import io.github.daisukikaffuchino.han1meviewer.logic.entity.HKeyframeEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.WatchHistoryEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.download.HanimeDownloadEntity
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeVideo
import io.github.daisukikaffuchino.han1meviewer.logic.model.SubscriptionItem
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavActressCache
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavNetwork
import io.github.daisukikaffuchino.han1meviewer.logic.state.VideoLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.AppViewModel.csrfToken
import io.github.daisukikaffuchino.han1meviewer.util.TagLocalizer
import androidx.lifecycle.ViewModel
import io.github.daisukikaffuchino.han1meviewer.logic.platform.AndroidVideoCacheStore
import io.github.daisukikaffuchino.han1meviewer.logic.platform.VideoCacheStore
import io.github.daisukikaffuchino.utils.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/17 017 19:01
 */
class VideoViewModel(
    private val videoCacheStore: VideoCacheStore = AndroidVideoCacheStore,
) : ViewModel() {

    data class IntroScrollState(
        val firstVisibleItemIndex: Int = 0,
        val firstVisibleItemScrollOffset: Int = 0,
    )

    data class VideoHostUiState(
        val selectedTabIndex: Int = 0,
        val commentBadgeCount: Int = 0,
        val isScrollDisabled: Boolean = false,
        val isInPipMode: Boolean = false,
        val playerHeightDp: Dp? = 250.dp,
    )

    private data class VideoIntroUiState(
        val playlistFirstVisibleIndex: Int? = null,
        val cachedVideo: HanimeVideo? = null,
        val introRestored: Boolean = false,
        val scrollState: IntroScrollState = IntroScrollState(),
        val selectedTabIndex: Int = 0,
    )

    companion object {
        /**
         * 最小的 HKeyframe 保存間隔，暫定 5s
         */
        const val MIN_H_KEYFRAME_SAVE_INTERVAL = 5_000 // ms

        /**
         * 详情页补 nJAV 女优头像的并发度。
         *
         * 每个未命中的人最多要翻 3 页索引（`NetworkRepo.findNjavActress`），
         * 女优多的片子（合作片 5–10 位）串行会拖成十几秒；4 是「快」与「别把站点打烦」的折中
         * （与关注新作红点那条用的是同一个数）。
         */
        const val NJAV_AVATAR_CONCURRENCY = 4
    }
    private val videoIntroUiStateMap = mutableMapOf<String, VideoIntroUiState>()
    private val _videoCodeFlow = MutableStateFlow(EMPTY_STRING)
    var videoCode: String = EMPTY_STRING
        set(value) {
            field = value
            _videoCodeFlow.value = value
        }

    var fromDownload = false

    // 平板横屏模式下，左栏不显示相关视频（右栏已显示）
    var hideRelatedInIntro by mutableStateOf(false)
    var hKeyframes: HKeyframeEntity? = null
    private val _videoList = MutableLiveData<List<HanimeInfo>>()
    val videoList: LiveData<List<HanimeInfo>> = _videoList
    private val _hanimeVideoStateFlow =
        MutableStateFlow<VideoLoadingState<HanimeVideo>>(VideoLoadingState.Loading)
    val hanimeVideoStateFlow = _hanimeVideoStateFlow.asStateFlow()

    private val _hanimeVideoFlow = MutableStateFlow<HanimeVideo?>(null)
    val hanimeVideoFlow = _hanimeVideoFlow.asStateFlow()

    /**
     * 详情页展示用视频流：**该用本机状态时**把本地喜欢/清单状态合成到视频上，
     * 否则与 [hanimeVideoFlow] 保持一致。
     *
     * ⚠️ 判据是 [SettingsRepository.useLocalVideoStateFlow] 而不是「有没有登录 hanime」：
     * 在 Pornhub / nJAV 下，就算登录了 hanime，这部片子也不存在于 hanime，
     * 收藏落在本机库 —— 状态自然也要从本机库读，否则点了喜欢按钮上的心不会亮。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val displayVideoFlow: StateFlow<HanimeVideo?> = combine(
        _hanimeVideoFlow,
        _videoCodeFlow,
        SettingsRepository.useLocalVideoStateFlow,
    ) { video, code, useLocalState -> Triple(video, code, useLocalState) }
        .flatMapLatest { (video, code, useLocalState) ->
            if (video == null || !useLocalState || code.isBlank()) {
                flowOf(video)
            } else {
                combine(
                    LocalListRepository.observeIsFavorite(code),
                    LocalListRepository.observeIsWatchLater(code),
                    LocalListRepository.observeListCodes(code),
                    LocalListRepository.observePlaylists(),
                    LocalListRepository.observeFavoriteCollections(),
                ) { isFavorite, isWatchLater, listCodes, playlists, collections ->
                    video.copy(
                        isFav = isFavorite,
                        myList = HanimeVideo.MyList(
                            isWatchLater = isWatchLater,
                            myListInfo = buildList {
                                add(
                                    HanimeVideo.MyList.MyListInfo(
                                        code = LocalListRepository.WATCH_LATER_CODE,
                                        title = application.getString(R.string.watch_later),
                                        isSelected = isWatchLater,
                                    )
                                )
                                playlists.forEach { playlist ->
                                    add(
                                        HanimeVideo.MyList.MyListInfo(
                                            code = playlist.listCode,
                                            title = playlist.title,
                                            isSelected = playlist.listCode in listCodes,
                                        )
                                    )
                                }
                                // ⭐ 9.0：收藏夹排在播放清单之后，用 isCollection 标记，
                                // 让弹窗能给它们单独一个分组标题。
                                collections.forEach { collection ->
                                    add(
                                        HanimeVideo.MyList.MyListInfo(
                                            code = collection.listCode,
                                            title = collection.title,
                                            isSelected = collection.listCode in listCodes,
                                            isCollection = true,
                                        )
                                    )
                                }
                            },
                        ),
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _videoHostUiStateFlow = MutableStateFlow(VideoHostUiState())
    val videoHostUiStateFlow = _videoHostUiStateFlow.asStateFlow()

    fun setVideoList(list: List<HanimeInfo>) {
        _videoList.value = list
    }

    fun getPlaylistFirstVisibleIndex(videoCode: String): Int? {
        return videoIntroUiStateMap[videoCode]?.playlistFirstVisibleIndex
    }

    fun setPlaylistFirstVisibleIndex(videoCode: String, index: Int) {
        updateVideoIntroUiState(videoCode) { copy(playlistFirstVisibleIndex = index) }
    }

    fun setVideoIntroCachedData(videoCode: String, video: HanimeVideo?) {
        updateVideoIntroUiState(videoCode) {
            copy(
                cachedVideo = video,
                introRestored = video != null,
            )
        }
    }

    fun clearVideoIntroRestoredFlag(videoCode: String) {
        updateVideoIntroUiState(videoCode) { copy(introRestored = false) }
    }

    fun getIntroScrollState(videoCode: String): IntroScrollState {
        return videoIntroUiStateMap[videoCode]?.scrollState ?: IntroScrollState()
    }

    fun getSelectedTabIndex(videoCode: String): Int {
        return _videoHostUiStateFlow.value.selectedTabIndex
    }

    fun setSelectedTabIndex(videoCode: String, selectedTabIndex: Int) {
        _videoHostUiStateFlow.update { it.copy(selectedTabIndex = selectedTabIndex) }
        updateVideoIntroUiState(videoCode) { copy(selectedTabIndex = selectedTabIndex) }
    }

    fun setCommentBadgeCount(commentBadgeCount: Int) {
        _videoHostUiStateFlow.update { it.copy(commentBadgeCount = commentBadgeCount) }
    }

    fun setScrollDisabled(isScrollDisabled: Boolean) {
        _videoHostUiStateFlow.update { it.copy(isScrollDisabled = isScrollDisabled) }
    }

    fun setPipMode(isInPipMode: Boolean) {
        _videoHostUiStateFlow.update { it.copy(isInPipMode = isInPipMode) }
    }

    fun setPlayerHeightDp(playerHeightDp: Dp?) {
        _videoHostUiStateFlow.update { it.copy(playerHeightDp = playerHeightDp) }
    }

    fun setIntroScrollState(
        videoCode: String,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int,
    ) {
        updateVideoIntroUiState(videoCode) {
            copy(
                scrollState = IntroScrollState(
                    firstVisibleItemIndex = firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
                )
            )
        }
    }

    private inline fun updateVideoIntroUiState(
        videoCode: String,
        transform: VideoIntroUiState.() -> VideoIntroUiState,
    ) {
        val current = videoIntroUiStateMap[videoCode] ?: VideoIntroUiState()
        videoIntroUiStateMap[videoCode] = current.transform()
    }

    fun resolveTagSearchKey(tag: String): String = TagLocalizer.resolveSearchKey(tag)

    private fun HanimeVideo.withLocalizedLabels(): HanimeVideo {
        return copy(
            tags = TagLocalizer.localizeTags(tags),
            artist = artist?.copy(genre = TagLocalizer.localizeTag(artist.genre)),
        )
    }

    fun buildLocalPlayInfo(localPath: String? = null): HanimeVideo {
        val resolution = HanimeResolution()
        resolution.parseResolution(
            HanimeResolution.RES_1080P,
            resLink = localPath?:"",
            type = "video/mp4"
        )
        return HanimeVideo(
            title = "",
            coverUrl = "",
            chineseTitle = localPath?.toUri()?.lastPathSegment,
            introduction = "",
            uploadTime = null,
            views = "0",
            videoUrls = resolution.toResolutionLinkMap(),
            tags = emptyList(),
        )
    }
    fun getHanimeVideo(videoCode: String,localUri: String? = null) {
        if (videoCode == "-1"){
            val localPlayInfo = buildLocalPlayInfo(localUri)
            _hanimeVideoStateFlow.value = VideoLoadingState.Success(localPlayInfo)
            _hanimeVideoFlow.value = localPlayInfo
            return
        }
        if (videoIntroUiStateMap[videoCode]?.introRestored == true) return
        viewModelScope.launch {
            val flow = if (fromDownload) {
                videoCacheStore.load(videoCode).map { hv ->
                    if (hv == null) {
                        VideoLoadingState.NoContent
                    } else {
                        VideoLoadingState.Success(hv)
                    }
                }
            } else {
                // ⭐ 9.0 跨站点：关注 / 订阅 / 观看历史 / 收藏夹里的片子可能属于**别的站**，
                //    一律按当前数据源去问会拿到 403/500，界面显示成「可能该影片不存在」。
                //    这里改用「先用当前数据源、失败再试另外两个站」的入口。
                NetworkRepo.getHanimeVideoAnySite(videoCode)
            }
            flow.collect { state ->
                val emitState = when {
                    localUri != null && state is VideoLoadingState.Success -> {
                        val resolution = HanimeResolution()
                        resolution.parseResolution(
                            HanimeResolution.RES_1080P,
                            resLink = localUri,
                            type = "video/mp4"
                        )
                        VideoLoadingState.Success(
                            state.info.copy(videoUrls = resolution.toResolutionLinkMap())
                                .withLocalizedLabels()
                        )
                    }

                    state is VideoLoadingState.Success -> {
                        VideoLoadingState.Success(state.info.withLocalizedLabels())
                    }

                    else -> state
                }
                _hanimeVideoStateFlow.value = emitState
                if (emitState is VideoLoadingState.Success) {
                    _hanimeVideoFlow.update { emitState.info }
                    csrfToken = emitState.info.csrfToken
                    fillNjavArtistAvatars(emitState.info)
                }
            }
        }
    }

    /**
     * nJAV 详情页**给不出作者头像**，这里按名字补。
     *
     * `NjavParser.artistsOf` 只能把 `avatarUrl` 填成空串（站点在女优页给的是「首字占位符」，
     * 不是图片），真头像在女优一览的 `fourhoi.com/actress/<id>-t.jpg`。所以详情页这条链路
     * 必须自己去索引页按名字找 —— 这件事以前只做在**女优页**和**关注表**上
     * （`ArtistViewModel` / `FollowedArtistStore.fillMissingAvatars`），
     * 详情页从来没做过，表现就是头像位置一片空白。
     *
     * 两步走，尽量少打网络：
     * 1. 先查本地索引缓存（[NjavActressCache]）—— 0 网络，浏览过女优一览就命中；
     * 2. 没命中的再联网翻索引页（`NetworkRepo.findNjavActress`，内部并行 + 回填缓存）。
     *
     * ⚠️ 只在 nJAV 源上做：hanime / Pornhub 的解析器**直接给得出**头像，多打这次请求是纯浪费。
     * ⚠️ 逐个回写、且回写前核对片名 —— 用户可能已经切到别的片子，别把头像写串到新页面上。
     * ⚠️ 整个方法**不阻塞**首屏：它在 `viewModelScope` 里异步跑，拿到一个显示一个。
     * ⚠️⚠️ 回写**只碰 [_hanimeVideoFlow]，绝不碰 [_hanimeVideoStateFlow]** —— 见 [applyNjavAvatar]。
     */
    private fun fillNjavArtistAvatars(video: HanimeVideo) {
        if (!SettingsRepository.isNjavSite) return
        val pending = video.artists.filter { it.avatarUrl.isBlank() }
        if (pending.isEmpty()) return

        viewModelScope.launch {
            // 并发 [NJAV_AVATAR_CONCURRENCY]：女优多的时候（合作片常见 5–10 位）串行查会把
            // 「补头像」拖成十几秒 —— 而每个未命中的人最多要翻 3 页索引（见 findNjavActress）。
            // 每个子协程查完**自己**回主线程回写，所以还是「拿到一个显示一个」。
            val gate = Semaphore(NJAV_AVATAR_CONCURRENCY)
            for (artist in pending) {
                launch {
                    val avatar = withContext(Dispatchers.IO) {
                        gate.withPermit { resolveNjavAvatar(artist) }
                    } ?: return@launch
                    applyNjavAvatar(video, artist.name, avatar)
                }
            }
        }
    }

    /**
     * 查一位女优的头像直链。**先本地（路径 → 名字），最后才联网** —— 顺序不能反。
     */
    private suspend fun resolveNjavAvatar(artist: HanimeVideo.Artist): String? {
        // 1) 本地缓存 · 按**女优路径** —— 最稳的一条。
        //    详情页给的名字与索引页的写法**可能繁简不同**（索引页 `href` 用繁体、`h4` 用简体，
        //    见 `NjavActress.path` 的注释），按名字查会 miss，按路径不会。
        //    ⚠️ 27.0.6 的详情页补头像**漏了这一步**（`ArtistViewModel.resolveMissingAvatarIfNeeded`
        //    一直这么做），所以「有些女优头像还是显示不出来」。
        runCatching { NjavNetwork.actressPathFrom(artist.url) }.getOrNull()
            ?.let { path -> NjavActressCache.findByPath(path)?.avatarUrl }
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // 2) 本地缓存 · 按名字（浏览过女优一览/排行，或者此前补过任何一个人，这里就命中）
        NjavActressCache.avatarOf(artist.name).takeIf { it.isNotBlank() }?.let { return it }

        // 3) 联网：先抓当月排行（一页 100 位）再并行翻索引页，见 [NetworkRepo.findNjavActress]。
        val found = runCatching { NetworkRepo.findNjavActress(artist.name) }.getOrNull()
            ?: return null
        // 把这次的结果也记到「详情页这个写法」下（繁简/别名）——下次就是命中缓存、同帧出图。
        runCatching { NjavActressCache.rememberAlias(artist.name, found) }
        return found.avatarUrl.takeIf { it.isNotBlank() }
    }

    /**
     * 把查到的头像贴进详情页。
     *
     * ⚠️⚠️ **只更新展示流 [_hanimeVideoFlow]，绝不写 [_hanimeVideoStateFlow]。**
     *
     * 详情页把「收到一次 `VideoLoadingState.Success`」当作**可以起播**的信号
     * （`VideoRouteHostScreen` 里那段 collect ⇒ `playbackController.load(...)`），
     * 而 27.0.6/27.0.7 这里原来**每拿到一个头像就写一次 state** ⇒ 女优越多写得越多，
     * 播放器就被反复重建：表现为**底部导航栏一直闪 + 视频不停重新加载**。
     * 头像只是 `@Transient` 的展示字段，**不影响视频地址**，所以完全没必要碰 state。
     */
    private fun applyNjavAvatar(video: HanimeVideo, artistName: String, avatar: String) {
        // 已经翻页到别的片子了就别写 —— 否则会把 A 的头像画到 B 的页面上。
        val current = _hanimeVideoFlow.value ?: return
        if (current.title != video.title) return

        _hanimeVideoFlow.value = current.copy(
            artists = current.artists.map {
                if (it.name == artistName) it.copy(avatarUrl = avatar) else it
            },
            artist = current.artist?.let {
                if (it.name == artistName) it.copy(avatarUrl = avatar) else it
            },
        )
    }

    fun restoreFromCacheIfExists(code: String): Boolean {
        val cached = videoIntroUiStateMap[code]?.cachedVideo?.withLocalizedLabels() ?: return false
        updateVideoIntroUiState(code) { copy(introRestored = true) }
        _hanimeVideoFlow.value = cached
        _hanimeVideoStateFlow.value = VideoLoadingState.Success(cached)
        return true
    }



    private val _addToFavVideoFlow = MutableSharedFlow<WebsiteState<Boolean>>()
    val addToFavVideoFlow = _addToFavVideoFlow.asSharedFlow()

    private val _loadDownloadedFlow = MutableSharedFlow<HanimeDownloadEntity?>()
    val loadDownloadedFlow = _loadDownloadedFlow.asSharedFlow()

    fun addToFavVideo(
        videoCode: String,
        currentUserId: String?,
    ) = modifyFavVideoInternal(videoCode, likeStatus = false, currentUserId)

    fun removeFromFavVideo(
        videoCode: String,
        currentUserId: String?,
    ) = modifyFavVideoInternal(videoCode, likeStatus = true, currentUserId)

    private fun modifyFavVideoInternal(
        videoCode: String,
        likeStatus: Boolean,
        currentUserId: String?,
    ) {
        viewModelScope.launch {
            NetworkRepo.addToMyFavVideo(
                videoCode, likeStatus, currentUserId, csrfToken
            ).collect { state ->
                _addToFavVideoFlow.emit(state)
                if (likeStatus) {
                    _hanimeVideoFlow.update { it?.rateVideo(isPositive = true) }
                } else {
                    _hanimeVideoFlow.update { it?.rateVideo(isPositive = true) }
                }
            }
        }
    }

    fun rateVideo(video: HanimeVideo, isPositive: Boolean) {
        viewModelScope.launch {
            NetworkRepo.rateVideo(
                videoCode = videoCode,
                isPositive = isPositive,
                likeStatus = video.isFav,
                unlikeStatus = video.isUnlike,
                likesCount = video.favTimes ?: 0,
                unlikesCount = video.unlikesCount ?: 0,
                currentUserId = video.currentUserId,
                token = csrfToken,
            ).collect { state ->
                _addToFavVideoFlow.emit(state)
                if (state is WebsiteState.Success) {
                    _hanimeVideoFlow.update { it?.rateVideo(isPositive) }
                }
            }
        }
    }

    private val _modifyMyListFlow = MutableSharedFlow<WebsiteState<Int>>()
    val modifyMyListFlow = _modifyMyListFlow.asSharedFlow()

    fun modifyMyList(
        listCode: String,
        videoCode: String,
        isChecked: Boolean,
        position: Int,
    ) {
        viewModelScope.launch {
            NetworkRepo.addToMyList(listCode, videoCode, isChecked, position, csrfToken).collect {
                _modifyMyListFlow.emit(it)
                _hanimeVideoFlow.update { prev ->
                    val myList = prev?.myList?.myListInfo.orEmpty().toMutableList()
                    myList[position] = myList[position].copy(isSelected = isChecked)
                    prev?.copy(myList = prev.myList?.copy(myListInfo = myList))
                }
            }
        }
    }

    private val _localFavoriteActionFlow = MutableSharedFlow<WebsiteState<Boolean>>()
    val localFavoriteActionFlow = _localFavoriteActionFlow.asSharedFlow()

    private val _localMyListActionFlow = MutableSharedFlow<WebsiteState<Boolean>>()
    val localMyListActionFlow = _localMyListActionFlow.asSharedFlow()

    fun toggleLocalFavorite() {
        val video = _hanimeVideoFlow.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val isFavorite = LocalListRepository.isFavorite(videoCode)
                if (isFavorite) {
                    LocalListRepository.removeItem(LocalListRepository.FAVORITE_CODE, videoCode)
                } else {
                    LocalListRepository.setFavorite(videoCode, video, add = true)
                }
                !isFavorite
            }.onSuccess { isFavorite ->
                _localFavoriteActionFlow.emit(WebsiteState.Success(isFavorite))
            }.onFailure {
                _localFavoriteActionFlow.emit(WebsiteState.Error(it))
            }
        }
    }

    fun updateLocalMyListSelection(
        myList: HanimeVideo.MyList,
        selectedStates: List<Boolean>,
    ) {
        val video = _hanimeVideoFlow.value ?: return
        val changes = myList.myListInfo.mapIndexedNotNull { index, info ->
            val newChecked = selectedStates.getOrNull(index) ?: return@mapIndexedNotNull null
            if (info.isSelected == newChecked) null else info to newChecked
        }
        if (changes.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                changes.forEach { (info, newChecked) ->
                    if (info.code == LocalListRepository.WATCH_LATER_CODE) {
                        LocalListRepository.setWatchLater(videoCode, video, newChecked)
                    } else {
                        LocalListRepository.setPlaylistContains(
                            info.code,
                            videoCode,
                            video,
                            newChecked,
                        )
                    }
                }
            }.onSuccess {
                _localMyListActionFlow.emit(WebsiteState.Success(true))
            }.onFailure {
                _localMyListActionFlow.emit(WebsiteState.Error(it))
            }
        }
    }

    fun insertWatchHistory(history: WatchHistoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.WatchHistory.insert(history)
            LogUtil.d("insert_watch_hty", "$history DONE!")
        }
    }

    fun insertWatchHistoryWithCover(history: WatchHistoryEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.WatchHistory.insert(history)
        }
    }

    fun findDownloadedHanime(videoCode: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val info = DatabaseRepo.HanimeDownload.find(videoCode)
            _loadDownloadedFlow.emit(info)
        }
    }

    // true代表已关注成功，false代表取消关注成功
    private val _subscribeArtistFlow = MutableSharedFlow<WebsiteState<Boolean>>()
    val subscribeArtistFlow = _subscribeArtistFlow.asSharedFlow()

    fun subscribeArtist(
        userId: String,
        artistId: String,
    ) {
        viewModelScope.launch {
            NetworkRepo.subscribeArtist(csrfToken, userId, artistId, true).collect { state ->
                _subscribeArtistFlow.emit(state)
                if (state is WebsiteState.Success) {
                    _hanimeVideoFlow.update {
                        it?.copy(artist = it.artist?.copy(post = it.artist.post?.copy(isSubscribed = true)))
                    }
                    recordSubscribedArtistLocally()
                }
            }
        }
    }

    /**
     * ⭐ 在 hanime 上订阅成功之后，**顺手把这位作者记进本机关注库**。
     *
     * 这是「订阅 → 同步到我自己的账号」这条链路的起点：
     *
     * ```
     * 点订阅 → hanime 服务端订阅成功 ─┬→ 本机关注库（写一条）
     *                                └→ 用户自建账号（上传一次）
     * ```
     *
     * 于是**退出 hanime 登录之后，订阅列表照样看得见**（读的是自己账号那份）。
     * 取关**不会**把本机这条删掉 —— 与 `AccountSync.merge` 的「只增不删」一致，
     * 同步删数据的风险远大于多留一条。
     *
     * 上传失败静默忽略：本机那份已经写好了，下次同步还会再传。
     */
    private fun recordSubscribedArtistLocally() {
        val artist = _hanimeVideoFlow.value?.artist ?: return
        if (artist.name.isBlank()) return
        viewModelScope.launch {
            val added = runCatching {
                FollowedArtistStore.mergeSubscriptionItems(
                    listOf(SubscriptionItem(artistName = artist.name, avatar = artist.avatarUrl))
                )
            }.getOrDefault(0)
            if (added > 0 && AccountRepository.isLoggedIn) AccountRepository.uploadLocal()
        }
    }

    fun unsubscribeArtist(
        userId: String,
        artistId: String,
    ) {
        viewModelScope.launch {
            NetworkRepo.subscribeArtist(csrfToken, userId, artistId, false).collect { state ->
                _subscribeArtistFlow.emit(state)
                if (state is WebsiteState.Success) {
                    _hanimeVideoFlow.update {
                        it?.copy(artist = it.artist?.copy(post = it.artist.post?.copy(isSubscribed = false)))
                    }
                }
            }
        }
    }

    // boolean: 成功 or 失敗，String: 提示信息
    data class HKeyframeResult(
        val succeeded: Boolean,
        val messageResId: Int,
        val args: List<Any> = emptyList(),
    )

    private val _modifyHKeyframeFlow = MutableSharedFlow<HKeyframeResult>()
    val modifyHKeyframeFlow = _modifyHKeyframeFlow.asSharedFlow()
    private val _forceRefresh = MutableSharedFlow<Unit>(replay = 1)
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeKeyframe(videoCode: String): Flow<HKeyframeEntity?> {
        return _forceRefresh
            .onStart { emit(Unit) }
            .flatMapLatest {
                DatabaseRepo.HKeyframe.observe(videoCode).flowOn(Dispatchers.IO)
            }
    }
    fun appendHKeyframe(videoCode: String, title: String, hKeyframe: HKeyframeEntity.Keyframe) {
        viewModelScope.launch(Dispatchers.IO) {
            run {
                this@VideoViewModel.hKeyframes?.keyframes?.forEach { keyframeInDb ->
                    if (abs(keyframeInDb.position - hKeyframe.position) < MIN_H_KEYFRAME_SAVE_INTERVAL) {
                        LogUtil.d("HKeyframe", "append_hkeyframe:time conflict: $keyframeInDb")
                        _modifyHKeyframeFlow.emit(
                            HKeyframeResult(
                                succeeded = false,
                                messageResId = R.string.interval_must_greater_than_d,
                                args = listOf(MIN_H_KEYFRAME_SAVE_INTERVAL / 1_000L),
                            )
                        )
                        return@run
                    }
                }
                DatabaseRepo.HKeyframe.appendKeyframe(videoCode, title, hKeyframe)
                LogUtil.d("HKeyframe", "append_hkeyframe:$hKeyframe DONE!")
                _modifyHKeyframeFlow.emit(HKeyframeResult(true, R.string.add_success))
                _forceRefresh.emit(Unit)
            }
        }
    }

    fun modifyHKeyframe(
        videoCode: String,
        oldKeyframe: HKeyframeEntity.Keyframe,
        newKeyframe: HKeyframeEntity.Keyframe,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.HKeyframe.modifyKeyframe(videoCode, oldKeyframe, newKeyframe)
            _modifyHKeyframeFlow.emit(HKeyframeResult(true, R.string.modify_success))
            _forceRefresh.emit(Unit)
        }
    }

    fun removeHKeyframe(videoCode: String, hKeyframe: HKeyframeEntity.Keyframe) {
        viewModelScope.launch(Dispatchers.IO) {
            DatabaseRepo.HKeyframe.removeKeyframe(videoCode, hKeyframe)
            _modifyHKeyframeFlow.emit(HKeyframeResult(true, R.string.delete_success))
            _forceRefresh.emit(Unit)
        }
    }
}
