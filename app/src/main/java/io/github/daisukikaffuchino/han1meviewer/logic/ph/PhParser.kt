package io.github.daisukikaffuchino.han1meviewer.logic.ph

import io.github.daisukikaffuchino.han1meviewer.HanimeLink
import io.github.daisukikaffuchino.han1meviewer.ResolutionLinkMap
import io.github.daisukikaffuchino.han1meviewer.logic.exception.ParseException
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistProfile
import io.github.daisukikaffuchino.han1meviewer.logic.model.ArtistVideosPage
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimePreview
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeVideo
import io.github.daisukikaffuchino.han1meviewer.logic.model.HomePage
import io.github.daisukikaffuchino.han1meviewer.logic.state.PageLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.VideoLoadingState
import io.github.daisukikaffuchino.han1meviewer.logic.state.WebsiteState
import kotlinx.datetime.LocalDate
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Pornhub 页面解析 —— mod 26.6 新增的第三个数据源。
 *
 * 产物**刻意复用 hanime 的模型**（[HomePage] / [HanimeInfo] / [HanimeVideo]），
 * 于是 ViewModel 与整个 Compose UI 一行都不用改，只在
 * [io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo] 里按数据源分流。
 *
 * ## 两条完全不同的取数路径
 *
 * | 页面 | 来源 | 形态 |
 * |---|---|---|
 * | 首页 / 列表 / 搜索 | `/webmasters/search` | **JSON**（30 条/页，字段齐全） |
 * | 首页的「推荐」一行 | `/recommended` | **HTML**（约 1 MB，21 条/页，见 [recommendedList]） |
 * | 作者页 | `/pornstar|/model/<slug>/videos` | **HTML**（约 1.2 MB，40–49 条/页） |
 * | 详情 | `/view_video.php?viewkey=…` | HTML，播放地址埋在 `var flashvars_<id> = {…}` 里 |
 *
 * 列表之所以不抓 HTML：站点列表页有 1.2–1.6 MB，而 JSON 只有约 140 KB，
 * 而这一切都要经过自建中转（见 [PhNetwork]），差 10 倍的流量没有理由不用 JSON。
 * **只有推荐和作者页没有等价的 JSON 接口**，才不得不抓 HTML（两条都复用同一套卡片解析）。
 *
 * ## 详情页最容易踩的两个坑
 *
 * 1. **页面上有多个视频的 JSON**（预加载 / 推荐 / 广告），
 *    所以 `"duration":796` 这种关键字全局搜到的很可能是**别的视频**的。
 *    正确做法是把 `flashvars_*` 那个对象整体抠出来（见 [extractJsonObject]），
 *    再取它自己的 `mediaDefinitions`。
 * 2. **分类 / 标签 / 演员在 `div.video-detailed-info` 里，而观看数不在** ——
 *    观看数在更靠前的 `div.video-actions-menu div.ratingInfo div.views span.count`。
 *    全局搜 `class="views"` 会先撞到推荐位的卡片（那边是 `span.views > var`，且**没有**
 *    `span.count`）；反过来把观看数也限定在 `video-detailed-info` 里又会拿到 `null`。
 *    两处作用域不一样，别图省事写成同一个。
 * 3. 媒体表里那个 `height=2160 / quality=[] / format=mp4` 的条目指向
 *    `www.pornhub.com/video/get_media?s=…`，是个**要带 Cookie 走重定向的中间页**，
 *    不是可直接播放的地址；靠 `format == "hls"` 把它滤掉。
 */
object PhParser {

    //<editor-fold desc="列表（JSON 接口）">

