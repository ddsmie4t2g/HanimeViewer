package io.github.daisukikaffuchino.han1meviewer.logic

import android.util.Base64
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxyAuthenticator
import io.github.daisukikaffuchino.han1meviewer.BuildConfig
import io.github.daisukikaffuchino.han1meviewer.UPSTREAM_GITHUB_REPO
import io.github.daisukikaffuchino.han1meviewer.upstreamReleasePageUrl
import io.github.daisukikaffuchino.utils.LogUtil
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.han1meviewer.logic.network.GitHubDns
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.Announcement
import io.github.daisukikaffuchino.utils.applicationContext
import io.github.daisukikaffuchino.utils.decodeFromStringByBase64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.TimeSource

@Serializable
data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val updateDescription: String,
    val forceUpdate: Boolean,
)

/**
 * 上游（[UPSTREAM_GITHUB_REPO]）的最新发布版本。
 *
 * ⚠️ **上游的包本应用装不上** —— 两个事实都是硬约束，别指望能「一键更新到上游」：
 *
 * 1. **签名不同**：实测上游发布包的签名证书 SHA-256 是
 *    `F2:8A:4E:14:…:CF:E7`，本 fork 的 mod 包是 `B3:DD:C8:6C:…:7A:63`。
 *    Android 对同包名（都是 `io.github.daisukikaffuchino.han1meviewer`）的覆盖安装
 *    强制校验签名，不一致会直接 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
 * 2. **版本号更低**：上游 26.3.2 的 `versionCode` 是 `260805`，而 mod 线已经到 `260925`，
 *    就算签名一致也会被判成降级（`INSTALL_FAILED_VERSION_DOWNGRADE`）。
 *
 * 所以上游版本只用于**告知**（「上游又出新版了，mod 该合了」），
 * 真正的更新走本仓库的 `update.json`（见 [AppUpdateCheckResult.updateInfo]）。
 */
data class UpstreamReleaseInfo(
    /** 上游 tag，形如 `26.3.2`。 */
    val version: String,
    /** 这一次检查里上游是否比「当前 mod 所基于的版本」更新。 */
    val isNewerThanInstalled: Boolean,
    /** GitHub 上这个 tag 的发布页。 */
    val releasePageUrl: String,
    /** 上游 Release 的正文（更新说明）。只有走 GitHub API 那条源才拿得到，可能为空。 */
    val changelog: String = "",
)

data class AppUpdateCheckResult(
    /** 本仓库（mod 线）有可安装的新版本时非空。 */
    val updateInfo: AppUpdateInfo? = null,
    val announcement: Announcement? = null,
    /** 上游最新版本；读不到就是 null。 */
    val upstream: UpstreamReleaseInfo? = null,
    /** 上游没读到时，失败原因（用于在界面上如实说明，而不是假装「已是最新」）。 */
    val upstreamError: String? = null,
)

sealed interface AppUpdateState {
    data object Checking : AppUpdateState
    data object NoUpdate : AppUpdateState
    data class Available(val info: AppUpdateInfo) : AppUpdateState
}

/** 一个更新源的应答：`versionCode` 用来比大小，`json` 是原样内容。 */
internal data class UpdateSourceAnswer(val versionCode: Int, val json: String)

/** 「等到第一个成功应答」的上限；一个都没答上来就退回缓存。 */
internal const val UPDATE_FIRST_ANSWER_BUDGET_MS = 6_000L

/** 已经确认「有更新」之后再多等这么久，看有没有源报出更高的版本。 */
internal const val UPDATE_SETTLE_MS = 1_200L

/** 判定「本构建已是最新」之前愿意等多久（防 jsDelivr 边缘的旧内容）。 */
internal const val UPDATE_NO_UPDATE_BUDGET_MS = 4_000L

/**
 * 一次源赛跑的结果。
 *
 * @param best 采用的那一份（null = 一条源都没答上来）
 * @param answered 有多少条源**答了话**（成功或失败都算）
 * @param dropped 到点时**还在路上、被主动放弃等待**的条数
 *
 *   ⚠️ 这**不是失败**，而是「已经拿到够用的答案，不等了」。
 *   26.9.2 曾经把这种源也记成 `更新源失败：…`，于是日志里永远有两条「失败」，
 *   看起来就像「那两个源又坏了」—— 这正是用户报「两个更新源又全都没用了」的来源。
 *   日志必须把「失败」与「不等了」分开写。
 * @param elapsedMillis 整个赛跑用了多久
 */
internal data class UpdateRaceOutcome(
    val best: UpdateSourceAnswer?,
    val answered: Int,
    val dropped: Int,
    val elapsedMillis: Long,
)

/**
 * **并发问所有更新源，但不等最慢的那一个。**
 *
 * ## 为什么不能 `awaitAll`
 *
 * 「取 versionCode 最大的那一份」这个规则要求**收到所有源的应答**，于是老实现是
 * `UPDATE_URLS.map { async { … } }.awaitAll()` —— 只要有一条源是**黑洞**
 * （TCP connect 不返回，只在 connectTimeout 时失败），整次检查就被它按在地上：
 * 实测（2026-09-15，中国移动）5 条源里有 **3 条**是这种，每次「检查更新」都要
 * 陪它们把 15 s 的连接超时走满。用户看到的就是「检测更新很久」。
 *
 * ## 现在的规则（三步）
 *
 * 1. **谁先答上来就用谁**：所有源同时开跑，谁先回谁的结果先记下（不再等所有源）；
 * 2. **已经知道「有更新」就再等 [settleMillis] 收一收**：可能还有源报更高的版本，
 *    等一小会儿取最大；再久就不值得了 —— 用户此刻要的就是「有新版本，能装」。
 *    没有更新时反而愿意等满 [noUpdateBudgetMillis]（这是唯一会被「旧缓存」坑到的方向：
 *    jsDelivr 边缘可能还缓存着旧内容，只信它的「已是最新」会漏掉刚发的版本）；
 * 3. **到点就掐**：决定之后立刻 `cancel()` 掉还在挂着的源 —— 挂死的那几条不再占着
 *    socket 和线程（这也要求用非阻塞的 [await] 而不是 `execute()`，否则取消掐不断）。
 *
 * 于是总耗时 ≈ 第一个应答（通常 0.3–1 s）+ 一小段结算时间，与「最慢的源」无关；
 * 一个源都没答上来时也只等 [firstBudgetMillis]（6 s）就退回缓存，而不是 15 s 起。
 *
 * @param sources 各条源的取值函数（内部自己解析出 `versionCode`），抛异常=这条源失败；
 *   返回 null = 「这条源答话了，但没有可用内容」（例如 GitHub 上最新的是预发布）——
 *   一样算应答，只是不参与比大小
 * @param isNewer 「这个版本号算不算比本机新」——用它决定还要不要继续等更高的版本
 */
