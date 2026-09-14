package io.github.daisukikaffuchino.han1meviewer.logic.network

import io.github.daisukikaffuchino.han1meviewer.GETCHU_BASE_URL
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.network.service.GetchuService
import io.github.daisukikaffuchino.han1meviewer.logic.network.service.HanimeBaseService
import io.github.daisukikaffuchino.han1meviewer.logic.network.service.HanimeCommentService
import io.github.daisukikaffuchino.han1meviewer.logic.network.service.HanimeMyListService
import io.github.daisukikaffuchino.han1meviewer.logic.network.service.HanimeSubscriptionService

/**
 * @project Hanime1
 * @author Yenaly Liew
 * @time 2022/06/08 008 22:35
 */
object HanimeNetwork {
    var hanimeService = _hanimeService
        private set
    var getchuService = _getchuService
        private set
    var commentService = _commentService
        private set
    var myListService = _myListService
        private set
    var subscriptionService = _subscriptionService
        private set

    /**
     * ⚠️ 这里四个服务**全部**是 hanime 独有的（首页 / 搜索 / 详情 / 评论 / 我的清单 / 订阅），
     * 所以 baseUrl 一律取 [SettingsRepository.hanimeBaseUrl]，**不能取 `HANIME_BASE_URL`** ——
     * 那个会跟着当前数据源变成 pornhub.com / njavtv.com，于是在 AV 站点下登录、收藏、订阅
     * 全会打到错误的站点上（用户 2026-09-14 报的「登录 hanime 显示错误」）。
     */
    private val hanimeUrl get() = SettingsRepository.hanimeBaseUrl

    private val _hanimeService
        get() = ServiceCreator.create<HanimeBaseService>(hanimeUrl)

    private val _getchuService
        get() = ServiceCreator.createGetchu<GetchuService>(GETCHU_BASE_URL)

    private val _commentService
        get() = ServiceCreator.create<HanimeCommentService>(hanimeUrl)

    private val _myListService
        get() = ServiceCreator.create<HanimeMyListService>(hanimeUrl)

    private val _subscriptionService
        get() = ServiceCreator.create<HanimeSubscriptionService>(hanimeUrl)

    fun rebuildNetwork() {
        ServiceCreator.rebuildOkHttpClient()
        hanimeService = _hanimeService
        getchuService = _getchuService
        commentService = _commentService
        myListService = _myListService
        // ⚠️ 以前漏了这一个：切换镜像后订阅服务还指着旧域名。
        subscriptionService = _subscriptionService
    }
}
