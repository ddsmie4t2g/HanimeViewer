package io.github.daisukikaffuchino.han1meviewer.logic.hsex

import io.github.daisukikaffuchino.han1meviewer.HanimeLink
import io.github.daisukikaffuchino.han1meviewer.ResolutionLinkMap
import io.github.daisukikaffuchino.han1meviewer.logic.exception.ParseException
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimePreview
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeVideo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HomePage
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.VideoLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import kotlinx.datetime.LocalDate
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 好色TV（hsex.tv）页面解析 —— mod 26.5 新增的第三个数据源。
 *
 * 产物**刻意复用 hanime 的模型**（[HomePage] / [HanimeInfo] / [HanimeVideo]），
 * 于是 ViewModel 与整个 Compose UI 一行都不用改，只在
 * [io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo] 里按数据源分流。
 *
 * ## 列表页卡片（2026-09 实测）
 *
 * ```html
 * <div class="thumbnail">
 *   <a target="_self" href="video-1240261.htm">
 *     <div class="image" style="background-image: url('https://i.hdcdn.online/thumb/1240261.webp')"
 *          title="0312 三穴干完未尽兴，继续寻找极限">
 *       <div class="marker-overlays">
 *         <var class="duration">00:36</var><span class="hd-thumbnail">HD</span>
 *       </div>
 *     </div>
 *   </a>
 *   <div class="caption title"><h5><a href="video-1240261.htm">0312 三穴干完…</a></h5></div>
 *   <div class="info"><p><a href="user.htm?author=zhimakaimen_">zhimakaimen_</a><br/>21.2k次观看</p></div>
 * </div>
 * ```
 *
 * ⚠️ 封面**不是 `<img>`**，而是 `div.image` 的 CSS `background-image`。
 * 别去 `selectFirst("img")` —— 那样拿到的会是站点 logo。这与 nJAV / hanime
 * 两套解析器的写法都不同，是这个地方最容易照抄出错的一点。
 *
 * ## 详情页
 *
 * 字段全在 `og:` 系列 meta 里（比 nJAV 还整齐），播放地址是一个**静态标签**：
 *
 * ```html
 * <source id="video-source" src="https://cdn.hdcdn.online/…/hls/1240261/index.m3u8"
 *         type="application/x-mpegURL" />
 * ```
 */
object HsexParser {

    //<editor-fold desc="列表">

    /** 首页要抓的栏目：[键] 对应用 [homePage] 填入 [HomePage] 的哪个槽位。 */
    const val SEC_LATEST = "latest"
    const val SEC_TOP = "top"
    const val SEC_TOP7 = "top7"
    const val SEC_LONG = "long"
    const val SEC_5MIN = "five_min"

    /**
     * 首页栏目 → hsex 路径（已全部实测可达，每页 24 条）。
     *
     * ⚠️ 站方首页自己链接的「最热」（`hot_list-1.htm`）**已经 404**，
     * 所以这里不采纳它，改用 `top_list`（排行榜）。别照着它首页上的链接抄。
     */
    val HOME_SECTIONS: List<Pair<String, String>> = listOf(
        SEC_LATEST to "list",
        SEC_TOP to "top_list",
        SEC_TOP7 to "top7_list",
        SEC_LONG to "long_list",
        SEC_5MIN to "5min_list",
    )

    /**
     * 首页「更多」按钮带下来的检索标记 → hsex 路径。
     *
     * 标记由 [io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.buildCategoryList]
     * 在 hsex 数据源下打出（见那个函数里的 `isHsexSite` 分支）。
     */
    private val MARKER_TO_PATH = mapOf(
        "全部" to "list",
        "最新" to "list",
        "排行榜" to "top_list",
        "七日排行" to "top7_list",
        "長片" to "long_list",
        "5分鐘" to "5min_list",
    )

    fun pathForMarker(marker: String?): String? =
        marker?.trim()?.takeIf { it.isNotEmpty() }?.let { MARKER_TO_PATH[it] }