    /**
     * 解析 `/webmasters/search` 的响应。
     *
     * 容错优先：站点偶尔会回一页 HTML（限流 / 维护），这时**返回空列表而不是抛异常** ——
     * 上层 [pageState] 会把「空 + 没有下一页」翻译成 `NoMoreData`，
     * 界面表现是「到底了」，比整页报错更接近事实（毕竟这一页确实没数据）。
     */
    fun videoList(body: String): MutableList<HanimeInfo> {
        val array = runCatching { JSONObject(body).optJSONArray("videos") }.getOrNull()
            ?: return mutableListOf()
        val result = LinkedHashMap<String, HanimeInfo>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            parseItem(item)?.let { result.putIfAbsent(it.videoCode, it) }
        }
        return result.values.toMutableList()
    }

    private fun parseItem(item: JSONObject): HanimeInfo? {
        val id = item.optString("video_id").trim().takeIf { it.isNotEmpty() }
            ?: PhNetwork.videoIdFrom(item.optString("url"))
            ?: return null

        val duration = item.optString("duration").trim().takeIf { DURATION_TEXT.matches(it) }

        val views = item.optLong("views", 0L).takeIf { it > 0L }?.toString()

        // publish_date 形如 "2025-11-03 08:24:34"，只取日期部分。
        val uploadTime = item.optString("publish_date").trim().take(DATE_LENGTH)
            .takeIf { DATE_TEXT.matches(it) }

        val categories = childNames(item, "categories", "category")
        val pornstars = childNames(item, "pornstars", "pornstar_name")

        return HanimeInfo(
            title = item.optString("title").trim().takeIf { it.isNotEmpty() } ?: id,
            coverUrl = item.optString("thumb").trim()
                .takeIf { it.isNotEmpty() }
                ?: item.optString("default_thumb").trim(),
            videoCode = id,
            duration = duration,
            views = views,
            uploadTime = uploadTime,
            genre = categories.firstOrNull(),
            itemType = HanimeInfo.NORMAL,
            currentArtist = pornstars.takeIf { it.isNotEmpty() }?.joinToString(", ").orEmpty(),
        )
    }

    /** 把 `[{"tag_name":"x"},…]` 这类数组里的某个字段抽成字符串列表。 */
    private fun childNames(parent: JSONObject, arrayKey: String, fieldKey: String): List<String> {
        val array = parent.optJSONArray(arrayKey) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val name = array.optJSONObject(i)?.optString(fieldKey)?.trim().orEmpty()
                if (name.isNotEmpty()) add(name)
            }
        }
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
     * 是否还有下一页。
     *
     * 这个接口**不返回总页数**，只按 [PhNetwork.PAGE_SIZE] 一页一页给。
     * 所以判据就是「这一页给满了吗」——给满说明后面大概率还有。
     *
     * 代价是「最后一页恰好给满 30 条」时会多请一次空页，随后由
     * [pageState] 判成 `NoMoreData`。这个代价比「提前判死、最后 30 条看不到」小得多。
     */
    fun hasNextPage(body: String, page: Int): Boolean {
        val count = runCatching { JSONObject(body).optJSONArray("videos")?.length() }.getOrNull() ?: 0
        return count >= PhNetwork.PAGE_SIZE
    }

    /** `00:36` / `11:42` / `1:02:03`。 */
    private val DURATION_TEXT = Regex("""^\d{1,3}:\d{2}(:\d{2})?$""")

    /** `2025-11-03`。 */
    private val DATE_TEXT = Regex("""^\d{4}-\d{2}-\d{2}$""")

    private const val DATE_LENGTH = 10

    //</editor-fold>

    //<editor-fold desc="首页 / 列表标记">

    /**
     * 首页栏目 → 往 [HomePage] 哪个槽位填；由 [homePage] 使用。
     *
     * @param recommended 站点自己的「推荐」（`/recommended` 页的第 1 页）。
     *   单独传进来是因为它**不是**一个 JSON 检索栏目：它要抓一整页 HTML（约 1 MB），
     *   比那 10 个 JSON 栏目都慢，所以仓库层是先发一版没有它的首页、等它回来再补发一版
     *   （见 [io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo.phHomePageFlow]）。
     *   为空时它占的槽位就是空列表，界面按「没内容的行不画」处理。
     */
    fun homePage(
        sections: Map<String, List<HanimeInfo>>,
        recommended: List<HanimeInfo> = emptyList(),
    ): WebsiteState<HomePage> {
        fun list(key: String) = sections[key].orEmpty().toMutableList()
        // 槽位名是 hanime 那边的叫法，内容与它毫无关系 —— 这里只是借用
        // 「同一份界面按槽位取数」的机制。映射关系见 HomePageMappers.buildCategoryList
        // 里 isPornhubSite 的那几个分支，两边必须一一对应。
        //
        // Pornhub 用掉 10 个槽、留空 3 个（aiGenerated / newAnimeTrailer / cosplay），
        // 「推荐」占 newAnimeTrailer —— 那是这三个里语义最不相干的一个（hanime 的
        // 「本月新番预告」页在 Pornhub 根本没有对应物，见 [emptyPreview]）。
        return WebsiteState.Success(
            HomePage(
                csrfToken = null,
                avatarUrl = null,
                username = null,
                banner = null,
                latestHanime = list(PhNetwork.SEC_WEEKLY),
                latestRelease = list(PhNetwork.SEC_POPULAR),
                ecchiAnime = list(PhNetwork.SEC_LATEST),
                shortEpisodeAnime = list(PhNetwork.SEC_AMATEUR),
                twoPointFiveDAnime = list(PhNetwork.SEC_CHINESE),
                threeDCG = list(PhNetwork.SEC_HENTAI),
                motionAnime = list(PhNetwork.SEC_JAPANESE),
                twoDAnime = list(PhNetwork.SEC_COSPLAY),
                aiGenerated = mutableListOf(),
                mmd = list(PhNetwork.SEC_EXCLUSIVE),
                cosplay = mutableListOf(),
                watchingNow = list(PhNetwork.SEC_TOP_RATED),
                newAnimeTrailer = recommended.toMutableList(),
                userId = "",
            )
        )
    }

    /**
     * 检索标记 → 接口查询条件。
     *
     * 标记由 [io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.buildCategoryList]
     * 在 Pornhub 数据源下打出，也是搜索页「分类」下拉里 [PhNetwork] 那一套取值。
     *
     * ⚠️ 简繁两套都要认：界面文案按语言给的是简体，而搜索下拉的 `search_key` 取自
     * `genre_ph.json`，那里用的是繁体（与 `genre_av.json` 的习惯一致）。
     * 只认一套的话，另一种语言下点「更多」会静默退回默认排序。
     *
     * 目前覆盖 [PhNetwork.HOME_SECTIONS] 的全部 10 个栏目，外加首页以外的几个别名
     * （如「最多播放」「評分」「角色扮演」）—— 别名是给搜索页那一栏用的，
     * 用户能选到的每一项都必须在这里有映射，否则点了没反应。
     */
    fun queryForMarker(marker: String?): PhNetwork.PhQuery? =
        marker?.trim()?.takeIf { it.isNotEmpty() }?.let { MARKER_TO_QUERY[it] }

    /**
     * ⭐ 「推荐」这一行的标记（26.9.5）。
     *
     * 它**故意不走 [queryForMarker]**：那套映射的产物是 [PhNetwork.PhQuery]，
     * 也就是「同一个检索接口换参数」，而「推荐」是另一个页面（`/recommended`，整页 HTML）。
     * 由 [io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo.resolvePhListUrl]
     * 先问这一个、再问 [queryForMarker]。
     *
     * 简繁同义都收，因为界面文案按语言给的是简体，而别处（`genre_ph.json`）用繁体。
     */
    const val RECOMMENDED_MARKER = "推薦"

    private val RECOMMENDED_MARKERS = setOf(RECOMMENDED_MARKER, "推荐", "Recommended", "recommended")

    /** [marker] 是不是「推荐」那一行的标记。 */
    fun isRecommendedMarker(marker: String?): Boolean =
        marker?.trim()?.let { it in RECOMMENDED_MARKERS } == true

    private val MARKER_TO_QUERY: Map<String, PhNetwork.PhQuery> = buildMap {
        PhNetwork.PhQuery(ordering = "newest").let { q ->
            listOf("全部", "最新", "無码", "無碼", "无码", "无码").forEach { put(it, q) }
        }
        PhNetwork.PhQuery(ordering = "mostviewed").let { q ->
            listOf("最多觀看", "最多观看", "最多播放").forEach { put(it, q) }
        }
        PhNetwork.PhQuery(ordering = "rating").let { q ->
            listOf("最高評分", "最高评分", "評分", "评分").forEach { put(it, q) }
        }
        // 本週熱門 = mostviewed + period=weekly。判据是实测「与不带的相比一页零重合」，
        // 不是照着站点文案猜的（见 PhNetwork.HOME_SECTIONS）。
        PhNetwork.PhQuery(ordering = "mostviewed", period = "weekly").let { q ->
            listOf("本週熱門", "本周热门", "熱門", "热门").forEach { put(it, q) }
        }
        // 下面这些都是**实测过的真实标签 slug**（命中率见 PhNetwork.HOME_SECTIONS）。
        PhNetwork.PhQuery(tag = "japanese").let { q ->
            listOf("日本", "日本AV", "日系").forEach { put(it, q) }
        }
        PhNetwork.PhQuery(tag = "chinese").let { q ->
            listOf("華語", "华语", "中文", "國產", "国产").forEach { put(it, q) }
        }
        PhNetwork.PhQuery(tag = "verified-amateurs").let { q ->
            listOf("素人", "業餘素人", "业余素人", "素人業餘").forEach { put(it, q) }
        }
        PhNetwork.PhQuery(tag = "hentai").let { q ->
            listOf("動漫", "动漫", "二次元").forEach { put(it, q) }
        }
        // slug 本身是 `cosplay`，界面文案也一样（简繁同形），所以三种写法都收。
        PhNetwork.PhQuery(tag = "cosplay").let { q ->
            listOf("Cosplay", "cosplay", "角色扮演").forEach { put(it, q) }
        }
        PhNetwork.PhQuery(tag = "exclusive").let { q ->
            listOf("官方獨家", "官方独家", "獨家", "独家").forEach { put(it, q) }
        }
    }

    //</editor-fold>

    //<editor-fold desc="详情">

    fun video(body: String): VideoLoadingState<HanimeVideo> {
        val doc = Jsoup.parse(body)

        fun meta(property: String): String? = doc.selectFirst("meta[property=$property]")
            ?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        val title = doc.selectFirst("h1.title span.inlineFree")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: meta("og:title")
            ?: return VideoLoadingState.Error(ParseException("Pornhub：未能解析影片标题"))

        val hls = mediaDefinitions(body).filter { it.isHls && it.url.isNotBlank() }
        if (hls.isEmpty()) {
            return VideoLoadingState.Error(ParseException("Pornhub：未能解析播放地址"))
        }

        // ⚠️ tags / categories / pornstars 都要在 div.video-detailed-info 里找，
        //    全局搜会先撞到推荐位的卡片。
        val info = doc.selectFirst("div.video-detailed-info")

        // ⚠️ 但**观看数不在** video-detailed-info 里 —— 它在更靠前的
        //    `div.video-actions-menu > div.ratingInfo > div.views > span.count`，
        //    而 video-detailed-info 是后面那块（演员 / 分类 / 标签）。
        //    实测（真页面留档）：`info.selectFirst("div.ratingInfo …")` 返回 **null**，
        //    所以这里必须**全局**取；好在整页 `div.ratingInfo` 只有 1 个
        //    （推荐位用的是 `span.views > var`，没有 ratingInfo 也没有 span.count），不会串味。
        val views = doc.selectFirst("div.ratingInfo div.views span.count")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }

        val uploadTime = UPLOAD_DATE.find(body)?.groupValues?.get(1)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        // 标签区：`tagsWrapper` 是关键词标签（doggystyle / big boobs …），
        // `categoriesWrapper` 是站点的正式分类（Amateur / Big Ass …）。界面只要一份，
        // 所以合起来去重。两个容器都在 `video-detailed-info` 里，别全局搜 ——
        // 全局会先撞到推荐位卡片的同名 class。
        val tagList = info?.select("div.tagsWrapper a.isTag span")?.mapNotNull { el ->
            el.text().trim().takeIf { it.isNotEmpty() }
        }.orEmpty()
        val categoryList = info?.select("div.categoriesWrapper a.item")?.mapNotNull { el ->
            el.text().trim().takeIf { it.isNotEmpty() }
        }.orEmpty()
        val tags = (tagList + categoryList).distinct()

        // ⭐ 作者必须从**两处**取，只取一处都不全 —— 26.6.1 被吐槽「作者显示不全」就是只取了第二处。
        //
        //   1. `div.userInfoBlock`：站点标为「主模特 / 上传者」的那一位，也是唯一带
        //      **作品数**与**关注者数**的地方（`<span>87 Videos</span>`、
        //      `<span>448K Subscribers</span>`），最像用户心里的「作者」。
        //      ⚠️ 这一块在 `video-detailed-info` **里面**，别全局搜 `usernameWrap` ——
        //      推荐位卡片里有 180+ 个同名 class（实测）。
        //   2. `div.pornstarsWrapper a.pstar-list-btn`：参演的全部演员，一部片常 2–4 位。
        //
        //   两处会重复（主模特几乎总在演员列表里），按名字去重、主模特排最前。
        // `video-detailed-info` 里的三块：主模特块 / 演员列表 / 分类与标签。
        fun imgUrl(el: Element?): String = el?.let { e ->
            listOf("src", "data-src", "data-image", "data-thumb_url")
                .firstNotNullOfOrNull { e.attr(it).trim().takeIf { s -> s.isNotEmpty() } }
        }.orEmpty()

        val primaryBlock = info?.selectFirst("div.userInfoBlock")
        val primaryLink = primaryBlock?.selectFirst("div.userInfo .usernameWrap a.bolded")
        // `div.userInfo span` 里既有名字徽章也有数据文案，靠关键字挑，别按下标取。
        val primaryStats = primaryBlock?.select("div.userInfo span")?.map { it.text().trim() }.orEmpty()
        val primaryArtist = primaryLink
            ?.takeIf { it.text().isNotBlank() }
            ?.let { link ->
                HanimeVideo.Artist(
                    name = link.text().trim(),
                    avatarUrl = imgUrl(primaryBlock?.selectFirst("div.userAvatar img")),
                    genre = categoryList.firstOrNull().orEmpty(),
                    url = link.attr("href").trim(),
                    videoCount = primaryStats.firstOrNull { it.contains("video", true) }.orEmpty(),
                    subscriberCount = primaryStats.firstOrNull { it.contains("subscrib", true) }.orEmpty(),
                )
            }

        val pornstars = info?.select("div.pornstarsWrapper a.pstar-list-btn")?.mapNotNull { a ->
            val name = a.ownText().trim().takeIf { it.isNotEmpty() }
                ?: a.attr("href").substringAfterLast('/').takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            HanimeVideo.Artist(
                name = name,
                // 头像在 phncdn 上，靠自建中转的 ?ref= 才拿得到（见 CdnRelay.refererFor）。
                avatarUrl = imgUrl(a.selectFirst("img")),
                // 副标题用第一个**分类**（如 "Amateur"）—— 用第一个 tag 会变成
                // "doggystyle" 这种动作词，完全不像「这是谁」。
                genre = categoryList.firstOrNull().orEmpty(),
                url = a.attr("href").trim(),
            )
        }.orEmpty()

        val artists = buildList {
            primaryArtist?.let { add(it) }
            pornstars.forEach { a ->
                if (none { it.name.equals(a.name, ignoreCase = true) }) add(a)
            }
        }

        return VideoLoadingState.Success(
            HanimeVideo(
                title = title,
                coverUrl = meta("og:image").orEmpty(),
                chineseTitle = null,
                introduction = meta("og:description"),
                uploadTime = uploadTime,
                views = views,
                videoUrls = buildVideoUrls(hls),
                tags = tags,
                // artist 保留「第一位」，是给订阅等只认单个对象的调用方用的；
                // 界面上画的是 artists（见 VideoIntroductionScreen）。
                artist = artists.firstOrNull(),
                artists = artists,
            )
        )
    }

    /** 结构化数据里的上传日期（`"uploadDate": "2026-08-19T01:28:14+00:00"`）。 */
    private val UPLOAD_DATE = Regex(""""uploadDate"\s*:\s*"(\d{4}-\d{2}-\d{2})""")

    /** 一条 `mediaDefinitions` 记录。 */
    data class PhMedia(
        val quality: String,
        val height: Int,
        val format: String,
        val url: String,
        val isDefault: Boolean,
    ) {
        val isHls: Boolean get() = format.equals("hls", ignoreCase = true)

        /**
         * ⭐ 这条地址是不是**可取**的那种签名形态。
         *
         * 实测（2026-09-13）站点会随机发两种形态，同一个视频、同样的请求头：
         *
         * | 形态 | 长相 | 结果 |
         * |---|---|---|
         * | **A** | `?validfrom=…&validto=…&ipa=1&hdl=-1&hash=…` | 可取（master / 子清单 / 分片全 200） |
         * | **B** | `?h=…&e=…&f=1` | **必 410** —— 12 种组合（UA × Referer × cookie）全试过 |
         *
         * 判据**只认 `validfrom`**，别去认主机名：主机名（`ev-h` / `em-h` / `hv-h` / `hm-h` / `ee-h` …）
         * 每次请求都在变，而且两种形态都可能出现在任意主机上。
         *
         * B 形态不是「过期」——它的 `e=` 明明在未来一小时。它就是**取不到**，
         * 站点侧灰度迁移的中间状态。所以只能绕开，见 [PhNetwork.embedUrl] 那条退路。
         */
        val hasTimeWindow: Boolean get() = url.contains("validfrom=")

        fun resolvedHeight(): Int = height.takeIf { it > 0 }
            ?: quality.filter { it.isDigit() }.toIntOrNull()
            ?: 0
    }

    /**
     * 抠出 `mediaDefinitions` 里的一份媒体表。
     *
     * 不能对它整体用正则：每个条目里有嵌套的 `segmentFormats:{…}`，
     * 而 JSON 的 `{}` 又不允许跨层匹配。所以先做一次**括号配对扫描**把整个对象切出来，
     * 再交给 [JSONObject] 解析（见 [extractJsonObject]）。
     *
     * 两条来源路径都要支持，因为播放地址现在从两处取（见 [NetworkRepo.phVideoFlow]）：
     * 1. **详情页** `view_video.php`：`var flashvars_<id> = { … "mediaDefinitions":[…] }`
     * 2. **embed 页** `/embed/<viewkey>`：没有 `flashvars`，只在一段 JSON 里裸着
     *    `"mediaDefinitions":[…]`（这一页连 `flashvars` 这个词都不出现）
     */
    fun mediaDefinitions(body: String): List<PhMedia> {
        val array = mediaDefinitionArray(body) ?: return emptyList()

        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("videoUrl").trim()
                if (url.isEmpty()) continue
                add(
                    PhMedia(
                        quality = item.optString("quality").trim(),
                        height = item.optInt("height", 0),
                        format = item.optString("format").trim(),
                        url = url,
                        isDefault = item.optBoolean("defaultQuality", false),
                    )
                )
            }
        }
    }

    /** 这份 HTML 给的播放地址是否**可信**（存在带时间窗的 HLS 档）。 */
    fun mediaLooksPlayable(html: String): Boolean =
        mediaDefinitions(html).any { it.isHls && it.url.isNotBlank() && it.hasTimeWindow }

    /**
     * 用另一份 HTML（[PhNetwork.embedUrl] 那一页）里的播放地址替换 [video] 的地址。
     *
     * 取不到可信地址时返回 `null`，调用方保持原样 —— 「换源失败」不该把原本
     * 还能用的结果变成错误。
     */
    fun videoWithMediaFrom(video: HanimeVideo, html: String): HanimeVideo? {
        val hls = mediaDefinitions(html).filter { it.isHls && it.url.isNotBlank() && it.hasTimeWindow }
        if (hls.isEmpty()) return null
        return video.copy(videoUrls = buildVideoUrls(hls))
    }

    private fun mediaDefinitionArray(body: String): JSONArray? {
        FLASHVARS.find(body)?.let { marker ->
            val json = extractJsonObject(body, marker.range.last + 1)
            if (json != null) {
                runCatching { JSONObject(json).optJSONArray(MEDIA_DEFINITIONS) }
                    .getOrNull()?.let { return it }
            }
        }
        // embed 页那条路：直接找键名，取它后面的数组。
        val key = body.indexOf("\"$MEDIA_DEFINITIONS\"")
        if (key < 0) return null
        val json = extractJsonArray(body, key + MEDIA_DEFINITIONS.length + 2) ?: return null
        return runCatching { JSONArray(json) }.getOrNull()
    }

    private const val MEDIA_DEFINITIONS = "mediaDefinitions"

    private val FLASHVARS = Regex("""var\s+flashvars_\d+\s*=\s*""")

    /**
     * 从 [from] 起找到第一个 `{`，返回与它配对的那个 `}` 为止的子串（含两端）。
     *
     * 括号必须按「字符串内不计」来数：这个对象里全是 URL 与转义引号，
     * 不区分字符串状态的话，`"…{…}"` 里出现一个 `{` 就会把深度算歪。
     */
    private fun extractJsonObject(text: String, from: Int): String? =
        extractJsonBlock(text, from, '{', '}')

    /** 与 [extractJsonObject] 同一套逻辑，括号换成 `[]`（`mediaDefinitions` 是数组）。 */
    private fun extractJsonArray(text: String, from: Int): String? =
        extractJsonBlock(text, from, '[', ']')

    private fun extractJsonBlock(text: String, from: Int, open: Char, close: Char): String? {
        val start = text.indexOf(open, from.coerceAtLeast(0))
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * 把各档清晰度整理成播放器要的「清晰度 → 地址」表。
     *
     * 站点给的是**每档一个独立的 HLS 主列表**（`480P_2000K_….mp4/master.m3u8`），
     * **没有**一条包含全部码位的自适应主列表，所以这里的「自动」不是真的自适应 ——
     * 它被定义成 480P。
     *
     * 为什么是 480P：播放器在「记住的偏好不存在」时**退回列表最后一项**
     * （见 `ComposePlaybackController.load`），也就是最后一项就是首次播放的默认档；
     * 而这条链路要经过自建中转，实测本机 → VPS 约 2.4 Mbps，
     * 480P 需要 1.6 Mbps 有余量，720P 的 2.7 Mbps 已经在临界点上。
     * 档位菜单里仍然如实标出 1080P / 720P / 480P / 240P，用户想冲高自己选。
     */
    private fun buildVideoUrls(hls: List<PhMedia>): ResolutionLinkMap {
        val ordered = hls.distinctBy { it.url }.sortedByDescending { it.resolvedHeight() }
        val map = linkedMapOf<String, HanimeLink>()

        ordered.forEach { media ->
            val height = media.resolvedHeight()
            val label = when {
                height > 0 -> "${height}P"
                media.quality.isNotEmpty() -> "${media.quality}P"
                else -> "默认"
            }
            map.putIfAbsent(label, HanimeLink(media.url, HLS_SUBTYPE))
        }

        if (map.isNotEmpty()) {
            val auto = map["${DEFAULT_HEIGHT}P"] ?: map.values.last()
            map[AUTO_LABEL] = HanimeLink(auto.link, HLS_SUBTYPE)
        }
        return map
    }

    /**
     * nJAV 与 Pornhub 给的都是 TS 分片，这里也一样。
     *
     * [HanimeLink.subtype] = `mp2t` 让 [HanimeLink.suffix] 返回 `ts`，
     * 下载文件就叫 `…_480P.ts`。**这不是可有可无的**：真实字节是 TS，
     * 挂个 `.mp4` 后缀会被部分播放器按容器名去解析而失败。
     * （实测本工程的 HLS 下载判定走的是「地址是不是 `.m3u8`」，与此无关，
     * 但如果哪天改成按后缀判，这里就是关键。）
     */
    private const val HLS_SUBTYPE = "mp2t"

    /** 「自动」档位名，与 hanime / nJAV 两套保持同一个词，方便界面统一处理。 */
    private const val AUTO_LABEL = "自动"

    /** 「自动」实际指向的高度，理由见 [buildVideoUrls]。 */
    private const val DEFAULT_HEIGHT = 480

    //</editor-fold>

    //<editor-fold desc="作者页（/pornstar|/model/<slug>/videos）">

    /**
     * 解析 Pornhub 的**作者页**：`/{pornstar|model}/<slug>/videos?page=N`。
     *
     * ## 为什么这里必须抓 HTML（而不是像列表那样走 JSON 接口）
     *
     * `/webmasters/search` 只支持「按关键词搜」，**没有**「按作者筛」的参数：
     * 实测 `stars[]=<slug>` 是当**模糊文本**处理的（`stars[]=riley-reid` 会同时返回
     * Riley Nixon / Ryan Reid / Audrey Reid……），拿它当作者过滤器会混进一堆别人。
     * 而作者页是站点自己维护的权威列表，还带真分页 —— 代价是 1.2 MB/页。
     *
     * ## 资料头与作品列表在**同一个** HTML 里
     *
     * 作者页把 `section.topProfileHeader`（头像 / 封面 / 关注者 / 观看总量）和
     * `ul#mostRecentVideosSection`（本页作品）放在一起，所以**一次请求拿两样**，
     * 只有第一页解析资料头（[page] <= 1），后续页返回 null 让界面沿用第一页那份。
     *
     * ## 翻页判据
     *
     * 站点在 `<head>` 里给 `<link rel="next" href="…?page=N+1">`（是 link 不是 a，
     * 和 nJAV 的 `a[rel=next]` 不同）——两个都认，别只写一个。
     */
    fun artistPage(body: String, page: Int): PageLoadingState<ArtistVideosPage> {
        val doc = Jsoup.parse(body)
        val videos = artistVideoList(doc)
        val profile = if (page <= 1) artistProfile(doc) else null
        // ⭐ 26.9.4：把「站点自己说还有没有下一页」原样带上去 —— 它是「下一页」按钮
        // 最可靠的闸门（比「作品数 ÷ 一页条数」这种估算值可靠：估算偏小会让用户
        // 以为已经到底了，正是「最后的作品翻不到」那类投诉的来源）。
        val hasNext = hasArtistNextPage(doc)
        return when {
            videos.isNotEmpty() -> PageLoadingState.Success(
                ArtistVideosPage(profile, videos, hasNext = hasNext)
            )

            hasNext -> PageLoadingState.Success(
                ArtistVideosPage(profile, videos, hasNext = true)
            )

            else -> PageLoadingState.NoMoreData
        }
    }

    /**
     * 作者页里的作品卡片。
     *
     * 列表容器**必须**限定在 `#mostRecentVideosSection`（没有才退到 `div.videoUList`）：
     * 同一个页面上还有 `#hottestMenuSection`（另一个标签页的预载数据）和一堆推荐位，
     * 全局取 `li.pcVideoListItem` 会把它们一起收进来 —— 表现就是「作者的作品里混着别人的片」。
     */
    fun artistVideoList(doc: org.jsoup.nodes.Document): MutableList<HanimeInfo> =
        videoItemsFrom(
            doc.select("#mostRecentVideosSection li.pcVideoListItem")
                .ifEmpty { doc.select("div.videoUList li.pcVideoListItem") }
                .ifEmpty { doc.select("li.pcVideoListItem") }
        )

    /**
     * ⭐ **站点自己的「推荐」列表** —— `/recommended` 页（26.9.5 新增）。
     *
     * 用户要的是「它自己的首页推荐」，而不是又一个排序：`最新 / 最多观看 / 本周热门`
     * 这些都是**同一个检索接口换参数**（见 [PhNetwork.HOME_SECTIONS]），
     * 而 `/recommended` 是站点推荐引擎吐出来的另一套东西（响应头里带
     * `x-dd-experiments: {video_recommendation: …}`，首页导航里也挂着
     * `Recommended Videos` → `/recommended`）。
     *
     * 实测（2026-09-15）：
     * - 容器 `ul#recommendedListings`，21 条/页，卡片结构与作者页**完全一致**
     *   （`data-video-vkey` / `span.title a` / `var.duration` / `div.videoDetailBlock span.views var`），
     *   所以解析直接复用 [videoItemsFrom]。
     * - 与「最多观看」/「最新」是**另一批片子**的意义上成立：`/recommended` 的第 1 页与
     *   第 2 页零重合，翻页有效（页面上能看到 18+ 页）。
     * - **越界的页码返回 404**（实测 `?page=999`），所以调用方必须把
     *   「第 2 页起的 404」当成「到底了」，否则滚到底会弹错误
     *   —— 这正是 26.9.4 用户报的「末页 404」的同一类坑，见 [recommendedState]。
     *
     * ⚠️ 容器**不要**退到全局 `li.pcVideoListItem`：这一页还有
     * `recommendedPornstarsWrapper` / `recommendedCategoriesWrapper` 两个区块，
     * 它们自有分页器，混进来会让「还有没有下一页」判错（见 [hasRecommendedNextPage]）。
     */
    fun recommendedList(body: String): MutableList<HanimeInfo> {
        val doc = Jsoup.parse(body)
        val scoped = doc.select("#recommendedListings li.pcVideoListItem")
        if (scoped.isNotEmpty()) return videoItemsFrom(scoped)
        // 站点改版时的兜底：只在**确实没有**这个容器时才用整页卡片，
        // 免得把旁边两个推荐区块的卡片混进作品列表。
        return if (doc.selectFirst("#recommendedListings") == null) {
            videoItemsFrom(doc.select("div.recommendedVideosContainer li.pcVideoListItem"))
        } else {
            mutableListOf()
        }
    }

    /**
     * 推荐页的分页结果。
     *
     * 判据只有一条：**这一页有卡片就有下一页，没有就到底**。
     *
     * 为什么不看站点自己的页码条：`PageLoadingState` 没有「还有下一页」这个位，
     * 列表页只认 `NoMoreData`（见 `SearchScreen` 的 `canLoadMore`）。所以在这里
     * 返回 `Success(空列表)` 的后果是**列表页继续保持「可以再翻」的状态**，
     * 用户每滚一下就会白发一次请求 —— 换来的是一个从未观察到的场景
     * （有效页码却渲染出空容器）。不值得。
     *
     * ⚠️ 真正会遇到的「到底」是**越界页码返回 404**，那一段在
     * [io.github.daisukikaffuchino.han1meviewer.logic.NetworkRepo.phListFlow]
     * 里翻译成 `NoMoreData`（这里根本收不到这种响应）。
     */
    fun recommendedState(body: String): PageLoadingState<MutableList<HanimeInfo>> {
        val list = recommendedList(body)
        return if (list.isNotEmpty()) PageLoadingState.Success(list)
        else PageLoadingState.NoMoreData
    }

    /**
     * 站点**主页**「热门色情视频」那一节的正文（26.9.7）。
     *
     * ## 它是什么
     *
     * 主页上那个带红点的标题「热门色情视频」（英文站 `Hot Porn Videos`）底下的大网格。
     * 红点实测是标题里的 `<i class="roundFlagIcon round-flag-int">`，纯装饰。
     *
     * ## 为什么必须限定容器
     *
     * ⚠️⚠️ 主页整页有 **65** 个 `li.pcVideoListItem`，而 `#singleFeedSection` 里只有 **61** 个。
     * 多出来的在**页头导航的下拉推荐位**（`ul#hottestMenuSection` 等）——
     * 这正是 26.9.5 在 `/recommended` 上踩过的同一个坑（那次是 25 vs 21）。
     * **全局取会把页头预载的另一批混进来。**
     *
     * ## 卡片标记与推荐页/作者页同源
     *
     * `li.pcVideoListItem` + `data-video-vkey` + `span.title a[title]` +
     * `img[src]`/`img[data-mediumthumb]` + `var.duration` + `div.videoDetailBlock span.views var`
     * ⇒ 直接复用 [videoItemsFrom]（三处共用一份，站点改标记不会只修好一边）。
     *
     * ⚠️ 两处**看似不同但与选择器无关**的写法，别被误导去改选择器：
     * - 时长是 `<var class="bgShadeEffect duration tooltipTrig" data-title="视频时长">3:26</var>`
     *   —— class 有多个，但 CSS `var.duration` 是「含该 class 即可」，照样命中；
     * - 观看数是 `<span class="views"><i …></i><var>3M</var></span>`
     *   —— `<i>` 夹在中间，但 `div.videoDetailBlock span.views var` 是**后代**选择器，也命中。
     *
     * ⚠️ 拿不到 `viewkey` 的卡片（**广告卡 `li.sniperModeEngaged`** 就没有）会被
     * [videoItemsFrom] 直接丢掉，不需要另外滤。
     *
     * ## 它**不能翻页**
     *
     * 主页没有分页参数、也没有加载更多接口，所以这里的返回值就是全部（实测 61 条）。
     * 调用方按批切片轮换即可，别去找「第 2 页」。
     */
    fun homepageHotList(body: String): MutableList<HanimeInfo> {
        val doc = Jsoup.parse(body)
        val scoped = doc.select("#singleFeedSection li.pcVideoListItem")
        if (scoped.isNotEmpty()) return videoItemsFrom(scoped)
        // 站点改版兜底：容器没了才退回整页取（把「有没有容器」和「容器里有没有卡片」
        // 分开判，否则容器在但空的时候会误退到全局，把页头那批混进来）。
        return if (doc.selectFirst("#singleFeedSection") == null) {
            videoItemsFrom(doc.select("ul.full-row-thumbs li.pcVideoListItem"))
        } else {
            mutableListOf()
        }
    }

    /**
     * 把一批 `li.pcVideoListItem` 卡片解析成 [HanimeInfo]。
     *
     * 作者页与推荐页用的是**同一套卡片标记**，所以两处共用这一个函数 ——
     * 站点改一处标记时不会只修好一边。
     *
     * ⚠️ 解析失败（拿不到 `viewkey`）的卡片**直接丢掉**，不要塞一个空壳进去：
     * 界面上会出现点不开的封面。
     */
    private fun videoItemsFrom(items: List<Element>): MutableList<HanimeInfo> {
        val result = LinkedHashMap<String, HanimeInfo>()
        items.forEach { item ->
            val anchor = item.selectFirst("a[href*=viewkey]")
            val code = item.attr("data-video-vkey").trim().takeIf { it.isNotEmpty() }
                ?: PhNetwork.videoIdFrom(anchor?.attr("href"))
                ?: return@forEach
            val title = item.selectFirst("span.title a")?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
                ?: item.selectFirst("span.title a")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: anchor?.attr("title")?.trim()?.takeIf { it.isNotEmpty() }
                ?: code
            val img = item.selectFirst("img")
            val cover = listOf("src", "data-mediumthumb", "data-src", "data-thumb_url")
                .firstNotNullOfOrNull { key -> img?.attr(key)?.trim()?.takeIf { it.isNotEmpty() } }
                .orEmpty()
            val duration = item.selectFirst("var.duration")?.text()?.trim()
                ?.takeIf { DURATION_TEXT.matches(it) }
            val views = item.selectFirst("div.videoDetailBlock span.views var")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
            result.putIfAbsent(
                code,
                HanimeInfo(
                    title = title,
                    coverUrl = cover,
                    videoCode = code,
                    duration = duration,
                    views = views,
                    itemType = HanimeInfo.NORMAL,
                )
            )
        }
        return result.values.toMutableList()
    }

    /** 作者页的 `rel="next"`：站点给的是 `<link rel="next">`，nJAV 那边是 `<a rel="next">`。 */
    private fun hasArtistNextPage(doc: org.jsoup.nodes.Document): Boolean =
        doc.selectFirst("link[rel=next]") != null || doc.selectFirst("a[rel=next]") != null

    /**
     * 作者页头部的资料。
     *
     * 选择器全部取自 `section.topProfileHeader`（实测 2026-09-13，`/pornstar/tru-kait/videos`）：
     *
     * | 字段 | 选择器 |
     * |---|---|
     * | 名字 | `h1[itemprop=name]` |
     * | 头像 | `#avatarPicture img` |
     * | 封面 | `#coverPictureDefault` |
     * | 观看总量 | `div.infoBox.videoViews span.big` |
     * | 关注者 | `div.infoBox.subscribers span.big` |
     *
     * ⚠️ **作品数不在这里**（`infoBoxes` 只有排名 / 观看量 / 关注者），所以它继续用
     * 详情页带过来的「87 Videos」原文案 —— 别在这页上硬凑一个数字出来。
     */
    private fun artistProfile(doc: org.jsoup.nodes.Document): ArtistProfile? {
        val header = doc.selectFirst("section.topProfileHeader") ?: return null
        fun img(selector: String): String = header.selectFirst(selector)?.let { el ->
            listOf("src", "data-src").firstNotNullOfOrNull { key ->
                el.attr(key).trim().takeIf { s -> s.isNotEmpty() }
            }
        }.orEmpty()
        val profile = ArtistProfile(
            name = header.selectFirst("h1[itemprop=name]")?.text()?.trim().orEmpty(),
            avatarUrl = img("#avatarPicture img"),
            coverUrl = img("#coverPictureDefault"),
            viewCount = header.selectFirst("div.infoBox.videoViews span.big")?.text()?.trim().orEmpty(),
            subscriberCount = header.selectFirst("div.infoBox.subscribers span.big")?.text()?.trim().orEmpty(),
        )
        return profile.takeIf { it.name.isNotEmpty() || it.avatarUrl.isNotEmpty() }
    }

    //</editor-fold>

    /**
     * Pornhub 没有 hanime 那种「本月新番预告」页，日历里也就没有对应的月度数据。
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