internal suspend fun raceUpdateSources(
    sources: List<suspend () -> UpdateSourceAnswer?>,
    isNewer: (Int) -> Boolean,
    firstBudgetMillis: Long = UPDATE_FIRST_ANSWER_BUDGET_MS,
    settleMillis: Long = UPDATE_SETTLE_MS,
    noUpdateBudgetMillis: Long = UPDATE_NO_UPDATE_BUDGET_MS,
): UpdateRaceOutcome = coroutineScope {
    if (sources.isEmpty()) {
        return@coroutineScope UpdateRaceOutcome(best = null, answered = 0, dropped = 0, elapsedMillis = 0)
    }

    // 谁先回来谁先投递；容量无限，源不会因为没人收而卡住。
    val arrivals = Channel<Result<UpdateSourceAnswer?>>(Channel.UNLIMITED)
    val jobs = sources.map { source ->
        launch(Dispatchers.IO) { arrivals.send(runCatching { source() }) }
    }

    val outcome = try {
        val start = TimeSource.Monotonic.markNow()
        fun elapsed() = start.elapsedNow().inWholeMilliseconds

        var best: UpdateSourceAnswer? = null
        var answered = 0
        var deadline = firstBudgetMillis

        while (answered < sources.size) {
            val remaining = deadline - elapsed()
            if (remaining <= 0) break
            val arrival = withTimeoutOrNull(remaining) { arrivals.receive() } ?: break
            answered++
            // null = 这条源答了话但没内容（见 sources 的说明）：算应答，不参与比大小。
            val answer = arrival.getOrNull() ?: continue
            val currentBest = best
            if (currentBest == null || answer.versionCode > currentBest.versionCode) best = answer
            // ⭐ 已知「有更新」→ 只再等一小会儿取更高的；否则等满「没有更新」的预算，
            //    免得被 jsDelivr 边缘的旧内容骗成「已是最新」。
            deadline = minOf(
                deadline,
                if (isNewer(answer.versionCode)) elapsed() + settleMillis else noUpdateBudgetMillis,
            )
        }
        UpdateRaceOutcome(
            best = best,
            answered = answered,
            dropped = sources.size - answered,
            elapsedMillis = elapsed(),
        )
    } finally {
        // 决定之后立刻掐掉还在挂着的源（见函数注释第 3 条）。
        jobs.forEach { it.cancel() }
    }
    outcome
}

/**
 * 非阻塞地等这次 call 的响应，并且**取消时真的把请求掐掉**。
 *
 * ⚠️ 别改回 `execute()`：它是阻塞调用，协程取消**掐不断**它 —— 源赛跑里「到点就掐」
 * 就变成一句空话，挂死的源照样把线程和 socket 占满整个 connectTimeout。
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            // 取消与响应同时发生时，response 必须自己关掉，否则连接不会归还连接池。
            if (continuation.isCancelled) response.close() else continuation.resume(response)
        }
    })
}

@Serializable
private data class AppUpdatePayload(
    val versionName: String? = null,
    val versionCode: Int = 0,
    val downloadUrl: String? = null,
    val updateDescription: String = "",
    val forceUpdate: Boolean = false,
    val isShowAnnouncement: Boolean = false,
    val announcement: String = "",
)

/** jsDelivr 的包版本列表（`data.jsdelivr.com/v1/packages/gh/<owner>/<repo>`）。 */
@Serializable
private data class JsDelivrPackage(
    val versions: List<JsDelivrVersion> = emptyList(),
)

@Serializable
private data class JsDelivrVersion(val version: String = "")

/** GitHub Releases API 的 `releases/latest` 响应里我们关心的字段。 */
@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("prerelease") val prerelease: Boolean = false,
)

@OptIn(ExperimentalSerializationApi::class)
object AppUpdateChecker {
    private const val TAG = "AppUpdateChecker"

    /**
     * 本 mod 线的仓库。
     *
     * 只有 [fetchUpdateJsonFromTagList] 与 [releaseApkUrl] 用它 —— 正常路径的仓库地址
     * 藏在 [UPDATE_URLS] 的那些 base64 里（沿用原实现的写法）。
     */
    private const val MOD_GITHUB_REPO = "ddsmie4t2g/HanimeViewer"

    /**
     * 上游版本查询的**总预算**。
     *
     * 上游信息只是「告知」（它的包本应用装不上，见 [UpstreamReleaseInfo]），
     * 没有理由让手动检查弹窗陪着它等 —— 两条源都慢/都不通时到点就放弃，
     * 把原因写进 `upstreamError` 如实显示。
     */
    private const val UPSTREAM_BUDGET_MS = 4_000L

