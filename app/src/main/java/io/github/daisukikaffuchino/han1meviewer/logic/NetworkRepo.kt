package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.han1meviewer.EMPTY_STRING
import io.github.daisukikaffuchino.han1meviewer.HANIME_GENRE_ANIME
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository.isAlreadyLogin
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.exception.CloudflareBlockedException
import io.github.daisukikaffuchino.han1meviewer.logic.exception.HanimeNotFoundException
import io.github.daisukikaffuchino.han1meviewer.logic.exception.IPBlockedException
import io.github.daisukikaffuchino.han1meviewer.logic.exception.ParseException
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistVideosPage
import io.github.daisukikaffuchino.han1meviewer.logic.model.CommentPlace
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimePreview
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeVideo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HomePage
import io.github.daisukikaffuchino.han1meviewer.logic.model.ModifiedPlaylistArgs
import io.github.daisukikaffuchino.han1meviewer.logic.model.MyListType
import io.github.daisukikaffuchino.han1meviewer.logic.model.NjavActress
import io.github.daisukikaffuchino.han1meviewer.logic.model.OnlineWatchHistorySort
import io.github.daisukikaffuchino.han1meviewer.logic.model.SiteSource
import io.github.daisukikaffuchino.han1meviewer.logic.model.VideoCommentArgs
import io.github.daisukikaffuchino.han1meviewer.logic.model.VideoComments
import io.github.daisukikaffuchino.han1meviewer.logic.ph.PhNetwork
import io.github.daisukikaffuchino.han1meviewer.logic.ph.PhParser
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavNetwork
import io.github.daisukikaffuchino.han1meviewer.logic.njav.NjavParser
import io.github.daisukikaffuchino.han1meviewer.logic.network.HanimeNetwork
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.VideoLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import io.github.daisukikaffuchino.utils.applicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.json.JSONObject
import retrofit2.Response
import java.io.File
import javax.net.ssl.SSLHandshakeException

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 22:38
 */
object NetworkRepo {

    //<editor-fold desc="Hanime">

    fun getHomePage(): Flow<WebsiteState<HomePage>> =
        when {
            SettingsRepository.isNjavSite -> njavHomePageFlow()
            SettingsRepository.isPornhubSite -> phHomePageFlow()
            else -> websiteIOFlow(
                request = { HanimeNetwork.hanimeService.getHomePage(SettingsRepository.homeUrl) },
                action = Parser::homePageVer2
            )
        }

    fun getHanimeSearchResult(
        page: Int, query: String?, genre: String?,
        sort: String?, broad: Boolean, date: String?,
        duration: String?, tags: Set<String>, brands: Set<String>,
        actressPath: String? = null,
    ): Flow<PageLoadingState<MutableList<HanimeInfo>>> =
        when {
            SettingsRepository.isNjavSite -> njavListFlow(
                page = page,
                url = resolveNjavListUrl(page, query, genre, sort, tags, actressPath),
            )

            SettingsRepository.isPornhubSite -> phListFlow(
                page = page,
                url = resolvePhListUrl(page, query, genre, sort, tags),
            )

            else -> pageIOFlow(
                request = {
                    HanimeNetwork.hanimeService.getHanimeSearchResult(
                        page, query, genre, sort,
                        if (broad) "on" else null,
                        date, duration, tags, brands
                    )
                },
                action = Parser::hanimeSearch
            )
        }

    fun getHanimeVideo(videoCode: String): Flow<VideoLoadingState<HanimeVideo>> =
        when {
            SettingsRepository.isNjavSite -> njavVideoFlow(videoCode)
            SettingsRepository.isPornhubSite -> phVideoFlow(videoCode)
            else -> videoIOFlow(
                request = { HanimeNetwork.hanimeService.getHanimeVideo(videoCode) },
                action = Parser::hanimeVideoVer2
            )
        }

    fun getHanimePreview(date: String): Flow<WebsiteState<HanimePreview>> =
        when {
            // nJAV / Pornhub 都没有「新番预告」这种月历页，日历里返回空态而不是报错。
            SettingsRepository.isNjavSite -> flowOf(NjavParser.emptyPreview())
            SettingsRepository.isPornhubSite -> flowOf(PhParser.emptyPreview())
            else -> websiteIOFlow(
                request = { HanimeNetwork.hanimeService.getHanimePreview(date) },
                action = Parser::hanimePreview
            )
        }

    /**
     * 【月度归档】按「上市月份」检索该月全部上市的番剧。
     *
     * 站方的 `/previews/{yyyyMM}` 只更新到 2026-04，自 `202605` 起整段返回 HTTP 500，
     * 所以停更之后的月份不能再当"预告"来展示。好在搜索接口本身就支持按上市年月筛选，
     * 参数形如 `date=2026 年 8 月`（与 [io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.SearchViewModel.getSearchDate]
     * 拼出来的格式一致）：
     *
     *     /search?sort=最新上市&date=2026 年 8 月
     *
     * 它返回的是**该月已经上市**的番剧，正好用来顶替停更月份的预告 ——
     * 打开 2026/8 列出的就是 8 月 1 日至 8 月底上线的那一批。
     *
     * @param year 年份，如 2026
     * @param month 月份 (1-12)
     * @param page 页码，从 1 开始
     */
    // 【月度归档】站方预告停更月份改用「按上市月份检索」。
    // 注意必须带上 genre = "裏番"（genre.json 里「里番」的 search_key）：
    // 不带 genre 的搜索结果会混进 3D动画 / MMD / Cosplay / AI生成 等其它分类，
    // 而这个页面的标题就是「某月 里番新番列表」，只应记录里番。
    fun getHanimeArchiveByMonth(
        year: Int,
        month: Int,
        page: Int,
    ): Flow<PageLoadingState<MutableList<HanimeInfo>>> =
        if (SettingsRepository.isNjavSite || SettingsRepository.isPornhubSite) {
            // nJAV / Pornhub 都没有「按上市月份」归档接口，日历在该数据源下只展示空态。
            flowOf<PageLoadingState<MutableList<HanimeInfo>>>(PageLoadingState.NoMoreData)
        } else {
            pageIOFlow(
                request = {
                    HanimeNetwork.hanimeService.getHanimeSearchResult(
                        page = page,
                        genre = HANIME_GENRE_ANIME,
                        sort = "最新上市",
                        date = "$year 年 $month 月",
                    )
                },
                action = Parser::hanimeSearch
            )
        }