    /** 解析列表页（首页 / 分类页 / 搜索页共用同一套卡片结构）。 */
    fun videoList(body: String): MutableList<HanimeInfo> {
        val doc = Jsoup.parse(body)
        val result = LinkedHashMap<String, HanimeInfo>()
        doc.select("div.thumbnail").forEach { card ->
            parseCard(card)?.let { result.putIfAbsent(it.videoCode, it) }
        }
        return result.values.toMutableList()
    }

    fun pageState(body: String, page: Int): PageLoadingState<MutableList<HanimeInfo>> {
        val list = videoList(body)
        return when {
            list.isNotEmpty() -> PageLoadingState.Success(list)
            hasNextPage(body, page) -> PageLoadingState.Success(list)
            else -> PageLoadingState.NoMoreData
        }
    }

    /**
     * 列表页是否还有下一页。
     *
     * 站点**没有** `rel="next"`，只有一排 `.page-link` 数字（`1` … `10`，再往后是省略号）。
     * 所以判据是「出现了比当前页更大的页码」—— 拿「有没有 page-link」当判据会永远为真，
     * 于是翻到最后一页之后仍会无限请求下一批空页。
     */
    fun hasNextPage(body: String, page: Int): Boolean =
        PAGE_LINK.findAll(body).any { (it.groupValues[1].toIntOrNull() ?: 0) > page }

    private val PAGE_LINK = Regex("""class="page-link"[^>]*>\s*(\d+)\s*<""")

    private fun parseCard(card: Element): HanimeInfo? {
        val id = card.select("a[href]")
            .asSequence()
            .mapNotNull { HsexNetwork.videoIdFrom(it.attr("href")) }
            .firstOrNull()
            ?: return null

        val imageDiv = card.selectFirst("div.image")

        // 封面在 CSS background-image 里，不是 <img>；见类注释。
        val cover = imageDiv?.attr("style").orEmpty()
            .let { CSS_URL.find(it)?.groupValues?.get(1) }
            ?.trim()
            .orEmpty()

        val title = card.selectFirst("div.caption.title h5 a")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: imageDiv?.attr("title")?.trim()?.takeIf { it.isNotBlank() }
            ?: id

        val duration = card.selectFirst("var.duration")?.text()?.trim()
            ?.takeIf { DURATION_TEXT.matches(it) }

        return HanimeInfo(
            title = title,
            coverUrl = cover,
            videoCode = id,
            duration = duration,
            itemType = HanimeInfo.NORMAL,
        )
    }