    /**
     * **本仓库**（mod 线）的更新信息源，沿用原实现的 base64 写法，指向仓库根目录的
     * `update.json`：
     *
     *     https://raw.githubusercontent.com/ddsmie4t2g/HanimeViewer/mod/update.json
     *
     * 发新版时只需要改这个 json 里的 versionName / versionCode / downloadUrl 即可。
     * 字段结构与 [AppUpdatePayload] 完全一致，解析逻辑无需改动。
     *
     * 注意分支是 `mod`：本仓库是 fork，`main` 上是另一条 0.19.x 线，
     * 这条 26.3.2-mod.x 线放在 `mod` 分支上，所以两个 URL 都锁 `mod`。
     *
     * ⚠️ **只有这份 json 带着 `versionCode`**，所以它是唯一能做「是否更新」判断、
     * 也是唯一能应用内下载安装的源。上游那条（[requestUpstreamLatest]）只能给出 tag。
     *
     * ## 为什么要这么多条（26.9.3 扩充）
     *
     * 这些源会**同时**被问（见 [raceUpdateSources]，不等最慢的那条），所以「多加一条
     * 在你这儿不通的源」几乎不花时间；而每一条都是**不同基础设施**上的同一份内容：
     * 任何一条能通，检查更新就成立。反过来，「只留 1–2 条」意味着那一条所处的
     * CDN 段一被墙，功能就整体失效 —— 用户报的「更新源又全都没用了」。
     *
     * | 源 | 基础设施 | 实测（2026-09-15，中国移动） |
     * |---|---|---|
     * | `cdn.jsdelivr.net` | jsDelivr / Fastly | 200 / 0.27 s（**钉 Fastly IP 后**）|
     * | `fastly.jsdelivr.net` | jsDelivr / Fastly | 200 / 0.27 s |
     * | `gcore.jsdelivr.net` | jsDelivr / Fastly | 200 / 0.37 s（**钉 Fastly IP 后**）|
     * | `testingcf.jsdelivr.net` | jsDelivr / Fastly | 200 / 0.33 s（**钉 Fastly IP 后**）|
     * | `jsdelivr.b-cdn.net` | jsDelivr / **Bunny** | 200 / 0.59–4.8 s（另一张 CDN，独立于 Fastly）|
     * | `ghproxy.net` | 第三方 GitHub 代理 | 200 / 0.95 s |
     * | `ddsmie4t2g.github.io` | **GitHub Pages** / Fastly | 200 / 0.50 s（**且不滞后**，见下）|
     * | `github.com/…/raw/…` | GitHub 本体（302 到 raw） | 本机 000（域名被墙），别的网络可能通 |
     * | `raw.githubusercontent.com` | GitHub / Fastly | 200 / 1.2–1.6 s（**钉 185.199.x 后**）|
     *
     * ⚠️ 第三方代理（`ghproxy.net`）只当**补充**：它拿到的是同一份 json，但内容由第三方
     * 转发，所以 [AppUpdatePayload.toAvailableUpdateOrNull] 仍然只信「网址合法 + 版本更大」，
     * 而安装包**始终**从 json 里给的那个地址下载 —— 签名不同会被系统拒装，装不上假包。
     *
     * ## ⭐ 27.0.4：为什么还要单独养一个 GitHub Pages 站点
     *
     * 上面的表看着源很多，但它们的分发链路其实只有**两类**，各有结构性毛病，
     * 而且都不是「多挂一个域名」能修的 —— 这才是加这条的理由
     * （站点侧的做法与理由见 `.github/workflows/pages.yml` 的文件头）：
     *
     * - **jsDelivr 那 7 条**：对 `gh/<owner>/<repo>@<branch>/<file>` 有 **12 小时的 s-maxage**，
     *   且缓存键**不含 query string**（拼 `?t=时间戳` 无效）。⇒ 发版后最长 12 小时客户端
     *   还拿到旧 json，表现就是「明明发了新版，检查更新却说不更新」。
     * - **GitHub 本体那 2 条**：`raw.githubusercontent.com` / `github.com` 在部分网络下
     *   被 DNS 投毒或直接不通（本机实测 `000`）。
     *
     * `ddsmie4t2g.github.io` 两类都不属于：**不经过 jsDelivr**（Pages 的 CDN TTL 是
     * 10 分钟量级，且每次部署刷新被改动的文件），域名也与 `raw.githubusercontent.com`
     * **不是一个**（同一段 Fastly anycast，但被墙/被投毒的范围常常不一样）。
     * 2026-09-17 本机直连实测：`185.199.108–111.153` 四个 IP 全部可达，
     * TLS 0.50 s / HTTP 200 —— 是**实测可用**的源，不是「多一条试试」。
     *
     * ⚠️ 这个地址与 `.github/workflows/pages.yml` 是**同一份约定**：
     * 改仓库名 / 改站点路径时必须两边一起改，否则这条源会**静默 404**
     * （赛跑里只表现为「这条源失败」，排查时很难往这儿想）。
     *
     * `internal`（而非 private）是给单测留的门：JVM 单测里 `android.util.Base64` 是
     * **Stub（一调就抛 `not mocked`）**，所以 [updateSourceUrls] 那条路在单测里跑不了，
     * 测试只能拿这份原始 base64 用 `java.util.Base64` 自己解 —— 见 [updateSourceUrls]。
     */
    internal val UPDATE_URLS = listOf(
        // ⭐ 27.0.4：GitHub Pages（仓库 mod 分支的 update.json 由 Actions 发布过去）。
        // 放在最前面只是给读代码的人一个「哪条最新鲜」的提示，赛跑本身与顺序无关。
        "aHR0cHM6Ly9kZHNtaWU0dDJnLmdpdGh1Yi5pby9IYW5pbWVWaWV3ZXIvdXBkYXRlLmpzb24=",
        // jsDelivr（GitHub 内容加速，国内一般可直连）
        "aHR0cHM6Ly9jZG4uanNkZWxpdnIubmV0L2doL2Rkc21pZTR0MmcvSGFuaW1lVmlld2VyQG1vZC91cGRhdGUuanNvbg==",
        // GitHub raw（直连，可能需要代理）
        "aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL2Rkc21pZTR0MmcvSGFuaW1lVmlld2VyL21vZC91cGRhdGUuanNvbg==",
        // ⭐ 26.8：jsDelivr 的**其它 CDN 段**。同一份内容、不同 anycast 段 ——
        // 某一段被墙/被投毒时还有别的能走（这三个域名都已进 [GitHubDns] 的内置 IP 表）。
        "aHR0cHM6Ly9mYXN0bHkuanNkZWxpdnIubmV0L2doL2Rkc21pZTR0MmcvSGFuaW1lVmlld2VyQG1vZC91cGRhdGUuanNvbg==",
        "aHR0cHM6Ly9nY29yZS5qc2RlbGl2ci5uZXQvZ2gvZGRzbWllNHQyZy9IYW5pbWVWaWV3ZXJAbW9kL3VwZGF0ZS5qc29u",
        "aHR0cHM6Ly90ZXN0aW5nY2YuanNkZWxpdnIubmV0L2doL2Rkc21pZTR0MmcvSGFuaW1lVmlld2VyQG1vZC91cGRhdGUuanNvbg==",
        // ⭐ 26.9.3：jsDelivr 的 **Bunny CDN** 入口（官方文档里的备用域名）。
        // 它与上面四个走的**不是同一张 CDN**（实测 IP 109.61.83.243），
        // 所以在「Fastly 段整体不通」的网络里它是唯一还能用的 jsDelivr 入口。
        "aHR0cHM6Ly9qc2RlbGl2ci5iLWNkbi5uZXQvZ2gvZGRzbWllNHQyZy9IYW5pbWVWaWV3ZXJAbW9kL3VwZGF0ZS5qc29u",
        // ⭐ 26.9.3：GitHub 本体入口（github.com 会 302 到 raw）。
        // 它和 raw 不是一个**域名**，被墙/被投毒的范围常常不一样，多一条不亏。
        "aHR0cHM6Ly9naXRodWIuY29tL2Rkc21pZTR0MmcvSGFuaW1lVmlld2VyL3Jhdy9tb2QvdXBkYXRlLmpzb24=",
        // ⭐ 26.9.3：第三方 GitHub 代理（实测国内可直连，0.95 s）。
        // 只当补充源：它是**别人**的服务器，所以排在自建/官方之后，且不参与任何写操作。
        "aHR0cHM6Ly9naHByb3h5Lm5ldC9odHRwczovL3Jhdy5naXRodWJ1c2VyY29udGVudC5jb20vZGRzbWllNHQyZy9IYW5pbWVWaWV3ZXIvbW9kL3VwZGF0ZS5qc29u",
    )

