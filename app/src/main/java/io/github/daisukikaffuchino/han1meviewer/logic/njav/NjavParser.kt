package io.github.daisukikaffuchino.han1meviewer.logic.njav

import io.github.daisukikaffuchino.han1meviewer.HanimeLink
import io.github.daisukikaffuchino.han1meviewer.ResolutionLinkMap
import io.github.daisukikaffuchino.han1meviewer.logic.exception.ParseException
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistProfile
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimePreview
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeVideo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HomePage
import io.github.daisukikaffuchino.han1meviewer.logic.model.NjavActress
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.VideoLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import kotlinx.datetime.LocalDate
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * nJAV（njavtv.com）页面解析。
 *
 * 这里的产物**刻意复用 hanime 的模型**（[HomePage] / [HanimeInfo] / [HanimeVideo]），
 * 这样 ViewModel 与整个 Compose UI 一行都不用改，只是在 [io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo]
 * 里按当前数据源分流而已。
 *
 * 列表页的卡片结构（2026-09 实测）：
 *
 * ```html
 * <div class="thumbnail group">
 *   <div class="relative …">
 *     <a href="https://njavtv.com/pppe-440" alt="pppe-440">
 *       <video data-src="https://fourhoi.com/pppe-440/preview.mp4"></video>
 *       <img class="lozad w-full" data-src="https://fourhoi.com/pppe-440/cover-t.jpg" …>
 *     </a>
 *     <a href="…"><span class="absolute bottom-1 right-1 …">1:58:48</span></a>
 *   </div>
 *   <div class="my-2 …"><a href="…">PPPE-440 标题 - 女优</a></div>
 * </div>
 * ```
 *
 * ⚠️ 卡片的 `href` 有**三种写法**并存，全都靠 `substringAfterLast('/')` 取尾部 slug，
 * 所以这里不用改；但**拼详情页地址时必须走裸 slug**（见 [NjavNetwork.detailUrl]）：
 *
 * ```
 * https://njavtv.com/pppe-440              ← 规范形式（当前主流）
 * https://njavtv.com/dm75/waaa-214         ← 随机数字前缀，会变，不能照抄
 * https://njavtv.com/npjs-158-uncensored-leak
 * ```
 *
 * 详情页的字段全在 `og:` 系列 meta 里，播放地址见 [NjavPacker]。
 */
object NjavParser {

    /**
     * 番号 slug：字母开头、中间必含「-数字」（如 `venx-381`、`ssni-429-uncensored-leak`、`fc2-ppv-3668755`）。
     * 用来把「影片卡片」和「导航链接 / 女优页 / 分类页」区分开。
     */
    private val VIDEO_SLUG = Regex("""^[a-z]+-*\d[a-z0-9-]*$""")

    /** 已知的非影片路径，尾部再兜一层。 */
    private val NON_VIDEO_PATHS = setOf(
        "actresses", "genres", "makers", "new", "release", "uncensored-leak",
        "chinese-subtitle", "today-hot", "weekly-hot", "monthly-hot", "search",
        "legacy", "login", "register", "history", "playlists", "saved", "upload",
        "terms", "contact", "ads", "clive", "klive", "cn", "en", "ja", "ko",
    )

    /** 首页要抓的栏目：[键] 对应用 [homePage] 填入 [HomePage] 的哪个槽位。 */
    const val SEC_LATEST_AV = "latest_av"
    const val SEC_LATEST_RELEASE = "latest_release"
    const val SEC_UNCENSORED = "uncensored"
    const val SEC_CHINESE_SUBTITLE = "chinese_subtitle"
    const val SEC_WEEKLY_HOT = "weekly_hot"
    const val SEC_TODAY_HOT = "today_hot"
    const val SEC_MONTHLY_HOT = "monthly_hot"

    const val SEC_VR = "vr"