    //获取订阅或者可以说是关注列表及它们的更新
    fun getMySubscriptions(page: Int) = websiteIOFlow(
        request = { HanimeNetwork.hanimeService.getMySubscriptions(page) },
        action = Parser::getMySubscriptions
    )
    //</editor-fold>

    //<editor-fold desc="My List">

    fun getMyListItems(userId: String, listType: Any, page: Int) = pageIOFlow(
        request = {
            when (listType) {
                is String ->
                    HanimeNetwork.myListService.getMyListItems(userId, listType, page)

                is MyListType ->
                    HanimeNetwork.myListService.getMyListItems(userId, listType.value, page)

                else ->
                    throw IllegalArgumentException("typeOrId must be String or MyListType")
            }
        },
        action = Parser::myListItems
    )

    fun getMyPlayListItems(page: Int = 1, listCode: String = "0") = pageIOFlow(
        request = {
            HanimeNetwork.myListService.getMyPlayListItems(listCode, page)
        },
        action = Parser::myPlayListItems
    )

    fun getOnlineWatchHistories(
        userId: String,
        sort: OnlineWatchHistorySort,
        page: Int,
    ) = pageIOFlow(
        request = {
            HanimeNetwork.myListService.getOnlineWatchHistories(userId, sort.value, page)
        },
        action = Parser::onlineWatchHistoryItems,
    )

    fun getUserAccountPage(userId: String) = websiteIOFlow(
        request = { HanimeNetwork.myListService.getUserAccountPage(userId) },
        action = Parser::userAccountPage,
    )