    /**
     * 把 [UPDATE_URLS] 里的 base64 解开成真正的地址（走生产的 `android.util.Base64`）。
     *
     * 做成函数而不是在每个调用点就地 `map { decode }`，是为了让「解码」只有一处，
     * 免得某天只改了一处解码的 flag/编码。
     *
     * ⚠️ **单测不要调这个**：JVM 单测里 `android.util.Base64` 是 **Stub**
     * （一调就抛 `RuntimeException: Method decode in android.util.Base64 not mocked`）。
     * 单测请拿 [UPDATE_URLS] 自己用 `java.util.Base64` 解 —— 两边对同一份标准
     * 无换行 base64 的结果是逐字节相同的，所以「地址写错了」这个风险照样能被钉住。
     */
    internal fun updateSourceUrls(): List<String> =
        UPDATE_URLS.map { it.decodeFromStringByBase64(Base64.NO_WRAP) }

    /** 原实现用于腾讯云 COS 防盗链；对 raw.githubusercontent 无影响，保留以免动到请求结构。 */
    private const val ENCODED_UPDATE_REFERER = "aG5tdmlld2VydXAuY29t"

    /**
     * 当前安装在设备上的版本号。
     *
     * ⚠️ **不要**再在这里写死一个常量。历史上这里写的是 `260914`，而
     * `app/build.gradle.kts` 的 `versionCode` 是 `260915`，两边一旦不同步，
     * `it.versionCode > currentVersionCode` 就永远不成立 —— 表现就是
     * **发版之后「检查更新」一直不推送，明明仓库里的 update.json 已经是最新的**。
     *
     * `BuildConfig.VERSION_CODE` 由 `build.gradle.kts` 的 `defaultConfig.versionCode`
     * 生成（见 `buildConfigField("int", "VERSION_CODE", ...)`），是同一份数据源，
     * 天然不会漂移。
     */
    private val currentVersionCode: Int
        get() = BuildConfig.VERSION_CODE

    /**
     * 当前版本所基于的「上游版本」三段数字。
     *
     * 26.6.1 起 `versionName` 是干净的三段号（`26.6.1`），**不再**编码上游基准，
     * 所以这里读的是 `BuildConfig.UPSTREAM_BASE_VERSION`（由 `app/build.gradle.kts`
     * 生成，本仓库当前为 `26.3.2`）。
     *
     * 上游的 tag 是裸的 `26.3.2`，两边取到的都是 `[26, 3, 2]`，于是「上游有没有出更新的
     * 版本」就退化成**同一个基准上的比较**。
     *
     * ⚠️ 别再退回去解析 `VERSION_NAME`：那样会把本仓库自己的 `26.6.1` 当成上游基准，
     * 于是「上游最新 26.3.2」永远显得比本构建旧，上游更新就再也提示不出来了。
     */
    private val localBaseVersion: List<Int>?
        get() = baseVersionParts(BuildConfig.UPSTREAM_BASE_VERSION)