    /**
     * 首页栏目 → nJAV 路径。
     *
     * ⭐ 26.8 全部改成**站点真实导航里的栏目**（用户要求「扎根实际网页」）。
     * 路径来自 2026-09-14 抓下来的首页导航（`/cn` 会 301 到 `/dm###/cn`，导航本身稳定）：
     *
     * | 站点导航 | 路径 |
     * |---|---|
     * | 中文字幕 | `chinese-subtitle` |
     * | 最近更新 | `new` |
     * | 新作上市 | `release` |
     * | 无码流出 | `uncensored-leak` |
     * | 今日热门 | `today-hot` |
     * | 本週热门 | `weekly-hot` |
     * | 本月热门 | `monthly-hot` |
     * | VR | `genres/VR` |
     *
     * ⚠️ **刻意不加**的东西（用户点名不要）：`色色主播` / `韩国直播` / `中国直播`（外链直播站）、
     * `无广告免费漫画`、底部那一堆 `bit.ly` 推广位、以及 `site/123av` 之类的换量互链 ——
     * 它们是广告不是影片分类。
     *
     * 站点还有 `女优一览` / `女优排行` / `类型` / `发行商` 与二十来个**系列厂商页**
     * （SIRO / LUXU / FC2 / 东京热 / 一本道 / 麻豆传媒 …）：那些是索引页而非影片列表页，
     * 塞进首页会把真栏目挤掉，需要单独入口。
     */
    val HOME_SECTIONS: List<Pair<String, String>> = listOf(
        SEC_LATEST_AV to "new",
        SEC_LATEST_RELEASE to "release",
        SEC_UNCENSORED to "uncensored-leak",
        SEC_CHINESE_SUBTITLE to "chinese-subtitle",
        SEC_WEEKLY_HOT to "weekly-hot",
        SEC_TODAY_HOT to "today-hot",
        SEC_MONTHLY_HOT to "monthly-hot",
        SEC_VR to "genres/VR",
    )

    /**
     * [io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.buildCategoryList]
     * 在 AV 模式下给每个分类打的「检索标记」→ nJAV 路径。
     * 用户点某个分类的「更多」时，仓库层就是靠这张表把标记翻译成 nJAV 的分类页。
     */
    private val LEGACY_MARKER_TO_PATH = mapOf(
        "日本AV" to "new",
        "最新上市" to "release",
        "高清無碼" to "uncensored-leak",
        "中文字幕" to "chinese-subtitle",
        "他們在看" to "weekly-hot",
        "本日排行" to "today-hot",
        "本月排行" to "monthly-hot",
    )

    /** 站点真实导航里、可以被当作「影片列表页」打开的路径。 */
    private val REAL_LIST_PATHS = setOf(
        "new", "release", "uncensored-leak", "chinese-subtitle",
        "today-hot", "weekly-hot", "monthly-hot", "genres/VR",
    )