    fun updateUserAccountProfile(
        userId: String,
        csrfToken: String?,
        name: String,
        email: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.updateUserAccountProfile(
                userId = userId,
                csrfToken = csrfToken,
                name = name,
                email = email,
            )
        },
        permittedSuccessCode = intArrayOf(302),
    ) {
        if (it.isBlank()) {
            WebsiteState.Success(Unit)
        } else {
            when (val result = Parser.userAccountPage(it)) {
                is WebsiteState.Error -> WebsiteState.Error(result.throwable)
                else -> WebsiteState.Success(Unit)
            }
        }
    }

    fun updateUserAccountPassword(
        userId: String,
        csrfToken: String?,
        oldPassword: String,
        newPassword: String,
        newPasswordConfirm: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.updateUserAccountPassword(
                userId = userId,
                csrfToken = csrfToken,
                oldPassword = oldPassword,
                newPassword = newPassword,
                newPasswordConfirm = newPasswordConfirm,
            )
        },
        permittedSuccessCode = intArrayOf(302),
    ) {
        if (it.isBlank()) {
            WebsiteState.Success(Unit)
        } else {
            when (val result = Parser.userAccountPage(it)) {
                is WebsiteState.Error -> WebsiteState.Error(result.throwable)
                else -> WebsiteState.Success(Unit)
            }
        }
    }

    fun updateUserAccountAvatar(
        userId: String,
        csrfToken: String?,
        avatarFile: File,
    ) = websiteIOFlow(
        request = {
            val imageRequestBody = avatarFile.asRequestBody("image/jpeg".toMediaType())
            val imagePart = MultipartBody.Part.createFormData(
                "photo",
                avatarFile.name,
                imageRequestBody,
            )
            HanimeNetwork.myListService.updateUserAccountAvatar(
                userId = userId,
                csrfToken = (csrfToken ?: EMPTY_STRING).toRequestBody("text/plain".toMediaType()),
                method = "patch".toRequestBody("text/plain".toMediaType()),
                type = "photo".toRequestBody("text/plain".toMediaType()),
                photo = imagePart,
            )
        },
        permittedSuccessCode = intArrayOf(302),
    ) {
        if (it.isBlank()) {
            WebsiteState.Success(Unit)
        } else {
            when (val result = Parser.userAccountPage(it)) {
                is WebsiteState.Error -> WebsiteState.Error(result.throwable)
                else -> WebsiteState.Success(Unit)
            }
        }
    }

    fun deleteOnlineWatchHistory(
        videoCode: String,
        position: Int,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.deleteOnlineWatchHistory(
                videoCode = videoCode,
                csrfToken = csrfToken,
            )
        },
    ) {
        val jsonObject = JSONObject(it)
        val success = jsonObject.optBoolean("success", false)
        if (success) {
            WebsiteState.Success(position)
        } else {
            WebsiteState.Error(IllegalStateException("cannot delete it ?!"))
        }
    }

    fun deleteMyListItems(
        typeOrCode: Any,
        videoCode: String,
        position: Int,
        token: String?,
    ) = websiteIOFlow(
        request = {
            when (typeOrCode) {
                is String ->
                    HanimeNetwork.myListService.deleteMyListItems(
                        typeOrCode, videoCode,
                        csrfToken = token
                    )

                is MyListType ->
                    HanimeNetwork.myListService.deleteMyListItems(
                        typeOrCode.value, videoCode,
                        csrfToken = token
                    )

                else ->
                    throw IllegalArgumentException("typeOrId must be String or MyListType")
            }
        }
    ) { deleteBody ->
        val jsonObject = JSONObject(deleteBody)
        val returnVideoCode = jsonObject.get("video_id").toString()
        if (videoCode == returnVideoCode) {
            return@websiteIOFlow WebsiteState.Success(position)
        }

        return@websiteIOFlow WebsiteState.Error(IllegalStateException("cannot delete it ?!"))
    }

    fun getPlaylists(page: Int, userId: String ) = websiteIOFlow(
        request = { HanimeNetwork.myListService.getPlaylists(userId, page) },
        action = Parser::playlists
    )

    fun addToMyFavVideo(
        videoCode: String,
        likeStatus: Boolean, // false => "": add fav; true => "1": cancel fav;
        currentUserId: String?,
        token: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.addToMyFavVideo(
                videoCode, if (likeStatus) "1" else EMPTY_STRING,
                token, currentUserId
            )
        }
    ) {
        LogUtil.d("add_to_fav_body", it)
        return@websiteIOFlow WebsiteState.Success(likeStatus)
    }

    fun rateVideo(
        videoCode: String,
        isPositive: Boolean,
        likeStatus: Boolean,
        unlikeStatus: Boolean,
        likesCount: Int,
        unlikesCount: Int,
        currentUserId: String?,
        token: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.rateVideo(
                videoCode = videoCode,
                isPositive = if (isPositive) 1 else 0,
                likeStatus = if (likeStatus) "1" else EMPTY_STRING,
                unlikeStatus = if (unlikeStatus) "1" else EMPTY_STRING,
                likesCount = likesCount,
                unlikesCount = unlikesCount,
                csrfToken = token,
                userId = currentUserId,
            )
        }
    ) {
        LogUtil.d("rate_video_body", it)
        return@websiteIOFlow WebsiteState.Success(isPositive)
    }

    fun createPlaylist(
        videoCode: String,
        title: String,
        description: String,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.createPlaylist(
                csrfToken, videoCode, title, description
            )
        },
        permittedSuccessCode = intArrayOf(500)
    ) {
        LogUtil.d("create_playlist_body", it)
        return@websiteIOFlow WebsiteState.Success(Unit)
    }

    fun addToMyList(
        listCode: String,
        videoCode: String,
        isChecked: Boolean,
        position: Int,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.addToMyList(
                csrfToken, listCode, videoCode, isChecked
            )
        }
    ) {
        LogUtil.d("add_to_playlist_body", it)
        return@websiteIOFlow WebsiteState.Success(position)
    }

    fun modifyPlaylist(
        listCode: String,
        title: String,
        description: String,
        delete: Boolean,
        csrfToken: String?,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.myListService.modifyPlaylist(
                listCode, title, description,
                if (delete) "on" else null,
                csrfToken
            )
        },
        permittedSuccessCode = intArrayOf(302)
    ) {
        LogUtil.d("modify_playlist_body", it)
        return@websiteIOFlow WebsiteState.Success(
            ModifiedPlaylistArgs(
                title = title, desc = description, isDeleted = delete,
            )
        )
    }

    //</editor-fold>

    //<editor-fold desc="Comment">

    fun getComments(type: String, code: String) = websiteIOFlow(
        request = { HanimeNetwork.commentService.getComments(type, code) },
        action = Parser::comments
    )

    fun getCommentReply(commentId: String) = websiteIOFlow(
        request = { HanimeNetwork.commentService.getCommentReply(commentId) },
        action = Parser::commentReply
    )

    fun postComment(
        csrfToken: String?,
        currentUserId: String,
        targetUserId: String,
        type: String,
        text: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.postComment(
                csrfToken, currentUserId,
                type, targetUserId, text
            )
        }
    ) {
        LogUtil.d("post_comment_body", it)
        return@websiteIOFlow WebsiteState.Success(Unit)
    }

    fun postCommentReply(
        csrfToken: String?,
        replyCommentId: String,
        text: String,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.postCommentReply(
                csrfToken, replyCommentId, text
            )
        }
    ) {
        LogUtil.d("post_comment_reply_body", it)
        return@websiteIOFlow WebsiteState.Success(Unit)
    }

    fun likeComment(
        csrfToken: String?,
        commentPlace: CommentPlace,
        foreignId: String?,
        isPositive: Boolean, // 你選擇的是讚還是踩，1是讚，0是踩
        likeUserId: String?,
        commentLikesCount: Int,
        commentLikesSum: Int,
        likeCommentStatus: Boolean, // 你之前有沒有點過讚，1是0否
        unlikeCommentStatus: Boolean, // 你之前有沒有點過踩，1是0否
        commentPosition: Int, comment: VideoComments.VideoComment,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.likeComment(
                csrfToken, commentPlace.value, foreignId,
                if (isPositive) 1 else 0,
                likeUserId, commentLikesCount, commentLikesSum,
                if (likeCommentStatus) 1 else 0,
                if (unlikeCommentStatus) 1 else 0
            )
        }
    ) {
        LogUtil.d("like_comment_body", it)
        return@websiteIOFlow WebsiteState.Success(
            VideoCommentArgs(
                commentPosition, isPositive, comment
            )
        )
    }

    fun reportComment(
        csrfToken: String?,
        reason: String,
        currentUserId: String?,
        redirectUrl: String,
        reportableType: String?,
        reportableId: String?
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.commentService.submitReport(
                userId = currentUserId,
                csrfToken = csrfToken,
                redirectUrl = redirectUrl,
                reportableId = reportableId,
                reportableType = reportableType,
                reason = reason
            )
        },
        action = Parser::reportCommentResponse
    )

    //</editor-fold>

    //<editor-fold desc="Subscription">

    fun subscribeArtist(
        csrfToken: String?,
        userId: String,
        artistId: String,
        // 这里表示目标状态
        status: Boolean,
    ) = websiteIOFlow(
        request = {
            HanimeNetwork.subscriptionService.subscribeArtist(
                csrfToken, userId, artistId,
                if (status) "" else "1"
            )
        }
    ) {
        LogUtil.d("subscribe_artist_body", it)
        return@websiteIOFlow WebsiteState.Success(status)
    }

    //</editor-fold>

    //<editor-fold desc="Base">

    fun login(email: String, password: String) = flow {
        emit(WebsiteState.Loading)
        // 首先获取token
        val loginPage = HanimeNetwork.hanimeService.getLoginPage()
        val token = loginPage.body()?.string()?.let(Parser::extractTokenFromLoginPage)
        val req = HanimeNetwork.hanimeService.login(token, email, password)
        if (req.isSuccessful) {
            // 再次获取登录页面，如果失败则返回 cookie
            // 因为登录成功再次访问 login 返回 404，这是判断是否登录成功的方法
            val loginPageAgain = HanimeNetwork.hanimeService.getLoginPage()
            if (loginPageAgain.code() == 404) {
                // Cookie 會返回 XSRF-TOKEN 和 hanime1_session，我們只需要後者
                // 错误的，还需要 remember_web 字段！但我没找到！
                LogUtil.d("login_headers", req.headers().toMultimap().toString())
                emit(WebsiteState.Success(req.headers().values("Set-Cookie")))
            } else {
                emit(WebsiteState.Error(IllegalStateException(getString(R.string.account_or_password_wrong))))
            }
        } else {
            // 雙重保險
            emit(WebsiteState.Error(IllegalStateException(getString(R.string.account_or_password_wrong))))
        }
    }.catch { e ->
        emit(WebsiteState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    //<editor-fold desc="nJAV (njavtv.com) 数据源">

    /**
     * nJAV 首页：并行抓取若干分类页，再拼成一个 [HomePage]。
     *
     * 不复用 [websiteIOFlow] 是因为它不是「一个请求 → 一个页面」，而是 7 个分类页
     * 合起来才凑成首页。单个分类失败不影响整体（`runCatching` 兜成空列表），
     * 免得一个栏目 502 就让整个首页报错。
     */
    private fun njavHomePageFlow(): Flow<WebsiteState<HomePage>> = flow {
        val sections = coroutineScope {
            NjavParser.HOME_SECTIONS.map { (key, path) ->
                async(Dispatchers.IO) {
                    key to runCatching {
                        val response = NjavNetwork.service.get(NjavNetwork.listUrl(path))
                        if (response.isSuccessful) {
                            NjavParser.videoList(response.body()?.string().orEmpty())
                        } else {
                            throw ParseException("nJAV: HTTP ${response.code()} - $path")
                        }
                    }.getOrDefault(mutableListOf<HanimeInfo>())
                }
            }.awaitAll().toMap()
        }
        emit(NjavParser.homePage(sections))
    }.catch { e ->
        emit(WebsiteState.Error(handleNjavException(e)))
    }.flowOn(Dispatchers.IO)

    /** nJAV 列表页（分类 / 搜索）通用管线。 */
    private fun njavListFlow(
        page: Int,
        url: String,
    ): Flow<PageLoadingState<MutableList<HanimeInfo>>> = flow {
        val response = NjavNetwork.service.get(url)
        if (!response.isSuccessful) {
            throw ParseException("nJAV: HTTP ${response.code()} - $url")
        }
        val body = response.body()?.string().orEmpty()
        val list = NjavParser.videoList(body)
        emit(
            if (list.isEmpty() && !NjavParser.hasNextPage(body)) {
                PageLoadingState.NoMoreData
            } else {
                PageLoadingState.Success(list)
            }
        )
    }.catch { e ->
        emit(PageLoadingState.Error(handleNjavException(e)))
    }.flowOn(Dispatchers.IO)

    private fun njavVideoFlow(videoCode: String): Flow<VideoLoadingState<HanimeVideo>> = flow {
        val response = NjavNetwork.service.get(NjavNetwork.detailUrl(videoCode))
        if (!response.isSuccessful) {
            throw ParseException("nJAV: HTTP ${response.code()} - $videoCode")
        }
        emit(NjavParser.video(response.body()?.string().orEmpty()))
    }.catch { e ->
        emit(VideoLoadingState.Error(handleNjavException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * 把 hanime 风格的检索条件翻译成 nJAV 的列表 URL。
     *
     * - 选了女优 → 女优影片列表页 `/{locale}/actresses/{name}`
     * - 有关键词 → 搜索页 `/{locale}/search/{kw}`
     * - 否则按首页分类点击时带下来的「检索标记」（genre / tags / sort）映射到对应分类页
     * - 都没有 → 兜底到「最新」
     *
     * ⚠️ **女优优先级最高**：站点的女优页是一个独立的列表页，关键词 / 分类 / 标签
     * 都拼不进去。所以选中女优时 UI 会把其它条件一并清掉（见
     * [io.github.daisukikaffuchino.han1meviewer.ui.viewmodel.SearchViewModel.applyActressFilter]），
     * 这里的顺序只是兜底，避免出现「点进去却是别的结果」。
     */
    private fun resolveNjavListUrl(
        page: Int,
        query: String?,
        genre: String?,
        sort: String?,
        tags: Set<String>,
        actressPath: String? = null,
    ): String {
        actressPath?.takeIf { it.isNotBlank() }
            ?.let { return NjavNetwork.actressUrl(it, page) }

        val keyword = query?.trim().orEmpty()
        if (keyword.isNotEmpty()) return NjavNetwork.searchUrl(keyword, page)

        val path = sequenceOf(genre).plus(tags.asSequence()).plus(sequenceOf(sort))
            .firstNotNullOfOrNull { NjavParser.pathForMarker(it) }

        return NjavNetwork.listUrl(path ?: "new", page)
    }

    /**
     * nJAV 女优索引（`/cn/actresses`）的第 [page] 页。
     *
     * 站点**不支持在索引页按名字检索**（`?q=` / `?keyword=` / `?name=` 实测都被忽略），
     * 所以这里只负责「按页拉取」，名字过滤交给
     * [io.github.daisukikaffuchino.han1meviewer.ui.screen.search.NjavActressPickerDialog]
     * 在已加载的条目上做。索引默认按作品数从多到少排，**叫得出名字的女优都在前几页**，
     * 所以「先加载几页 + 本地过滤」实际够用。
     */
    fun getNjavActressIndex(page: Int): Flow<PageLoadingState<MutableList<NjavActress>>> = flow {
        val url = NjavNetwork.actressIndexUrl(page)
        val response = NjavNetwork.service.get(url)
        if (!response.isSuccessful) {
            throw ParseException("nJAV: HTTP ${response.code()} - $url")
        }
        val body = response.body()?.string().orEmpty()
        val list = NjavParser.actressList(body)
        emit(
            if (list.isEmpty() && !NjavParser.hasNextPage(body)) {
                PageLoadingState.NoMoreData
            } else {
                PageLoadingState.Success(list)
            }
        )
    }.catch { e ->
        emit(PageLoadingState.Error(handleNjavException(e)))
    }.flowOn(Dispatchers.IO)

    //</editor-fold>

    //<editor-fold desc="Pornhub (pornhub.com) 数据源">

    /**
     * Pornhub 首页：并行请求若干检索条件，再拼成一个 [HomePage]。
     *
     * 与 [njavHomePageFlow] 同一套思路：单个栏目失败不影响整体
     * （`runCatching` 兜成空列表），免得一个栏目抽风就让整个首页报错。
     *
     * ⚠️ 四个栏目是**四个独立请求**（每个约 140 KB 的 JSON，且都要过自建中转），
     * 所以别再加栏目了 —— 每多一个栏目就是首页首屏多等一次境外往返。
     */
    private fun phHomePageFlow(): Flow<WebsiteState<HomePage>> = flow {
        val sections = coroutineScope {
            PhNetwork.HOME_SECTIONS.map { (key, query) ->
                async(Dispatchers.IO) {
                    key to runCatching {
                        val response = PhNetwork.service.get(PhNetwork.apiUrl(page = 1, query = query))
                        if (response.isSuccessful) {
                            PhParser.videoList(response.body()?.string().orEmpty())
                        } else {
                            throw ParseException("Pornhub: HTTP ${response.code()} - $key")
                        }
                    }.getOrDefault(mutableListOf<HanimeInfo>())
                }
            }.awaitAll().toMap()
        }
        emit(PhParser.homePage(sections))
    }.catch { e ->
        emit(WebsiteState.Error(handlePhException(e)))
    }.flowOn(Dispatchers.IO)

    /** Pornhub 列表页（分类 / 搜索）通用管线。 */
    private fun phListFlow(
        page: Int,
        url: String,
    ): Flow<PageLoadingState<MutableList<HanimeInfo>>> = flow {
        val response = PhNetwork.service.get(url)
        if (!response.isSuccessful) {
            throw ParseException("Pornhub: HTTP ${response.code()} - $url")
        }
        // 翻页判据要 page：接口不返回总页数，只能看这一页给满没有。
        emit(PhParser.pageState(response.body()?.string().orEmpty(), page))
    }.catch { e ->
        emit(PageLoadingState.Error(handlePhException(e)))
    }.flowOn(Dispatchers.IO)

    private fun phVideoFlow(videoCode: String): Flow<VideoLoadingState<HanimeVideo>> = flow {
        val response = PhNetwork.service.get(PhNetwork.detailUrl(videoCode))
        if (!response.isSuccessful) {
            throw ParseException("Pornhub: HTTP ${response.code()} - $videoCode")
        }
        val detailHtml = response.body()?.string().orEmpty()
        emit(withPlayablePhMedia(PhParser.video(detailHtml), videoCode, detailHtml))
    }.catch { e ->
        emit(VideoLoadingState.Error(handlePhException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * ⭐⭐ 播放地址「换源」—— 修「视频明明在，一播就 410」。
     *
     * 详情页给的播放地址有两种签名形态，站点**随机**发：
     *
     * | 形态 | 长相 | 结果 |
     * |---|---|---|
     * | A | `?validfrom=…&validto=…&ipa=1&hdl=-1&hash=…` | 可取 |
     * | B | `?h=…&e=…&f=1` | **必 410**（12 种头组合全试过，且 `e=` 明明在未来） |
     *
     * 实测同一分钟连抓 8 次详情页：可取 2–5 次（2026-09-13 那一刻 B 占多数）。
     * 而 `/embed/<viewkey>`（[PhNetwork.embedUrl]）**10/10 次**都发可取形态，
     * 只是只有 480P 一档 —— 所以这里只在**详情页那条不可取**时才去换。
     *
     * ⚠️ 判据是**地址长相**（有没有 `validfrom`），不是去 `HEAD` 探一下：
     * 换一次源要多一趟 48 KB，没必要为「本来就好」的情况付这个钱。
     * 探测另一个坑是 410 与 403/404 混在一起分不清（403 是签名不对，重试无意义）。
     */
    private suspend fun withPlayablePhMedia(
        state: VideoLoadingState<HanimeVideo>,
        videoCode: String,
        detailHtml: String,
    ): VideoLoadingState<HanimeVideo> {
        if (state !is VideoLoadingState.Success) return state
        if (PhParser.mediaLooksPlayable(detailHtml)) return state

        val fallback = runCatching { PhNetwork.service.get(PhNetwork.embedUrl(videoCode)) }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()?.string()
            ?: return state

        return PhParser.videoWithMediaFrom(state.info, fallback)
            ?.let { VideoLoadingState.Success(it) }
            ?: state
    }

    /**
     * 把 hanime 风格的检索条件翻译成 Pornhub 的接口地址。
     *
     * 有关键词就搜索；否则把 [genre] / [tags] / [sort] 里的标记交给
     * [PhParser.queryForMarker] 映射成排序或标签条件，都映射不上就兜底到「最新」。
     */
    private fun resolvePhListUrl(
        page: Int,
        query: String?,
        genre: String?,
        sort: String?,
        tags: Set<String>,
    ): String {
        val keyword = query?.trim().orEmpty()
        if (keyword.isNotEmpty()) {
            return PhNetwork.apiUrl(page, PhNetwork.PhQuery(keyword = keyword))
        }

        val mapped = sequenceOf(genre).plus(tags.asSequence()).plus(sequenceOf(sort))
            .firstNotNullOfOrNull { PhParser.queryForMarker(it) }

        return PhNetwork.apiUrl(page, mapped ?: PhNetwork.PhQuery(ordering = "newest"))
    }

    /**
     * Pornhub 专用的异常处理。
     *
     * 与 [handleNjavException] 同一原则：**有多少信息就给多少**。
     * 通用 [handleException] 会把所有 [ParseException] 的 message 一律换成
     * `parse_error_msg`，而这条信息本身往往就是唯一线索（尤其这个数据源
     * 全程依赖自建中转，报错里「中转不通」和「解析失败」必须能一眼分开）。
     */
    internal fun handlePhException(e: Throwable): Throwable {
        if (e is CancellationException) throw e
        e.printStackTrace()
        val detail = e.message?.takeIf { it.isNotBlank() }
        return ParseException(
            when {
                detail != null && detail.startsWith("Pornhub") -> detail
                detail != null -> "Pornhub 加载失败（${e::class.java.simpleName}）：$detail"
                else -> "Pornhub 加载失败（${e::class.java.simpleName}）"
            }
        )
    }

    //</editor-fold>

    //<editor-fold desc="作者页（跨数据源）">

    /**
     * ⭐ **作者页的作品列表** —— 「点作者进去全是别人的视频」这一条的正解。
     *
     * 三个数据源的能力完全不一样，所以这里按 [ArtistRef.siteSource] 分流：
     *
     * | 数据源 | 取数方式 | 分页 |
     * |---|---|---|
     * | Pornhub `/pornstar|/model/<slug>` | 站点作者页 HTML | **真分页**（`link rel=next`） |
     * | Pornhub `/users…`（无作者页） | 退回按名字搜索（JSON 接口） | 有（30 条/页，但会混进同名者） |
     * | nJAV | 女优页 `/cn/actresses/<编码名>` | 无（站点反爬，见 [njavArtistFlow]） |
     * | hanime | **合成**：按名字（+分类）搜索 | 有 |
     *
     * ⚠️ 分流认的是 [ArtistRef.siteSource]（作者自己的站点），**不是用户当前在哪个站** ——
     * 「在 hanime 域名下点一个 Pornhub 关注的人」必须去问 Pornhub（26.6.3 的 404 就出在这）。
     */
    fun getArtistVideos(
        artist: ArtistRef,
        page: Int,
        /** nJAV 女优页的排序（`sort=`）；其它站点忽略。 */
        sort: String? = null,
        /** nJAV 女优页的筛选（`filters=`）；其它站点忽略。 */
        filter: String? = null,
    ): Flow<PageLoadingState<ArtistVideosPage>> = when (artist.siteSource) {
        SiteSource.Pornhub -> phArtistFlow(artist, page)
        SiteSource.Njav -> njavArtistFlow(artist, page, sort, filter)
        SiteSource.Hanime1 -> hanimeArtistFlow(artist, page)
    }

    /**
     * 按名字在 nJAV 的**女优索引**里找一位女优（26.8）。
     *
     * 为什么需要它：nJAV 的视频详情页只有女优的名字与链接，**没有头像**（女优页顶部那个
     * 圆圈也是首字占位符，不是图片）—— 真头像只在索引页的
     * `fourhoi.com/actress/<id>-t.jpg` 里。所以「关注了某个女优之后关注列表没有头像」
     * 这件事，只能回索引页按名字把她找出来。
     *
     * 代价与边界：索引页每页 52 位、按作品数倒序，**没有名字检索**（`?q=` 被忽略），
     * 所以这里最多翻 [maxPages] 页（默认 3 页 ≈ 156 位，叫得出名字的都在前面）。
     * 找不到就返回 null —— 界面显示占位符，不做无上限的翻页。
     */
    suspend fun findNjavActress(name: String, maxPages: Int = 3): NjavActress? {
        val target = name.trim()
        if (target.isEmpty()) return null
        for (page in 1..maxPages) {
            val result = runCatching {
                val response = NjavNetwork.service.get(NjavNetwork.actressIndexUrl(page))
                if (!response.isSuccessful) return@runCatching emptyList()
                NjavParser.actressList(response.body()?.string().orEmpty())
            }.getOrDefault(emptyList())
            result.firstOrNull { it.name.equals(target, ignoreCase = true) }?.let { return it }
            if (result.isEmpty()) break
        }
        return null
    }

    /**
     * hanime 的「作者页」—— 站点没有这个页面，所以是**合成**的。
     *
     * 做法就是原来 `openArtistSearch` 做的事：拿名字（加上作者自带的分类检索键）去搜索，
     * 但把结果装进作者页的壳里：头部有头像/名字/关注按钮，正文是这个搜索的结果。
     * 比直接把人丢进一个通用搜索页清楚，也让三个站点的作者页长得一样。
     *
     * ⚠️ 这里**不能**复用 [getHanimeSearchResult]：那个函数按**当前站点**分流，
     * 于是「在 Pornhub 域名下点一个 hanime 关注的人」会跑去问 Pornhub。
     * 作者页取数必须认**作者自己的站点**，与用户当前在哪个站无关。
     */
    private fun hanimeArtistFlow(
        artist: ArtistRef,
        page: Int,
    ): Flow<PageLoadingState<ArtistVideosPage>> = flow {
        val response = HanimeNetwork.hanimeService.getHanimeSearchResult(
            page = page,
            query = artist.name,
            genre = artist.genreKey.takeIf { it.isNotBlank() },
        )
        if (!response.isSuccessful) {
            throw ParseException("hanime: HTTP ${response.code()} - ${artist.name}")
        }
        val state = Parser.hanimeSearch(response.body()?.string().orEmpty())
        emit(
            when (state) {
                is PageLoadingState.Success ->
                    PageLoadingState.Success(ArtistVideosPage(profile = null, videos = state.info))

                is PageLoadingState.Error -> state
                PageLoadingState.Loading -> PageLoadingState.Loading
                PageLoadingState.NoMoreData -> PageLoadingState.NoMoreData
            }
        )
    }.catch { e ->
        emit(PageLoadingState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    private fun phArtistFlow(
        artist: ArtistRef,
        page: Int,
    ): Flow<PageLoadingState<ArtistVideosPage>> = flow {
        val artistUrl = PhNetwork.artistVideosUrl(artist.url, page)
        if (artistUrl == null) {
            // 没有站点作者页（`/users/…`、`/channels/…` 之类）：退回按名字搜索。
            // ⚠️ 这条结果**不保证只属于这位作者**，所以 profile 传 null —— 界面别把它
            // 当成「该作者的作品」来标榜，头部仍然用详情页带过来的那份资料。
            val url = PhNetwork.apiUrl(
                page,
                PhNetwork.PhQuery(keyword = artist.name, ordering = "newest"),
            )
            val response = PhNetwork.service.get(url)
            if (!response.isSuccessful) {
                throw ParseException("Pornhub: HTTP ${response.code()} - $url")
            }
            val body = response.body()?.string().orEmpty()
            val list = PhParser.videoList(body)
            emit(
                if (list.isEmpty() && !PhParser.hasNextPage(body, page)) {
                    PageLoadingState.NoMoreData
                } else {
                    PageLoadingState.Success(ArtistVideosPage(profile = null, videos = list))
                }
            )
            return@flow
        }
        val response = PhNetwork.service.get(artistUrl)
        if (!response.isSuccessful) {
            throw ParseException("Pornhub: HTTP ${response.code()} - $artistUrl")
        }
        emit(PhParser.artistPage(response.body()?.string().orEmpty(), page))
    }.catch { e ->
        emit(PageLoadingState.Error(handlePhException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * nJAV 作者页（女优页）。
     *
     * ⚠️ **实测（2026-09-13）：nJAV 的作者页没有分页。**
     * 女优页 `/actresses/<编码名>` 服务端渲染出来的卡片就是全部能拿到的（实测某位女优 4 部），
     * 页面上**没有** `a[rel=next]`；而任何 `?page=N`（无论列表页还是女优页）都会返回
     * 一段 **JS 反爬挑战页**（约 71 KB、零卡片、无 rel=next）—— 非浏览器客户端拿不到内容。
     *
     * 所以这里只请求第一页，翻页判据交给 [NjavParser.hasNextPage]（恒为 false）；
     * 界面表现为「加载到底」，而不是转圈或报错。**不要**为此写个假的 `?page=` 循环：
     * 那只会把挑战页当成空数据，白跑一趟还容易被站点加重限流。
     *
     * 拿不到站点作者页时（关注表里只有名字）退回站点搜索 —— 与 Pornhub 的兜底同一逻辑。
     */
    private fun njavArtistFlow(
        artist: ArtistRef,
        page: Int,
        sort: String? = null,
        filter: String? = null,
    ): Flow<PageLoadingState<ArtistVideosPage>> = flow {
        val path = NjavNetwork.actressPathFrom(artist.url)
        val url = if (path != null) {
            NjavNetwork.actressUrl(path, page, sort, filter)
        } else {
            // 关注表里可能只有名字（老数据 / 从索引里搜到的人）→ 退回站点搜索。
            NjavNetwork.searchUrl(artist.name, page)
        }
        val response = NjavNetwork.service.get(url)
        if (!response.isSuccessful) {
            throw ParseException("nJAV: HTTP ${response.code()} - $url")
        }
        val body = response.body()?.string().orEmpty()
        val list = NjavParser.videoList(body)
        // 资料头（身材 / 生日）只在第一页解析一次 —— 它在页面上是同一个块，
        // 每页都解析一遍纯属浪费；而且排序/筛选切换会重新拉第一页，天然会刷新。
        val profile = if (page <= 1) NjavParser.actressProfile(body) else null
        emit(
            if (list.isEmpty() && !NjavParser.hasNextPage(body)) {
                PageLoadingState.NoMoreData
            } else {
                PageLoadingState.Success(ArtistVideosPage(profile = profile, videos = list))
            }
        )
    }.catch { e ->
        emit(PageLoadingState.Error(handleNjavException(e)))
    }.flowOn(Dispatchers.IO)

    //</editor-fold>

    /**
     * 用于单网页的情况
     *
     * @param permittedSuccessCode 用于处理特殊情况，比如[NetworkRepo.modifyPlaylist]需要302成功
     */
    private fun <T> websiteIOFlow(
        request: suspend () -> Response<ResponseBody>,
        permittedSuccessCode: IntArray? = null,
        action: (String) -> WebsiteState<T>,
    ) = flow {
        val requestResult = request.invoke()
        val resultBody = requestResult.body()?.string()
        val permitted = permittedSuccessCode?.contains(requestResult.code()) == true
        if ((permitted || requestResult.isSuccessful)) {
            emit(action.invoke(resultBody ?: EMPTY_STRING))
        } else {
            requestResult.throwRequestException()
        }
    }.catch { e ->
        emit(WebsiteState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * 用于有page分页的情况
     */
    private fun <T> pageIOFlow(
        request: suspend () -> Response<ResponseBody>,
        action: (String) -> PageLoadingState<T>,
    ) = flow {
        val requestResult = request.invoke()
        val resultBody = requestResult.body()?.string()
        if (requestResult.isSuccessful && resultBody != null) {
            emit(action.invoke(resultBody))
        } else {
            requestResult.throwRequestException()
        }
    }.catch { e ->
        emit(PageLoadingState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    /**
     * 用于影片界面
     */
    private fun <T> videoIOFlow(
        request: suspend () -> Response<ResponseBody>,
        action: (String) -> VideoLoadingState<T>,
    ) = flow {
        val requestResult = request.invoke()
        val resultBody = requestResult.body()?.string()
        if (requestResult.isSuccessful && resultBody != null) {
            emit(action.invoke(resultBody))
        } else {
            requestResult.throwRequestException()
        }
    }.catch { e ->
        emit(VideoLoadingState.Error(handleException(e)))
    }.flowOn(Dispatchers.IO)

    internal fun Response<ResponseBody>.throwRequestException(): Nothing {
        val body = errorBody()?.string()
        when (val code = code()) {
            403 -> if (!body.isNullOrBlank()) {
                when {
                    "you have been blocked" in body ->
                        throw IPBlockedException(getString(R.string.cloudflare_ip_block_warning))

                    "Just a moment" in body ->
                        throw CloudflareBlockedException(getString(R.string.cloudflare_network_mismatch))

                    else ->
                        throw HanimeNotFoundException(getString(R.string.video_might_not_exist)) // 主要出現在影片界面，當你v數不大時會報403
                }
            } else throw IllegalStateException("$code ${message()}")

            500 -> throw HanimeNotFoundException(getString(R.string.video_might_not_exist)) // 主要出現在影片界面，當你v數很大時會報500

            404 -> if (!isAlreadyLogin) {
                throw IllegalStateException(getString(R.string.not_logged_in_currently))
            } else {
                throw IllegalStateException("$code ${message()}")
            }

            else -> throw IllegalStateException("$code ${message()}")
        }
    }

    internal fun handleException(e: Throwable): Throwable {
        return when (e) {
            is CancellationException -> throw e
            is ParseException -> {
                e.printStackTrace()
                ParseException(getString(R.string.parse_error_msg))
            }

            is SSLHandshakeException -> {
                e.printStackTrace()
                SSLHandshakeException(getString(R.string.ssl_handshake_error))
            }

            else -> {
                e.printStackTrace()
                e
            }
        }
    }

    /**
     * nJAV 专用的异常处理。
     *
     * ⚠️ **刻意不复用 [handleException]**：它会把**所有** [ParseException] 的 message
     * 一律替换成 `parse_error_msg`（"可能这个网址解析起来不太一样…"）。那条兜底文案
     * 是给 hanime 那套「开发者风格」的 ParseException 准备的（形如
     * `[Parse::func => var] parse error!`），但 nJAV 抛出的信息本身就是中文、
     * 而且指明了失败在哪一步 —— 替换掉等于**把唯一的线索抹掉**。
     *
     * 真正踩过的坑：`NjavPacker` 的正则在 Android 上编译失败（ICU 与 JVM 的正则
     * 差异），导致那个类被永久标记为「初始化失败」，之后每次引用都抛
     * `NoClassDefFoundError: …logic.njav.NjavPacker`。这条消息**本身已经是全部线索**
     * 了，要是再被通用文案盖掉，就真的无从下手。
     *
     * 所以这里的原则是：**有多少信息就给多少**，并且一定带上异常类名。
     */
    internal fun handleNjavException(e: Throwable): Throwable {
        if (e is CancellationException) throw e
        e.printStackTrace()
        val detail = e.message?.takeIf { it.isNotBlank() }
        return ParseException(
            when {
                // nJAV 自己抛的（"nJAV：…" / "nJAV: HTTP …"），原样保留
                detail != null && detail.startsWith("nJAV") -> detail
                // 其它异常（网络层 / 解析库里冒出来的）：至少让用户和日志看到是哪个类
                detail != null -> "nJAV 加载失败（${e::class.java.simpleName}）：$detail"
                else -> "nJAV 加载失败（${e::class.java.simpleName}）"
            }
        )
    }

    //</editor-fold>

    private fun getString(resId: Int) = applicationContext.getString(resId)
}