    /**
     * 本构建**所基于的上游版本**（形如 `26.3.2`），取不到就是 null。
     *
     * 界面用它把关系说清楚：「上游最新 26.3.2 / 本构建基于上游 26.3.2」——
     * 否则用户看到「上游有新版本」却找不到更新按钮，会以为功能坏了。
     */
    val installedBaseVersion: String?
        get() = VERSION_TRIPLE.find(BuildConfig.UPSTREAM_BASE_VERSION)?.value

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /**
     * 检查更新用的 client。
     *
     * ⚠️ 必须挂 [HProxySelector]：更新源里的 `raw.githubusercontent.com` 在部分网络下
     * 直连不通（jsDelivr 那条一般能直连，所以「检查更新」看起来还能用），
     * 挂上代理后用户配的代理对两条源都生效，和 [AppUpdateDownloader] 保持一致。
     *
     * ⚠️ 同时必须挂 [GitHubDns]：`raw.githubusercontent.com` / `api.github.com` 常被 DNS 投毒，
     * 系统解析出来的 IP 直接连不上；线程池里的解析结果全是死 IP 时，
     * 回退源等于形同虚设。见 [GitHubDns] 的说明。
     * （`data.jsdelivr.com` 不在内置表里，会正常走系统 DNS。）
     *
     * `readTimeout` 从 15 s 放宽到 20 s：这个值约束的是「两次数据到达之间的最大间隔」，
     * 弱网下 15 s 太紧，会把「只是慢」误判成「失败」而白白切到更差的源。
     *
     * ⭐ `connectTimeout` 反过来收到 **5 s**：这几条源都是「连得上就很快（实测 0.07–0.32 s）、
     * 连不上就是黑洞（connect 永远不返回）」的 CDN，15 s 只是让黑洞源多耗 10 s。
     * 真正的兜底不是超时长短，而是 [raceUpdateSources]「到点就掐、不等最慢的源」。
     */
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .dns(GitHubDns)
            .proxySelector(HProxySelector())
            .proxyAuthenticator(HProxyAuthenticator.http)
            .build()
    }

    /**
     * 查一次上游版本用的 client。
     *
     * 单独一个是为了**不挂 [GitHubDns]**：主源是 `data.jsdelivr.com`，
     * 它跟 GitHub 完全不是一个 CDN，套上 GitHub 的钉 IP 表反而会把它打歪。
     */
    private val upstreamClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            // ⭐ 26.8 起也挂 [GitHubDns]：它现在同时覆盖 data.jsdelivr.com 与 pi.github.com
            // （都带系统 DNS 兜底尾巴），所以「套上 GitHub 的钉 IP 表会把 jsDelivr 打歪」
            // 这个顾虑已经不成立，而收益是上游查询不再吃 DNS 投毒。
            .dns(GitHubDns)
            .proxySelector(HProxySelector())
            .proxyAuthenticator(HProxyAuthenticator.http)
            .build()
    }

    /**
     * 检查更新。
     *
     * 一次检查会同时问两个地方，因为它们的用途不同：
     * - **本仓库的 `update.json`** → [AppUpdateCheckResult.updateInfo]，能装的那个；
     * - **上游的 Release** → [AppUpdateCheckResult.upstream]，只用于告知（装不上，见
     *   [UpstreamReleaseInfo] 的说明）。
     *
     * 两边互相独立：一个挂了不影响另一个。
     *
     * @param includeUpstream 要不要顺带查上游版本。
     *   ⭐ **首页那条路请传 false**：首页只消费 `updateInfo` 与 `announcement`
     *   （见 `HomePageViewModel.initializeHomePage`），上游那条支线它根本不看 ——
     *   带着它就等于每次开首页都白等一个额外请求（弱网下还是那句「检查更新很慢」）。
     *   只有「关于」页那个手动检查弹窗需要上游信息。
     */
    suspend fun checkForUpdate(includeUpstream: Boolean = true): AppUpdateCheckResult =
        withContext(Dispatchers.IO) {
            coroutineScope {
                // 两条源**并发**跑，而不是串行：慢的那条不该拖住快的那条。
                val modDeferred = async { fetchModResult() }
                val upstreamDeferred = if (includeUpstream) {
                    async { runCatching { requestUpstreamLatest() } }
                } else {
                    null
                }

                val modResult = modDeferred.await()
                val upstreamOutcome = upstreamDeferred?.await()

                var upstream: UpstreamReleaseInfo? = null
                var upstreamError: String? = null
                upstreamOutcome
                    ?.onSuccess { fetched ->
                        if (fetched != null) {
                            upstream = fetched
                            LogUtil.d(
                                TAG,
                                "上游最新版本 ${fetched.version}（比当前基准新=${fetched.isNewerThanInstalled}）"
                            )
                        }
                    }
                    ?.onFailure {
                        upstreamError = it.message ?: it.javaClass.simpleName
                        LogUtil.e(TAG, "查上游版本失败", it)
                    }

                AppUpdateCheckResult(
                    updateInfo = modResult.updateInfo,
                    announcement = modResult.announcement,
                    upstream = upstream,
                    upstreamError = upstreamError,
                )
            }
        }

    /**
     * 读本仓库的 `update.json`（读不到就退回上次缓存），只负责回答
     * 「本构建有没有可安装的新版本」。
     *
     * 网络这边**不再抛异常**：一个源都没答上来时 [requestUpdateJson] 返回 null，
     * 直接走缓存；缓存也没有就返回空结果（界面显示「已是最新」—— 这是原有的兜底语义）。
     */
    private suspend fun fetchModResult(): AppUpdateCheckResult {
        val cachedJson = SettingsRepository.current.cachedUpdateJson
        val responseJson = runCatching { requestUpdateJson() }
            .onFailure { LogUtil.e(TAG, "检查本仓库更新失败", it) }
            .getOrNull()

        if (responseJson != null) {
            SettingsRepository.setCachedUpdateJson(responseJson)
        } else {
            if (cachedJson == null) LogUtil.w(TAG, "所有更新源都没答上来，且没有缓存可用")
            else LogUtil.d(TAG, "复用上次缓存的 update.json")
        }
        val jsonToUse = responseJson ?: cachedJson
        return jsonToUse.toUpdateCheckResult()
    }

    suspend fun ignoreUpdate(versionCode: Int) = SettingsRepository.setIgnoredVersionCode(versionCode)

    //<editor-fold desc="本仓库 update.json">

    /**
     * 取 `update.json`：**并发问所有源，取 `versionCode` 最大的那一份**，
     * 但**不等最慢的那一个**（赛跑的规则与理由见 [raceUpdateSources]）。
     *
     * ⚠️ 这里以前是「第一个成功就返回」（漏掉更新），后来改成 `awaitAll`（每次都要陪
     * 挂死的源等满 connectTimeout ⇒ 「检测更新很久」）。现在两者都避开了：
     * 谁先答上来先记下、有一小段结算时间取更高版本、到点就把还在挂的源掐掉。
     *
     * 一个 json 源都没答上来时，先记一条**说得清楚**的日志（应答几条、几条是「不等了」），
     * 再退到两条**元数据兜底**（[fetchReleaseFromTagList] / [fetchReleaseFromGitHubLatest]，
     * 它们不依赖 raw 文件分发）；都拿不到才返回 null 走缓存。
     */
    private suspend fun requestUpdateJson(): String? {
        val referer = ENCODED_UPDATE_REFERER.decodeFromStringByBase64(Base64.NO_WRAP)
        val outcome = raceUpdateSources(
            sources = updateSourceUrls().map { url -> suspend { fetchUpdateSource(url, referer) } },
            isNewer = { it > currentVersionCode },
        )
        LogUtil.d(
            TAG,
            "更新源赛跑：应答 ${outcome.answered}/${UPDATE_URLS.size} 条、" +
                "${outcome.dropped} 条**不等了**（已拿到答案后主动放弃，不是失败）、" +
                "用时 ${outcome.elapsedMillis} ms" +
                (outcome.best?.let { "，采用 versionCode=${it.versionCode}" } ?: "，无可用应答"),
        )
        outcome.best?.let { return it.json }

        // 8 条源拿的都是**同一个 json 文件**（只是不同 CDN 转发），所以要有一条
        // 「不依赖 raw 文件分发」的路。两条元数据源同样**用赛跑**（不等最慢的）：
        // jsDelivr 的包信息接口（tag 列表）与 GitHub 的 `releases/latest`。
        val fallback = raceUpdateSources(
            sources = listOf(
                suspend { fetchReleaseFromTagList() },
                suspend { fetchReleaseFromGitHubLatest() },
            ),
            isNewer = { it > currentVersionCode },
        )
        LogUtil.w(
            TAG,
            "所有 json 源都没答上来 ⇒ 元数据兜底：" +
                (fallback.best?.let { "取到 versionCode=${it.versionCode}" } ?: "也没答上来"),
        )
        return fallback.best?.json
    }

    /**
     * 问一条更新源：拿到 json 并解析出 `versionCode`（解析不出来 = 这条源失败）。
     *
     * ⚠️ **取消（[CancellationException]）不算失败**：那是赛跑到点后主动把我们掐了
     * （「已经拿到答案，不等了」）。26.9.2 把这种也算成失败记了日志，于是每一次检查
     * 日志里都固定有两条「更新源失败：…」，看起来就像那两个源坏了 —— 用户报的
     * 「两个更新源又全都没用了」就是被这条日志误导的。
     */
    private suspend fun fetchUpdateSource(url: String, referer: String): UpdateSourceAnswer {
        val startedAt = TimeSource.Monotonic.markNow()
        try {
            val request = Request.Builder()
                .url(url)
                .header("Referer", referer)
                .get()
                .build()
            return client.newCall(request).await().use { response ->
                check(response.isSuccessful) { "Update check failed with HTTP ${response.code}" }
                val json = response.body.string()
                val code = jsonParser.decodeFromString<AppUpdatePayload>(json).versionCode
                LogUtil.d(
                    TAG,
                    "更新源 $url → versionCode=$code（${startedAt.elapsedNow().inWholeMilliseconds} ms）",
                )
                UpdateSourceAnswer(code, json)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LogUtil.e(
                TAG,
                "更新源失败：$url（${startedAt.elapsedNow().inWholeMilliseconds} ms）",
                e,
            )
            throw e
        }
    }

    /**
     * 兜底 A：jsDelivr 的**包信息接口**（tag 列表）。它给得出 tag（`26.9.3`），
     * 但**不给** update.json 的内容，所以下载地址按仓库既定命名拼、说明留空。
     *
     * ⚠️ 实测该接口的元数据**会滞后**（2026-09-15 查时最多只到 26.9.0，而 26.9.2 已发布），
     * 所以它只是兜底：滞后只会「暂时看不到最新版」，不会报出比实际更新的版本
     * （版本号仍然要**大于本机**才会提示）。
     */
    private suspend fun fetchReleaseFromTagList(): UpdateSourceAnswer? {
        val url = "https://data.jsdelivr.com/v1/packages/gh/$MOD_GITHUB_REPO"
        val body = client.newCall(Request.Builder().url(url).get().build())
            .await().use { response ->
                check(response.isSuccessful) { "jsDelivr HTTP ${response.code}" }
                response.body.string()
            }
        val pkg = jsonParser.decodeFromString<JsDelivrPackage>(body)
        val newest = pkg.versions
            .map { it.version.trim().removePrefix("v") }
            .mapNotNull { name -> versionCodeOf(name)?.let { code -> code to name } }
            .maxByOrNull { it.first }
            ?: return null
        val (code, versionName) = newest
        LogUtil.d(TAG, "tag 列表兜底：最新 tag=$versionName（$code）")
        return UpdateSourceAnswer(code, synthesizedUpdateJson(versionName, code))
    }

    /**
     * 兜底 B：GitHub 的 `releases/latest` —— **权威、不滞后**，但需要 api.github.com 能通
     * （本机直连不通，配了代理/DNS 兜底的网络可以）。
     *
     * 刻意**不带** Release 正文当更新说明：那是 `release.yml` 自动拼的（含安装提示、
     * 签名指纹、commit 列表），拿来当「更新内容」是噪音。说明缺失就让它缺着。
     */
    private suspend fun fetchReleaseFromGitHubLatest(): UpdateSourceAnswer? {
        val url = "https://api.github.com/repos/$MOD_GITHUB_REPO/releases/latest"
        val body = client.newCall(Request.Builder().url(url).get().build())
            .await().use { response ->
                check(response.isSuccessful) { "GitHub API HTTP ${response.code}" }
                response.body.string()
            }
        val release = jsonParser.decodeFromString<GitHubRelease>(body)
        // 预发布不当正式更新（客户端走 releases/latest 本来也拿不到预发布）
        if (release.prerelease) return null
        val versionName = release.tagName.trim().removePrefix("v")
        val code = versionCodeOf(versionName) ?: return null
        LogUtil.d(TAG, "GitHub releases/latest 兜底：tag=$versionName（$code）")
        return UpdateSourceAnswer(code, synthesizedUpdateJson(versionName, code))
    }

    /**
     * 由「版本名」合成一份最小 `update.json`。
     *
     * 只填 versionName / versionCode / downloadUrl 三项：更新说明、公告、是否强制更新
     * 在兜底路径上**确实不知道**，那就留空 —— 界面少显示一行，比编一段内容好。
     *
     * 做成 internal 只是为了能被单测盯一眼「合成出来的 json 至少字段是对的」
     * （这条兜底路平时跑不到，真跑起来时不能再出错）。
     */
    internal fun synthesizedUpdateJson(versionName: String, versionCode: Int): String =
        jsonParser.encodeToString(
            AppUpdatePayload(
                versionName = versionName,
                versionCode = versionCode,
                downloadUrl = releaseApkUrl(versionName),
            )
        )

    /**
     * 按仓库**既定命名**拼 release 里 APK 的地址（供两条兜底路径用）：
     *
     *     releases/download/v<版本>/Han1meViewer-v<版本>.apk
     *
     * 这个命名不是猜的：`release.yml` 就是**从 APK 文件名反推 tag** 的，两边必须一致
     * 才发得出去；2026-09-15 也用 API 核对过 v26.9.2 的资产地址与此完全一致。
     */
    private fun releaseApkUrl(versionName: String): String =
        "https://github.com/$MOD_GITHUB_REPO/releases/download/v$versionName/" +
            "Han1meViewer-v$versionName.apk"

    private fun String?.toUpdateCheckResult(): AppUpdateCheckResult {
        if (this.isNullOrBlank()) return AppUpdateCheckResult()
        return runCatching {
            val payload = jsonParser.decodeFromString<AppUpdatePayload>(this)
            AppUpdateCheckResult(
                updateInfo = payload.toAvailableUpdateOrNull(),
                announcement = payload.toAnnouncementOrNull(),
            )
        }.onFailure {
            LogUtil.e(TAG, "Invalid update JSON", it)
        }.getOrDefault(AppUpdateCheckResult())
    }

    private fun AppUpdatePayload.toAvailableUpdateOrNull(): AppUpdateInfo? {
        val versionName = versionName?.trim().orEmpty()
        val downloadUrl = downloadUrl?.trim().orEmpty()
        if (versionName.isBlank() || versionCode <= 0 || downloadUrl.isBlank()) return null
        if (downloadUrl.toHttpUrlOrNull() == null) {
            LogUtil.e(TAG, "downloadUrl is invalid")
            return null
        }

        val ignoredVersionCode = SettingsRepository.current.ignoredVersionCode
        return AppUpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            downloadUrl = downloadUrl,
            updateDescription = updateDescription,
            forceUpdate = forceUpdate,
        ).takeIf {
            it.versionCode > currentVersionCode &&
                (it.forceUpdate || it.versionCode != ignoredVersionCode)
        }
    }

    private fun AppUpdatePayload.toAnnouncementOrNull(): Announcement? {
        val content = announcement.trim()
        if (!isShowAnnouncement || content.isBlank()) return null
        return Announcement(
            title = applicationContext.getString(R.string.update_announcement_title),
            content = content,
            isActive = true,
        )
    }

    //</editor-fold>

    //<editor-fold desc="上游版本">

    /**
     * 上游 [UPSTREAM_GITHUB_REPO] 的最新发布版本。
     *
     * ## 为什么要换源
     *
     * 原来「检查更新」只认仓库里的 `update.json`，而上游的版本信息**只能**从 GitHub 拿
     * （`api.github.com` / `github.com`）。实测在**不加代理的国内网络**下这两个域名
     * 是直接不通的（`000`），于是「检查更新」永远拿不到上游新版本 ——
     * 这不是逻辑错，是这条路根本走不出去。
     *
     * 而 `data.jsdelivr.com` 是同一条链路上**唯一实测可直连**的（`200`）。
     * jsDelivr 本来就是 GitHub 的内容 CDN，它的包信息接口会按语义化版本倒序列出
     * 仓库的全部 tag，正好就是我们要的「上游最新版本」。
     *
     * 拿到 tag 就能拼出发布页；`tag → 26.4.0` 这种直接可读，也不需要额外维护一份 json。
     *
     * 顺序：jsDelivr（可直连）→ GitHub API（需要代理/DNS 兜底，但能多拿到更新说明）。
     *
     * ⚠️ 整条链有**总预算** [UPSTREAM_BUDGET_MS]：上游信息只是「告知」（装不上），
     * 不值得让手动检查弹窗陪着它等 —— 超时就把原因放进 `upstreamError`，弹窗里如实显示。
     */
    private suspend fun requestUpstreamLatest(): UpstreamReleaseInfo? {
        // runCatching 包一层，好把「超时」与「源报错」分开：超时时 withTimeoutOrNull 返回 null，
        // 而包成 Result 之后内部无论如何都不会返回 null。
        val outcome = withTimeoutOrNull(UPSTREAM_BUDGET_MS) {
            runCatching { fetchUpstreamLatest() }
        }
        // 超时：内层两条请求都已取消（它们走的是可取消的 await），这里给出明确的失败原因。
        return outcome?.getOrThrow()
            ?: throw IllegalStateException("上游版本查询超时（${UPSTREAM_BUDGET_MS / 1000} 秒）")
    }

    private suspend fun fetchUpstreamLatest(): UpstreamReleaseInfo? {
        var lastError: Throwable? = null

        runCatching { requestUpstreamFromJsDelivr() }
            .onSuccess { if (it != null) return it }
            .onFailure {
                lastError = it
                LogUtil.w(TAG, "jsDelivr 查上游版本失败，回退 GitHub API：${it.message}")
            }

        runCatching { requestUpstreamFromGitHubApi() }
            .onSuccess { if (it != null) return it }
            .onFailure {
                lastError = it
                LogUtil.w(TAG, "GitHub API 查上游版本也失败：${it.message}")
            }

        // 两个源都失败时，把原因抛出去（调用方会放进 upstreamError，界面上如实显示）
        lastError?.let { throw it }
        return null
    }

    private suspend fun requestUpstreamFromJsDelivr(): UpstreamReleaseInfo? {
        val url = "https://data.jsdelivr.com/v1/packages/gh/$UPSTREAM_GITHUB_REPO"
        val body = upstreamClient.newCall(Request.Builder().url(url).get().build())
            .await().use { response ->
                check(response.isSuccessful) { "jsDelivr HTTP ${response.code}" }
                response.body.string()
            }
        val pkg = jsonParser.decodeFromString<JsDelivrPackage>(body)
        val tag = pkg.versions
            .map { it.version.trim() }
            .filter { baseVersionParts(it) != null }
            .maxWithOrNull { a, b -> compareBaseVersions(baseVersionParts(a)!!, baseVersionParts(b)!!) }
            ?: throw IllegalStateException("jsDelivr 里没有可识别的版本 tag")
        LogUtil.d(TAG, "jsDelivr 上游最新 tag：$tag")
        return tag.toUpstreamReleaseInfo(changelog = "")
    }

    private suspend fun requestUpstreamFromGitHubApi(): UpstreamReleaseInfo? {
        val url = "https://api.github.com/repos/$UPSTREAM_GITHUB_REPO/releases/latest"
        val body = client.newCall(Request.Builder().url(url).get().build())
            .await().use { response ->
                check(response.isSuccessful) { "GitHub API HTTP ${response.code}" }
                response.body.string()
            }
        val release = jsonParser.decodeFromString<GitHubRelease>(body)
        val tag = release.tagName.trim()
        if (tag.isBlank()) throw IllegalStateException("releases/latest 里没有 tag_name")
        LogUtil.d(TAG, "GitHub API 上游最新 tag：$tag")
        return tag.toUpstreamReleaseInfo(changelog = release.body.trim())
    }

    private fun String.toUpstreamReleaseInfo(changelog: String): UpstreamReleaseInfo {
        val upstreamParts = baseVersionParts(this)
        val localParts = localBaseVersion
        return UpstreamReleaseInfo(
            version = this,
            isNewerThanInstalled = upstreamParts != null && localParts != null &&
                compareBaseVersions(upstreamParts, localParts) > 0,
            releasePageUrl = upstreamReleasePageUrl(this),
            changelog = changelog,
        )
    }

    //</editor-fold>

    //<editor-fold desc="版本号工具">

    /**
     * 从 `26.3.2-mod.7.0` / `26.3.2` / `v26.9.3` 里抠出前三段数字 `[26, 3, 2]`。
     *
     * 只认「三段数字」这一种形式：上游的预览 tag（`26.3.0p`）也有 `26.3.0`，
     * 会被当成 26.3.0 参与比较 —— 即使如此也只会算出「不比 26.3.2 新」，不会误报。
     */
    private fun baseVersionParts(raw: String): List<Int>? {
        val match = VERSION_TRIPLE.find(raw) ?: return null
        val parts = match.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
        return parts.takeIf { it.size == 3 }
    }

    /**
     * `26.9.3` → `26009003`，与 `app/build.gradle.kts` 的口径一致：
     *
     *     versionCode = major * 1_000_000 + minor * 1_000 + patch
     *
     * ⚠️ **只有 tag 列表兜底这条路需要它**（[fetchUpdateJsonFromTagList] 只能拿到 tag）。
     * 正常路径的 `versionCode` 是 `update.json` 里写好的，永远以 json 为准 ——
     * 别反过来从 `versionName` 推算，那样两边一旦不同步就会变成「永远检测不到更新」。
     */
    internal fun versionCodeOf(versionName: String): Int? {
        val parts = baseVersionParts(versionName) ?: return null
        return parts[0] * 1_000_000 + parts[1] * 1_000 + parts[2]
    }

    /** 三段数字的字典序比较。 */
    private fun compareBaseVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return if (diff > 0) 1 else -1
        }
        return 0
    }

    /** ⚠️ 正则里**不能**出现裸 `{` / `}` —— Android 的 ICU 引擎会判它语法错误，见工程约定。 */
    private val VERSION_TRIPLE = Regex("""(\d+)\.(\d+)\.(\d+)""")

    //</editor-fold>
}