    /**
     * 「更多」的标记 → 路径。
     *
     * ⭐ 26.8 起标记**就是真实路径本身**（`new` / `release` / `uncensored-leak` / `genres/VR` …）：
     * 栏目名已经和站点导航一一对应，中间再夹一层中文别名只会多一处会漂移的映射。
     * 旧别名表保留，是为了兼容旧版本存下来的首页缓存/路由 —— 点「更多」不至于静默退回默认排序。
     */
    fun pathForMarker(marker: String?): String? {
        val value = marker?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value in REAL_LIST_PATHS) return value
        return LEGACY_MARKER_TO_PATH[value]
    }

    //<editor-fold desc="列表">

    /** 解析列表页（首页 / 分类页 / 搜索页共用同一套卡片结构）。 */
    fun videoList(body: String): MutableList<HanimeInfo> {
        val doc = Jsoup.parse(body)
        val result = LinkedHashMap<String, HanimeInfo>()
        doc.select("div.thumbnail.group").forEach { card ->
            parseCard(card)?.let { result.putIfAbsent(it.videoCode, it) }
        }
        return result.values.toMutableList()
    }

    fun pageState(body: String): PageLoadingState<MutableList<HanimeInfo>> {
        val list = videoList(body)
        return when {
            list.isNotEmpty() -> PageLoadingState.Success(list)
            hasNextPage(body) -> PageLoadingState.Success(list)
            else -> PageLoadingState.NoMoreData
        }
    }

    /** 列表页底部是否有 `rel="next"` 的「下一页」。 */
    fun hasNextPage(body: String): Boolean =
        Jsoup.parse(body).selectFirst("a[rel=next]") != null

    private fun parseCard(card: Element): HanimeInfo? {
        val slug = card.select("a[href]")
            .asSequence()
            .map { anchor ->
                anchor.attr("href").substringAfterLast('/').substringBefore('?').substringBefore('#')
            }
            .firstOrNull { it.isNotBlank() && it !in NON_VIDEO_PATHS && VIDEO_SLUG.matches(it) }
            ?: return null

        // 标题在卡片底部那个 div.my-2 的 <a> 里；退化时用 alt（番号）兜底。
        val title = card.selectFirst("div.my-2 a")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: card.selectFirst("a[alt]")?.attr("alt")?.trim()?.takeIf { it.isNotBlank() }?.uppercase()
            ?: slug.uppercase()

        val cover = card.selectFirst("img[data-src]")?.attr("data-src")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "https://fourhoi.com/$slug/cover-t.jpg"

        val duration = parseDuration(card)

        // 无码判定（26.8）：两种标记都要认。
        // 1. **片名 slug**：nJAV 的无码片尾缀就是 `-uncensored-leak`（列表卡片与详情页都带）；
        // 2. 卡片角标文案：站点在部分列表页会给「無修正 / 无码 / Uncensored」角标。
        // 只看 slug 会漏掉「slug 干净但带角标」的片，只看角标则会漏掉绝大多数
        // （角标是分类页特有的，普通列表页没有）。
        val uncensored = slug.contains("uncensored", ignoreCase = true) ||
                card.select("span.absolute").any { span ->
                    val text = span.text().trim()
                    text.contains("无码") || text.contains("無碼") ||
                            text.contains("無修正") || text.contains("无修正") ||
                            text.contains("uncensored", ignoreCase = true)
                }

        return HanimeInfo(
            title = title,
            coverUrl = cover,
            videoCode = slug,
            duration = duration,
            itemType = HanimeInfo.NORMAL,
            isUncensored = uncensored,
        )
    }

    /**
     * 卡片右下角的时长。
     *
     * ⚠️ **不能**直接 `selectFirst("span.absolute")`。卡片里有两个 `span.absolute`：
     *
     * ```html
     * <span class="absolute bottom-1 left-1 …bg-red-800…">中文字幕</span>   ← 角标，DOM 里在前
     * <span class="absolute bottom-1 right-1 …bg-gray-800…">2:45:03</span>  ← 真正的时长
     * ```
     *
     * 中文字幕 / 无码 分类页的卡片都带左侧角标，于是「取第一个」拿到的就是角标，
     * 表现为**右下角时长显示成「中文字幕」**（用户 2026-09-12 报的那个 bug）。
     *
     * 修法：不按位置猜，按**内容**挑 —— 只认长得像时长的那个（`1:58:48` / `12:34`）。
     * 顺带也把左侧角标的文案丢掉了，反正 [HanimeInfo] 没有装它的字段。
     */
    private fun parseDuration(card: Element): String? = card.select("span.absolute")
        .asSequence()
        .map { it.text().trim() }
        .firstOrNull { DURATION_TEXT.matches(it) }

    /** `1:58:48` / `12:34`；见 [parseDuration]。 */
    private val DURATION_TEXT = Regex("""^\d{1,3}:\d{2}(?::\d{2})?$""")

    /** 把若干栏目拼成首页模型；空栏目会被 [io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.buildCategoryList] 自动过滤掉。 */
    fun homePage(sections: Map<String, List<HanimeInfo>>): WebsiteState<HomePage> {
        fun list(key: String) = sections[key].orEmpty().toMutableList()
        return WebsiteState.Success(
            HomePage(
                csrfToken = null,
                avatarUrl = null,
                username = null,
                banner = null,
                // ⚠️ 槽位名是 hanime 那边的叫法，这里只是「借同一份界面按槽位取数」，
                // 与内容没有语义关系。**必须与 HomePageMappers 的 nJAV 分支一一对应**。
                latestHanime = list(SEC_WEEKLY_HOT),       // 本週热门
                latestRelease = list(SEC_LATEST_RELEASE),  // 新作上市
                ecchiAnime = list(SEC_LATEST_AV),          // 最近更新
                shortEpisodeAnime = list(SEC_VR),          // VR
                twoPointFiveDAnime = mutableListOf(),
                threeDCG = mutableListOf(),
                motionAnime = list(SEC_UNCENSORED),        // 无码流出
                twoDAnime = mutableListOf(),
                aiGenerated = list(SEC_CHINESE_SUBTITLE),  // 中文字幕
                mmd = list(SEC_MONTHLY_HOT),               // 本月热门
                cosplay = mutableListOf(),
                watchingNow = list(SEC_TODAY_HOT),         // 今日热门
                newAnimeTrailer = mutableListOf(),
                userId = "",
            )
        )
    }

    //</editor-fold>

    //<editor-fold desc="女优索引">

    /**
     * 解析女优索引页 `/cn/actresses`（以及它的 `?page=N` 翻页）。
     *
     * 只认「`<li>` 里既有 `<h4>`、又有指向 `/actresses/xxx` 的链接」的条目：
     * 导航菜单里同样有大量 `<li><a href="…">`，靠这两个条件就能滤干净。
     * `/actresses/ranking` 这类「不是具体某个人」的保留路径由 [ACTRESS_RESERVED] 挡掉。
     */
    fun actressList(body: String): MutableList<NjavActress> {
        val doc = Jsoup.parse(body)
        val result = LinkedHashMap<String, NjavActress>()
        doc.select("li").forEach { item ->
            val href = item.selectFirst("a[href]")?.attr("href").orEmpty()
            val path = actressPath(href) ?: return@forEach
            val name = item.selectFirst("h4")?.text()?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            val text = item.text()
            result.putIfAbsent(
                name,
                NjavActress(
                    name = name,
                    avatarUrl = item.selectFirst("img[src]")?.attr("src")?.trim().orEmpty(),
                    videoCount = ACTRESS_VIDEO_COUNT.find(text)?.groupValues?.get(1)
                        ?.replace(",", "")?.toIntOrNull(),
                    debutYear = ACTRESS_DEBUT_YEAR.find(text)?.groupValues?.get(1)?.toIntOrNull(),
                    path = path,
                )
            )
        }
        return result.values.toMutableList()
    }

    /**
     * 从卡片 `href` 里取出 `actresses/<编码后的名字>` 这一段。
     *
     * ```
     * https://njavtv.com/dm288/cn/actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3
     *                              ↓ 丢掉会变的 dm### 前缀 + 语言段
     * actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3
     * ```
     *
     * 不是女优详情的链接（导航、`/actresses/ranking`、`/actresses/genres` 之类）返回 null。
     */
    private fun actressPath(href: String): String? {
        val marker = "/actresses/"
        val index = href.indexOf(marker)
        if (index < 0) return null
        val tail = href.substring(index + marker.length)
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
        if (tail.isEmpty() || tail in ACTRESS_RESERVED) return null
        return "$ACTRESSES_SEGMENT/$tail"
    }

    /** 女优路径段，与 [NjavNetwork.actressUrl] 拼地址时用的前缀保持一致。 */
    private const val ACTRESSES_SEGMENT = "actresses"

    /** 索引页上不是「具体某个人」的保留尾段。 */
    private val ACTRESS_RESERVED = setOf("ranking")

    /** 卡片上的「5668 条影片」。 */
    private val ACTRESS_VIDEO_COUNT = Regex("""([\d,]+)\s*条影片""")

    /** 卡片上的「2008 出道」。 */
    private val ACTRESS_DEBUT_YEAR = Regex("""(\d{4})\s*出道""")

    //</editor-fold>

    //<editor-fold desc="女优页（资料头 / 排序 / 筛选）">

    /**
     * 女优页顶部的**资料头**。
     *
     * 站点的结构（2026-09-14 抓取 `/actresses/<名字>` 实测）：
     *
     * ```html
     * <h1>持野蓬的 AV 影片库</h1>
     * <div x-init="...axios.get('https://njavtv.com/api/actresses/1112912/view')...">
     *   <div class="... rounded-full w-24 h-24"><div>持</div></div>   ← 头像位（只有首字占位）
     *   <div class="font-medium text-lg">
     *     <h4 class="text-nord6">持野蓬</h4>
     *     <div class="mt-2 ... text-nord9">
     *       <p>158cm / 40J - 22 - 33</p>      ← 身材（该女优没有数据时是空的 <p></p>）
     *       <p>1987-05-25 （39岁）</p>         ← 生日与年龄（同样可能为空）
     *     </div>
     *   </div>
     * </div>
     * ```
     *
     * ⚠️ 三个必须知道的点：
     * 1. **这两行 `<p>` 可能整段是空的**（站点对没资料的女优只留空 `<p></p>`）——
     *    所以解析结果必须允许为空，界面也要允许不显示，不能拿默认值硬凑。
     * 2. **这里的头像是首字占位符，不是图片**：女优页**给不出头像**（真头像在女优一览的
     *    `fourhoi.com/actress/<id>-t.jpg`）。想要头像得去索引页按名字找，见
     *    `NetworkRepo.findNjavActress`。
     * 3. 资料头里那份数据是 **JS 调 `/api/actresses/<id>/view` 填的**，服务端渲染时
     *    往往为空 —— 我们能拿到的就是 HTML 里已经有的部分；拿不到就不显示，
     *    **绝不为此再打一次那个被反爬保护的 API**（实测它对非浏览器客户端直接回挑战页）。
     */
    fun actressProfile(body: String): ArtistProfile? {
        val doc = Jsoup.parse(body)
        val header = doc.selectFirst("div.hero-pattern") ?: return null
        val name = header.selectFirst("h4")?.text()?.trim().orEmpty()
        val lines = header.select("div.text-nord9 p, div.text-sm p, div.xs\\:text-base p")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
        if (name.isEmpty() && lines.isEmpty()) return null
        val measurements = lines.firstOrNull { MEASUREMENTS.matches(it) }.orEmpty()
        val birthday = lines.firstOrNull { BIRTHDAY.containsMatchIn(it) }.orEmpty()
        return ArtistProfile(
            name = name,
            measurements = measurements,
            birthday = birthday,
        )
    }

    /** `158cm / 40J - 22 - 33`：必须同时有 cm 与三围分隔，避免把简介里的数字当身材。 */
    private val MEASUREMENTS = Regex("""^\d{2,3}\s*cm\s*/.*\d.*-.*\d""")

    /** `1987-05-25 （39岁）`：只认日期那一段，后面括号里的年龄随站点怎么写都行。 */
    private val BIRTHDAY = Regex("""\d{4}-\d{2}-\d{2}""")

    //</editor-fold>

    //<editor-fold desc="详情">

    fun video(body: String): VideoLoadingState<HanimeVideo> {
        val doc = Jsoup.parse(body)

        fun meta(property: String): String? = doc.selectFirst("meta[property=$property]")
            ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        val title = meta("og:title")
            ?: doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return VideoLoadingState.Error(ParseException("nJAV：未能解析影片标题"))

        val videoUrls = buildVideoUrls(NjavPacker.extractM3u8(body))
        if (videoUrls.isEmpty()) {
            return VideoLoadingState.Error(ParseException("nJAV：未能解析播放地址"))
        }

        val tags = doc.selectFirst("meta[name=keywords]")?.attr("content")
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            .orEmpty()

        val artists = artistsOf(doc)

        return VideoLoadingState.Success(
            HanimeVideo(
                title = title,
                coverUrl = meta("og:image").orEmpty(),
                chineseTitle = null,
                introduction = meta("og:description"),
                uploadTime = meta("og:video:release_date")?.let {
                    runCatching { LocalDate.parse(it) }.getOrNull()
                },
                videoUrls = videoUrls,
                tags = tags,
                // artist 保留「第一位」，给只认单个对象的调用方用；界面画的是 artists。
                artist = artists.firstOrNull(),
                artists = artists,
            )
        )
    }

    /**
     * 详情页的**女优**（26.6.3 起解析）。
     *
     * 站点的字段表长这样（实测 2026-09-13，繁体页面）：
     *
     * ```html
     * <div class="text-secondary">
     *     <span>女優:</span>
     *     <a href="https://njavtv.com/actresses/%E6%8C%81%E9%87%8E%E8%93%AC" class="text-nord13 font-medium">持野蓬</a>
     * </div>
     * ```
     *
     * 三个要点：
     *
     * 1. **不能只取第一个 `.text-secondary > a`**：同一张表里还有「類型」「發行日期」「番號」，
     *    它们的值也是链接（类型就是 `genres/…`）。所以要按标签文字筛出「女優」那一行。
     * 2. **`href` 必须原样保留**（含 URL 编码的名字）：站点给的编码就是作者页能接受的写法，
     *    拿显示名重新编码会撞上繁简差异 —— 与女优索引那条「href 繁体、h4 简体」是同一个坑。
     * 3. 一个视频**很少**有多位女优，但列表页确实见过合作片；这里全部收下。
     */
    private fun artistsOf(doc: org.jsoup.nodes.Document): List<HanimeVideo.Artist> {
        val result = LinkedHashMap<String, HanimeVideo.Artist>()
        for (block in doc.select("div.text-secondary")) {
            val label = block.selectFirst("span")?.text()?.trim().orEmpty()
            if (!label.startsWith("女優") && !label.startsWith("演員") && !label.startsWith("演员")) continue
            for (anchor in block.select("a[href]")) {
                val href = anchor.attr("href").trim()
                if (!href.contains("/actresses/")) continue
                val name = anchor.text().trim().ifEmpty { href.substringAfterLast('/') }
                if (name.isEmpty()) continue
                val absolute = if (href.startsWith("http")) {
                    href
                } else {
                    NjavNetwork.BASE_URL.trimEnd('/') + "/" + href.trimStart('/')
                }
                result.putIfAbsent(
                    name,
                    HanimeVideo.Artist(
                        name = name,
                        avatarUrl = "",
                        genre = "",
                        url = absolute,
                    ),
                )
            }
        }
        return result.values.toList()
    }

    /**
     * nJAV 的 `<source>` 一般只有一份自适应主列表（`playlist.m3u8`）加若干固定码率。
     * ⚠️ 顺序有讲究：播放器在「用户偏好清晰度不存在」时会退回**最后一项**，
     * 所以自适应的主列表必须放最后，普通用户默认就能拿到最好的画质。
     */
    private fun buildVideoUrls(urls: List<String>): ResolutionLinkMap {
        val master = urls.firstOrNull {
            it.toHttpUrlOrNull()?.encodedPath?.endsWith("/playlist.m3u8", ignoreCase = true) == true
        }
        val map = linkedMapOf<String, HanimeLink>()
        urls.filter { it != master }
            .distinct()
            .forEach { url ->
                val label = qualityLabel(url)
                if (label !in map) map[label] = HanimeLink(url, HLS_SUBTYPE)
            }
        master?.let { map["自动"] = HanimeLink(it, HLS_SUBTYPE) }
        return map
    }

    /**
     * nJAV 给的全是 HLS 清单，下载后拼出来的是 MPEG-TS。
     *
     * [HanimeLink.subtype] = `mp2t` 会让 [HanimeLink.suffix] 返回 `ts`，
     * 于是下载文件叫 `…_1080P.ts`。**这不是可有可无的**：真实字节是 TS，
     * 挂个 `.mp4` 后缀会让部分播放器按容器名去解析而失败。
     */
    private const val HLS_SUBTYPE = "mp2t"

    /**
     * 从播放地址里抽清晰度标签。
     *
     * 站点有两代路径写法，都要能吃下（2026-09 实测两者并存）：
     *
     * ```
     * https://surrit.com/<uuid>/720p/video.m3u8        ← 现行
     * https://surrit.com/<uuid>/1280x720/video.m3u8    ← 另一种
     * ```
     *
     * 注意 `1280x720` 里的 `720` 是**高**，`842x480` 里的 `480` 也是高 ——
     * 统一取高度当标签（`720P` / `480P`），跟 hanime 侧的
     * [io.github.daisukikaffuchino.han1meviewer.HanimeResolution] 命名保持一致。
     *
     * ⚠️ 别再写回 `/(\d{3,4})p/` 这种只认一种写法的正则：一旦站点换成
     * `1280x720` 形式，所有条目都会掉进「其他」，而 [buildVideoUrls] 里的
     * 去重是「同标签只留第一个」，于是清晰度列表会**只剩一项**。
     */
    private fun qualityLabel(url: String): String {
        val groups = RESOLUTION_IN_URL.find(url)?.groupValues ?: return UNKNOWN_QUALITY_LABEL
        // 1=宽、2=高（`1280x720`）；3=高（`720p`，另一种写法里 1、2 参与不到匹配，是空串）
        val height = groups.getOrNull(2)?.takeIf { it.isNotBlank() }
            ?: groups.getOrNull(3)?.takeIf { it.isNotBlank() }
            ?: groups.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: return UNKNOWN_QUALITY_LABEL
        return "${height}P"
    }

    private val RESOLUTION_IN_URL = Regex("""/(?:(\d{3,4})x(\d{3,4})|(\d{3,4})p)/""", RegexOption.IGNORE_CASE)

    /** 认不出清晰度时的兜底标签。 */
    private const val UNKNOWN_QUALITY_LABEL = "其他"

    //</editor-fold>

    /**
     * nJAV 没有 hanime 那种「本月新番预告」页，日历里也就没有对应的月度数据。
     * 这里返回一个**空但合法**的预告对象，让日历页展示空态而不是报错。
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
