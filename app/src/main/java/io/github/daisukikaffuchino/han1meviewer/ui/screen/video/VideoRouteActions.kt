package io.github.daisukikaffuchino.han1meviewer.ui.screen.video

import android.content.Context
import androidx.glance.appwidget.updateAll
import io.github.daisukikaffuchino.han1meviewer.HCacheManager
import io.github.daisukikaffuchino.han1meviewer.logic.DatabaseRepo
import io.github.daisukikaffuchino.han1meviewer.logic.FollowedArtistStore
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.account.AccountRepository
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.getHanimeVideoDownloadLink
import io.github.daisukikaffuchino.han1meviewer.getHanimeVideoLink
import io.github.daisukikaffuchino.han1meviewer.logic.dao.CheckInRecordDatabase
import io.github.daisukikaffuchino.han1meviewer.logic.entity.CheckInRecordEntity
import io.github.daisukikaffuchino.han1meviewer.logic.entity.download.DownloadGroupEntity
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeVideo
import io.github.daisukikaffuchino.han1meviewer.logic.model.SearchOption
import io.github.daisukikaffuchino.han1meviewer.ui.activity.MainActivity
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.ArtistRoute
import io.github.daisukikaffuchino.han1meviewer.ui.navigation.main.SearchRoute
import io.github.daisukikaffuchino.han1meviewer.ui.widget.CheckInWidget
import io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.VideoViewModel
import io.github.daisukikaffuchino.han1meviewer.worker.HanimeDownloadManager
import io.github.daisukikaffuchino.han1meviewer.worker.HanimeDownloadWorker
import io.github.daisukikaffuchino.utils.SonnerToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoRouteActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val viewModel: VideoViewModel,
    private val genres: List<SearchOption>,
    private val onPendingDownloadPromptChange: (DownloadPromptState?) -> Unit,
    private val getCheckedQuality: () -> String?,
    private val setCheckedQuality: (String?) -> Unit,
    private val onOpenUri: (String) -> Unit,
    private val onCopyText: (String) -> Unit,
    private val onRequestUnsubscribe: (HanimeVideo.Artist) -> Unit,
    private val onRequestNotificationPermission: () -> Unit,
    private val onRequestLocalListAction: (() -> Unit) -> Unit,
) {
    /**
     * 点作者 → **一律进作者页**（[ArtistRoute]）。
     *
     * 26.6.5 起不再在这里分流：三个站点统一进作者页，取数由
     * [io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo.getArtistVideos]
     * 按**作者自己的站点**决定（Pornhub 走站点作者页且有真分页、nJAV 走女优页、
     * hanime 没有作者页就退化成按名字+分类搜索，但照样装在作者页的壳里）。
     *
     * ⚠️ 之前这里用「当前站点」当作者站点（`ArtistRef.from(artist, SettingsRepository.siteSource)`），
     * 于是老记录（没有站点字段、url 又是 Pornhub 的相对路径 `/pornstar/x`）被判成 hanime，
     * 跑到 hanime 搜一个欧美名字 → **404**。现在站点判定统一收在 [ArtistRef.siteSource] 里，
     * 以 url 的路径形态为准。
     */
    fun openArtist(artist: HanimeVideo.Artist) {
        val ref = ArtistRef.from(
            artist = artist,
            site = SettingsRepository.siteSource,
            genreKey = hanimeGenreKeyFor(artist),
        )
        (context as? MainActivity)?.mainBackStack?.add(
            ArtistRoute(ArtistRef.encode(ref))
        )
    }

    /**
     * 把作者自带的分类文案映射成 hanime 搜索用的 genre key（映射不上返回空串）。
     *
     * 尊重设置里的「搜索作者时忽略视频类型」：打开它就一直返回空串 ——
     * 这条设置原本管的是 `openArtistSearch` 拼不拼 genre，现在管的是**合成作者页**拼不拼，
     * 语义没变，只是换了个地方生效。
     */
    private fun hanimeGenreKeyFor(artist: HanimeVideo.Artist): String {
        if (SettingsRepository.searchArtistIgnoreVideoType) return ""
        return genres.firstOrNull { option ->
            option.lang?.let { lang ->
                artist.genre == lang.zhrCN ||
                        artist.genre == lang.zhrTW ||
                        artist.genre == lang.en
            } == true
        }?.searchKey.orEmpty()
    }

    fun openTagSearch(tag: String) {
        (context as? MainActivity)?.mainBackStack?.add(SearchRoute(query = tag))
    }

    fun toggleArtistSubscription(artist: HanimeVideo.Artist) {
        val post = artist.post
        if (post == null) {
            // Pornhub / nJAV 没有订阅接口（站点订阅要登录、也没有公开 API），
            // 关注态只能存在本机 —— 见 FollowedArtistStore。
            // ⚠️ 存**整份** ArtistRef（含站点与作品数/关注者数）：关注列表点进作者页时
            //    要拿它画头部，只存名字+头像的话作者页上什么都显示不出来。
            scope.launch {
                val followed = FollowedArtistStore.toggle(
                    ArtistRef.from(artist, SettingsRepository.siteSource)
                )
                // 关注是「本机的事实」，同时也要跟着用户自己的账号走 → 立刻传一次。
                if (AccountRepository.isLoggedIn) AccountRepository.uploadLocal()
                SonnerToast.success(
                    if (followed) R.string.artist_followed else R.string.artist_unfollow
                )
            }
            return
        }
        if (!SettingsRepository.isAlreadyLogin) {
            SonnerToast.warning(R.string.login_first)
            return
        }
        if (artist.isSubscribed) {
            onRequestUnsubscribe(artist)
        } else {
            viewModel.subscribeArtist(post.userId, post.artistId)
        }
    }

    fun confirmUnsubscribe(artist: HanimeVideo.Artist) {
        val post = artist.post ?: return
        viewModel.unsubscribeArtist(post.userId, post.artistId)
    }

    /**
     * 「喜欢 / 加入喜欢」按钮。
     *
     * ## ⭐ 落地到哪里，看的是**这部片子在哪个站点**，不是「有没有登录 hanime」
     *
     * | 情况 | 存哪 |
     * |---|---|
     * | Pornhub / nJAV 数据源 | **本机库**（这两个站没有服务端收藏） |
     * | hanime 数据源但未登录 | 本机库 |
     * | hanime 数据源 + 已登录 | hanime 服务端 |
     *
     * 以前只判「有没有登录 hanime」，于是在 Pornhub 下登录了 hanime 之后点喜欢，会拿着
     * 一个 Pornhub 的 viewkey 去请求 hanime 的收藏接口 —— 表现就是**白等好几秒然后失败**
     * （用户 2026-09-14 报的「延迟非常高」）；而未登录时会弹「本机数据与在线数据独立」的提示，
     * 可那提示对 AV 站点根本不成立（它们本来就没有在线数据），用户看到的就是一句莫名其妙的
     * 「未登录时的数据」（同一天报的第二个问题）。
     *
     * 本机库里的东西**会同步到用户自建的账号**（见 [io.github.daisukikaffuchino.han1meviewer.logic.account.AccountSync]），
     * 所以「本机」不再是「孤立的一份数据」，提示的必要性也随之消失 —— 只有在本机什么都没有
     * （既没登 hanime 也没登自建账号）且当前是 hanime 站时，才还值得提醒一次。
     */
    fun toggleFavorite(video: HanimeVideo) {
        if (SettingsRepository.useLocalVideoState) {
            if (SettingsRepository.isAvSite) {
                // AV 站点没有服务端收藏可谈，直接落本机，不弹任何说明。
                viewModel.toggleLocalFavorite()
            } else {
                onRequestLocalListAction(viewModel::toggleLocalFavorite)
            }
            return
        }
        if (video.isFav) {
            viewModel.removeFromFavVideo(viewModel.videoCode, video.currentUserId)
        } else {
            viewModel.addToFavVideo(viewModel.videoCode, video.currentUserId)
        }
    }

    fun rateVideo(video: HanimeVideo, isPositive: Boolean) {
        if (SettingsRepository.useLocalVideoState) {
            SonnerToast.warning(R.string.login_first)
            return
        }
        viewModel.rateVideo(video, isPositive)
    }

    fun updateMyListSelection(
        myList: HanimeVideo.MyList?,
        selectedStates: List<Boolean>,
    ) {
        if (SettingsRepository.useLocalVideoState) {
            val localMyList = myList
            if (localMyList != null && localMyList.myListInfo.isNotEmpty()) {
                viewModel.updateLocalMyListSelection(localMyList, selectedStates)
            }
            return
        }
        if (myList == null || myList.myListInfo.isEmpty()) {
            SonnerToast.warning(R.string.login_first)
            return
        }
        myList.myListInfo.forEachIndexed { index, info ->
            val newChecked = selectedStates.getOrNull(index) ?: return@forEachIndexed
            if (info.isSelected != newChecked) {
                viewModel.modifyMyList(
                    listCode = info.code,
                    videoCode = viewModel.videoCode,
                    isChecked = newChecked,
                    position = index,
                )
            }
        }
    }

    fun quickCheckIn(record: CheckInRecordEntity) {
        scope.launch(Dispatchers.IO) {
            CheckInRecordDatabase.getDatabase(context).checkInDao().insert(record)
            runCatching { CheckInWidget().updateAll(context) }
            withContext(Dispatchers.Main) {
                SonnerToast.success(R.string.checkin_success)
            }
        }
    }

    fun openIntroductionLink(link: String) {
        try {
            onOpenUri(link)
        } catch (_: Exception) {
            onCopyText(link)
            SonnerToast.success(R.string.copy_to_clipboard)
        }
    }

    fun openOriginalComic(comicLink: String) {
        runCatching { onOpenUri(comicLink) }
            .onFailure { SonnerToast.error(R.string.fault_prompt) }
    }

    fun openVideoWebPage() {
        onOpenUri(getHanimeVideoLink(viewModel.videoCode))
    }

    fun openOfficialDownloadPage() {
        onOpenUri(getHanimeVideoDownloadLink(viewModel.videoCode))
    }

    fun startDownloadFlow(videoData: HanimeVideo) {
        if (videoData.videoUrls.isEmpty()) {
            SonnerToast.warning(R.string.no_video_links_found)
            return
        }
        viewModel.findDownloadedHanime(viewModel.videoCode)
    }

    fun confirmPendingDownload(
        videoData: HanimeVideo,
        pendingDownloadPrompt: DownloadPromptState?,
        autoCreateGroup: Boolean,
    ) {
        val redownload = pendingDownloadPrompt?.oldQuality != null
        onPendingDownloadPromptChange(null)
        scope.launch {
            val groupName = videoData.downloadGroupName()
            val groupId = if (autoCreateGroup && groupName.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    DatabaseRepo.HanimeDownload.getOrCreateGroup(groupName)
                }
            } else {
                pendingDownloadPrompt?.oldGroupId ?: DownloadGroupEntity.DEFAULT_GROUP_ID
            }
            enqueueDownloadWork(
                videoData = videoData,
                groupId = groupId,
                redownload = redownload,
            )
        }
    }

    private fun HanimeVideo.downloadGroupName(): String =
        sequenceOf(playlist?.playlistName, chineseTitle, title)
            .firstNotNullOfOrNull { candidate -> candidate?.trim()?.takeIf(String::isNotEmpty) }
            .orEmpty()

    private suspend fun enqueueDownloadWork(
        videoData: HanimeVideo,
        groupId: Int,
        redownload: Boolean = false,
    ) {
        onRequestNotificationPermission()
        val quality = getCheckedQuality()
        withContext(Dispatchers.IO) {
            HCacheManager.saveHanimeVideoInfo(context, viewModel.videoCode, videoData)
        }
        HanimeDownloadManager.addTask(
            HanimeDownloadWorker.Args(
                quality = quality,
                downloadUrl = videoData.videoUrls[quality]?.link,
                videoType = videoData.videoUrls[quality]?.suffix,
                hanimeName = videoData.title,
                videoCode = viewModel.videoCode,
                coverUrl = videoData.coverUrl,
                groupId = groupId,
            ),
            redownload = redownload,
        )
    }

}
