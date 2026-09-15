package io.github.daisukikaffuchino.han1meviewer.logic

import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistRef
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * **关注作者新作检查**（9.0）。
 *
 * ## 一句话
 *
 * 逐个读「关注里的人」的作者页**第一页**，跟本机记下的 [FollowedArtistStore.Item.seenCodes]
 * 对差集，差出来的条数就是「新作数」——写回关注表，订阅页据此画头像右上角的红点数字。
 *
 * ## 为什么是「第一页差集」而不是「作品总数变多」
 *
 * 作品总数（`profile.videoCount`）只有**站点自己**才准，而且它把「已下架 / 未上架」
 * 也算在里面，涨了不等于有新作可看。第一页的作品码是**确定的**：出现一个没见过的新码，
 * 就是真的有一部新片。
 *
 * ## 成本
 *
 * 每位关注者 **1 个请求**（并发 [CONCURRENCY]），所以几十位关注者也就是几秒。
 * 这就是它必须节流（[THROTTLE_MS]）而不是每次重组都跑的原因；只在
 * 「进订阅页」和「后台周期任务」两个时机触发。
 *
 * ## 什么时候**不做**检查
 *
 * 设置里选了 [MODE_OFF] 就直接返回 —— 用户关掉提醒，不该还在后台偷偷跑几十个请求。
 *
 * ## 首次开启时的坑（已处理）
 *
 * 老用户的关注表里 [FollowedArtistStore.Item.seenCodes] 是空的，
 * 如果直接「第一页全是新的」，一开启提醒所有人都亮红点 —— 那不是提醒，那是噪音。
 * 所以**空 seenCodes 只补种、不报数**（见 [FollowedArtistStore.applyUpdateResults]）。
 */
object ArtistUpdateChecker {

    /** 提醒关闭：不检查、不角标。 */
    const val MODE_OFF = 0

    /** 只在软件内显示角标。 */
    const val MODE_IN_APP = 1

    /** 角标 + 手机通知。 */
    const val MODE_NOTIFICATION = 2

    /** 同一个进程内两次自动检查的最小间隔。 */
    private const val THROTTLE_MS = 30 * 60 * 1000L

    /** 并发上限。关注几十个人的时候，一次全放出去会把中转的连接池占满。 */
    private const val CONCURRENCY = 4

    /** 单个作者页的超时。站点偶尔会卡住一个请求，不能让它把整轮检查拖死。 */
    private const val PER_ARTIST_TIMEOUT_MS = 20_000L

    private val lock = Mutex()

    /**
     * 上次检查时刻（**仅进程内**）。
     *
     * 不落盘是刻意的：落盘会让「换台设备 / 重装」后的第一次检查被上一次的时间挡住，
     * 而这一次检查正是用户最想要的。进程内节流已经挡住了「在同一页反复重组」
     * 这种真正的浪费。
     */
    @Volatile
    private var lastCheckAt = 0L

    /** 上一次检查得到的未读总数（节流命中时原样返回，界面不会误以为「都清零了」）。 */
    @Volatile
    private var lastKnownNew = 0

    /**
     * 检查结果。
     *
     * @param totalNew 未读新作总数（[throttled] 为 true 时是上一次的值）
     * @param checked 真的问到结果的作者数
     * @param failed 请求失败的作者数（站点抖一下就少算几个，不算错误）
     * @param throttled 是不是被节流挡掉了（没发请求）
     */
    data class Outcome(
        val totalNew: Int,
        val checked: Int,
        val failed: Int,
        val throttled: Boolean,
    )

    /**
     * 跑一轮检查。
     *
     * @param force 忽略 [THROTTLE_MS]（用户手动下拉刷新、以及后台周期任务用）
     */
    suspend fun check(force: Boolean = false): Outcome = lock.withLock {
        val mode = SettingsRepository.followUpdateAlert
        if (mode == MODE_OFF) {
            lastKnownNew = 0
            return@withLock Outcome(0, 0, 0, throttled = false)
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastCheckAt < THROTTLE_MS) {
            return@withLock Outcome(lastKnownNew, 0, 0, throttled = true)
        }

        val followed = FollowedArtistStore.all
        if (followed.isEmpty()) {
            lastCheckAt = now
            lastKnownNew = 0
            return@withLock Outcome(0, 0, 0, throttled = false)
        }

        // 提前把「看过哪些」抄出来：下面的协程里不能再读 FollowedArtistStore.all
        // （它每次调用都会反序列化整份 JSON），而且中途页面的 markSeen 会把表改掉，
        // 两边读到不同的快照会让这一轮结论自相矛盾。
        val seenByKey = followed.associate { it.key to it.seenCodes }

        val semaphore = Semaphore(CONCURRENCY)
        val fetched = coroutineScope {
            followed.map { item ->
                async(Dispatchers.IO) {
                    val key = item.key
                    if (key.isEmpty()) return@async key to null
                    val codes = semaphore.withPermit {
                        withTimeoutOrNull(PER_ARTIST_TIMEOUT_MS) {
                            runCatching { firstPageCodes(item.toArtistRef()) }.getOrNull()
                        }
                    }
                    key to codes
                }
            }.awaitAll()
        }

        val updates = mutableMapOf<String, FollowedArtistStore.UpdateResult>()
        var checked = 0
        var failed = 0
        fetched.forEach { (key, codes) ->
            if (key.isEmpty()) return@forEach
            // null = 请求失败 / 超时。**绝不当成「没有新作」**写回去 ——
            // 那会让一次网络抖动把红点清掉。
            if (codes == null) {
                failed++
                return@forEach
            }
            checked++
            val seen = seenByKey[key].orEmpty()
            updates[key] = if (seen.isEmpty()) {
                // 首次看到这个人：只补种，不报数。
                FollowedArtistStore.UpdateResult(newCount = 0, seedCodes = codes)
            } else {
                val seenSet = seen.toSet()
                FollowedArtistStore.UpdateResult(newCount = codes.count { it !in seenSet })
            }
        }

        if (updates.isNotEmpty()) {
            FollowedArtistStore.applyUpdateResults(updates)
        }
        lastCheckAt = System.currentTimeMillis()
        val totalNew = updates.values.sumOf { it.newCount }
        lastKnownNew = totalNew

        // 通知只在「明确选了手机通知」时发；软件内角标是订阅页自己画的，不需要发。
        if (totalNew > 0 && SettingsRepository.followUpdateAlert == MODE_NOTIFICATION) {
            val names = followed.filter { (updates[it.key]?.newCount ?: 0) > 0 }
                .map { it.name to (updates[it.key]?.newCount ?: 0) }
            FollowUpdateNotifier.notifyNewWorks(names)
        }

        Outcome(totalNew, checked, failed, throttled = false)
    }

    /**
     * 取某位作者页**第一页**的作品码。
     *
     * 失败 / 没有内容都返回空列表 —— 调用方靠「是不是 null」区分「失败」和「空」，
     * 所以这里不抛异常。
     */
    private suspend fun firstPageCodes(artist: ArtistRef): List<String> {
        val state = NetworkRepo.getArtistVideos(artist, page = 1)
            .first { it !is PageLoadingState.Loading }
        val success = state as? PageLoadingState.Success ?: return emptyList()
        return success.info.videos.map { it.videoCode }
    }
}