    /** `background-image: url('https://…')` / `url(&quot;…&quot;)` / `url(https://…)`。 */
    private val CSS_URL = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)

    /** `00:36` / `11:42` / `1:02:03`。 */
    private val DURATION_TEXT = Regex("""^\d{1,3}:\d{2}(:\d{2})?$""")

    /** 把若干栏目拼成首页模型；空栏目会被 [buildCategoryList] 自动过滤掉。 */
    fun homePage(sections: Map<String, List<HanimeInfo>>): WebsiteState<HomePage> {
        fun list(key: String) = sections[key].orEmpty().toMutableList()
        return WebsiteState.Success(
            HomePage(
                csrfToken = null,
                avatarUrl = null,
                username = null,
                banner = null,
                latestHanime = mutableListOf(),
                latestRelease = list(SEC_TOP),
                ecchiAnime = list(SEC_LATEST),
                shortEpisodeAnime = mutableListOf(),
                twoPointFiveDAnime = mutableListOf(),
                threeDCG = mutableListOf(),
                motionAnime = list(SEC_LONG),
                twoDAnime = mutableListOf(),
                aiGenerated = list(SEC_5MIN),
                mmd = mutableListOf(),
                cosplay = mutableListOf(),
                watchingNow = list(SEC_TOP7),
                newAnimeTrailer = mutableListOf(),
                userId = "",
            )
        )
    }

    //</editor-fold>

    //<editor-fold desc="详情">

    fun video(body: String): VideoLoadingState<HanimeVideo> {
        val doc = Jsoup.parse(body)

        fun meta(property: String): String? = doc.selectFirst("meta[property=$property]")
            ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        val title = meta("og:title")
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return VideoLoadingState.Error(ParseException("好色TV：未能解析影片标题"))

        val m3u8 = doc.selectFirst("source#video-source")?.attr("src")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("#video-source")?.attr("src")?.trim()?.takeIf { it.isNotBlank() }
            // ⚠️ 必须 `.find(...)?.value`：`findAll(...).firstOrNull()` 给的是
            // MatchResult?，和左边那些 String? 一 elvis 就并成 Any?，
            // 后面 `isNullOrBlank()` / `HanimeLink(m3u8, …)` 全都编译不过。
            ?: M3U8.find(body)?.value
        if (m3u8.isNullOrBlank()) {
            return VideoLoadingState.Error(ParseException("好色TV：未能解析播放地址"))
        }

        // keywords 是逗号分隔的标签；分类 / 地区 / 作者另行补上，方便搜索时命中。
        val tags = buildList {
            doc.selectFirst("meta[name=keywords]")?.attr("content")
                ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.let(::addAll)
            listOf("og:video:class", "og:video:area", "og:video:actor")
                .forEach { meta(it)?.let(::add) }
        }.distinct()

        return VideoLoadingState.Success(
            HanimeVideo(
                title = title,
                coverUrl = meta("og:image").orEmpty(),
                chineseTitle = null,
                introduction = meta("og:description"),
                uploadTime = meta("og:video:date")?.let {
                    runCatching { LocalDate.parse(it) }.getOrNull()
                },
                videoUrls = buildVideoUrls(m3u8),
                tags = tags,
            )
        )
    }

    /** 站点任何位置出现的 `.m3u8`（只在 `#video-source` 丢失时的兜底）。 */
    private val M3U8 = Regex("""https?://[^\s'"<>\\]+\.m3u8(?:\?[^\s'"<>\\#]*)?""")

    /**
     * 好色TV 每个视频只有一档画质，但 CDN 有三条线路可选。
     *
     * 于是把「清晰度」那个槽位拿来放线路 —— 那个菜单本来在这个站上是空的，
     * 用来切线路既不用新做 UI，又能让用户在「线路 1 卡住」时自己换一条。
     * 见 [HsexNetwork.cdnVariants]（换 host 就等于整条线路一起换，因为分片是相对路径）。
     */
    private fun buildVideoUrls(m3u8: String): ResolutionLinkMap {
        val variants = HsexNetwork.cdnVariants(m3u8)
        val map = linkedMapOf<String, HanimeLink>()
        if (variants.isEmpty()) {
            map["默认"] = HanimeLink(m3u8, HLS_SUBTYPE)
        } else {
            variants.forEach { (label, url) -> map[label] = HanimeLink(url, HLS_SUBTYPE) }
        }
        return map
    }

    /**
     * hsex 给的是 HLS 清单，分片是 MPEG-TS。
     *
     * [HanimeLink.subtype] = `mp2t` 让 [HanimeLink.suffix] 返回 `ts`，
     * 下载文件就叫 `…_默认.ts`。**这不是可有可无的**：真实字节是 TS，
     * 挂个 `.mp4` 后缀会被部分播放器按容器名去解析而失败。
     */
    private const val HLS_SUBTYPE = "mp2t"

    //</editor-fold>

    /**
     * 好色TV 没有 hanime 那种「本月新番预告」页，日历里也就没有对应的月度数据。
     * 返回一个**空但合法**的预告对象，让日历页展示空态而不是报错。
     */
    fun emptyPreview(): WebsiteState<HanimePreview> = WebsiteState.Success(
        HanimePreview(
            headerPicUrl = null,
            hasPrevious = false,
            hasNext = false,
            latestHanime = emptyList(),
            previewInfo = emptyList(),
        )
    )
}
