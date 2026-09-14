package io.github.daisukikaffuchino.han1meviewer.logic.njav

import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxyAuthenticator
import io.github.daisukikaffuchino.han1meviewer.logic.network.HCookieJar
import io.github.daisukikaffuchino.han1meviewer.logic.network.HDns
import io.github.daisukikaffuchino.han1meviewer.logic.network.HProxySelector
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.UserAgentInterceptor
import io.github.daisukikaffuchino.han1meviewer.logic.network.interceptor.UrlLoggingInterceptor
import io.github.daisukikaffuchino.utils.unsafeLazy
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Retrofit
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * nJAV（njavtv.com）站点入口。
 *
 * 这里**没有**复用 hanime 的 [io.github.daisukikaffuchino.han1meviewer.logic.network.ServiceCreator.hClient]：
 * 那条链路挂了 hanime 专用的 Cloudflare 挑战处理与 Getchu 相关拦截器，对 nJAV
 * 只会帮倒忙。但用户配置的**代理 / DoH / 自定义 DNS / UA** 仍然要继承，
 * 所以照抄了同样的 `HProxySelector` + `HDns` + [UserAgentInterceptor] 组合。
 *
 * ⚠️ nJAV 与 missav 是同一家（详情页的防盗链脚本里就列着 missav.ws / missav.ai），
 * 视频统一放在 `surrit.com`，**必须带 Referer 才能拉 m3u8**，见 [playbackHeadersFor]。
 */
object NjavNetwork {

    const val BASE_URL = "https://njavtv.com/"

    /**
     * nJAV 的简体中文路径前缀。
     *
     * 列表 / 搜索仍在它下面（`/cn/new`、`/cn/search/xxx`），但**详情页已经不在**了 ——
     * 站点改版后详情走裸 slug，见 [detailUrl]。
     */
    const val LOCALE = "cn"

    const val ORIGIN = "https://njavtv.com"
    const val REFERER = "https://njavtv.com/"

    val homeUrl: String get() = BASE_URL + LOCALE

    fun listUrl(path: String): String = "$BASE_URL$LOCALE/" + path.trim('/')

    fun searchUrl(keyword: String, page: Int): String {
        val base = "$BASE_URL$LOCALE/search/${URLEncoder.encode(keyword, "UTF-8")}"
        return if (page <= 1) base else "$base?page=$page"
    }

    /** 分类页的第 n 页（第 1 页不带参数）。 */
    fun listUrl(path: String, page: Int): String {
        val base = listUrl(path)
        return if (page <= 1) base else "$base?page=$page"
    }

    /**
     * 女优索引页 `/cn/actresses` 的第 n 页。
     *
     * ⚠️ 单数 `/cn/actress` 是 404，站点用的是复数。
     * 站点**不支持按名字检索女优**（`?q=` / `?keyword=` / `?name=` 实测都被忽略，
     * 只有 `?page` / `?sort` / `?height` / `?cup` / `?age` / `?debut` 生效），
     * 所以名字过滤只能由 UI 在已加载的条目上做。
     */
    fun actressIndexUrl(page: Int, sort: String? = null): String {
        val base = listUrl(ACTRESSES_SEGMENT, page)
        val value = sort?.takeIf { it.isNotBlank() } ?: return base
        val separator = if (base.contains('?')) "&" else "?"
        return "$base${separator}sort=$value"
    }

    /**
     * 女优一览页的排序取值（26.8.2）。
     *
     * | 菜单 | 取值 |
     * |---|---|
     * | 影片（默认） | [ACTRESS_SORT_VIDEOS] |
     * | 出道 | [ACTRESS_SORT_DEBUT] |
     */
    const val ACTRESS_SORT_VIDEOS = "videos"
    const val ACTRESS_SORT_DEBUT = "debut"

    /**
     * **女优排行**页 `/cn/actresses/ranking`（26.8.2）。
     *
     * 站点只给「当月」一份榜（页面上没有周期切换链接，只有语言变体），
     * H1 形如 `女优排行 SEP 2026`，100 条、带 `第 N 名` 角标。
     * 卡片结构与 [actressIndexUrl] 完全同构，所以解析共用
     * [NjavParser.actressList]，只有周期标题要另外抠（[NjavParser.actressRankingPeriod]）。
     *
     * ⚠️ 这个地址**没有 `?page=`**：排行就是固定的一页 100 条。
     */
    fun actressRankingUrl(): String = listUrl("$ACTRESSES_SEGMENT/ranking")

    /**
     * 某位女优的影片列表页 `/cn/actresses/<编码后的名字>` 的第 n 页。
     *
     * 站点会把裸地址 301 到带随机数字前缀的 `/dm###/cn/actresses/…`——**那个前缀会变**
     * （`/dm539/` 自己都能 301 到别处），所以这里只拼裸地址、让 OkHttp 跟跳转，
     * 与 [detailUrl] 是同一套思路。实测裸地址与 `?page=N` 都能正常 200。
     *
     * @param path 形如 `actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3`，
     *   由 [io.github.daisukikaffuchino.han1meviewer.logic.model.NjavActress.path] 提供。
     */
    fun actressUrl(path: String, page: Int): String = listUrl(path.trim('/'), page)

    /**
     * 女优页带**排序/筛选**的地址（26.8）。
     *
     * 取值全部来自站点自己的下拉菜单（2026-09-14 从女优页锚点里读出来的）：
     *
     * | 菜单 | 取值 |
     * |---|---|
     * | 排序 | `released_at`（发行日期）/ `published_at`（最近更新）/ `saved`（收藏数）/ `today_views` / `weekly_views` / `monthly_views` / `views`（总浏览数）|
     * | 筛选 | `individual`（单人作品）/ `multiple`（多人作品）/ `chinese-subtitle`（中文字幕）；「所有」= 不传 |
     *
     * ⚠️ 两个参数都是站点原生 query（`?sort=` / `?filters=`），不是我们编的 —— 站点改名前
     * 这套下拉就是死的，所以值只在 [NjavSort] / [NjavFilter] 里定义一次。
     */
    fun actressUrl(path: String, page: Int, sort: String?, filter: String?): String {
        val base = listUrl(path.trim('/'), page)
        val extras = buildList {
            sort?.takeIf { it.isNotBlank() }?.let { add("sort=" + it) }
            filter?.takeIf { it.isNotBlank() }?.let { add("filters=" + it) }
        }
        if (extras.isEmpty()) return base
        val separator = if (base.contains('?')) "&" else "?"
        return base + separator + extras.joinToString("&")
    }

    /**
     * 从女优链接里抠出 `actresses/<编码后的名字>` 这一段（[actressUrl] 要的形态）。
     *
     * 输入可能是三种写法，全部要能吃下：
     *
     * ```
     * https://njavtv.com/actresses/%E6%8C%81%E9%87%8E%E8%93%AC        ← 详情页给的（主流）
     * https://njavtv.com/dm288/cn/actresses/%E6%B3%A2%E5%A4%9A%E9%87%8E%E7%B5%90%E8%A1%A3  ← 索引页给的，前缀会变
     * /actresses/xxx                                                  ← 相对写法
     * ```
     *
     * ⚠️ **只抄路径尾段、绝不按显示名重新编码**：站点自己给的编码里繁简与
     * 特殊字符都可能是对的，重编一次就会 404（与女优索引「href 繁体、h4 简体」同一个坑）。
     * 抠不到返回 null，调用方退回按名字搜索。
     */
    fun actressPathFrom(url: String): String? {
        val raw = url.trim()
        if (raw.isEmpty()) return null
        val marker = "/actresses/"
        val index = raw.indexOf(marker)
        if (index < 0) return null
        val tail = raw.substring(index + marker.length)
            .substringBefore('?').substringBefore('#').trim('/')
        if (tail.isEmpty() || tail in ACTRESS_RESERVED_PATHS) return null
        return "$ACTRESSES_SEGMENT/$tail"
    }

    /** 不是具体某个人、而是一个榜单/分类的保留路径。 */
    private val ACTRESS_RESERVED_PATHS = setOf("ranking", "genres")

    private const val ACTRESSES_SEGMENT = "actresses"

    /**
     * 详情页地址：**裸 slug**，不带 `/cn/` 语言前缀。
     *
     * ⚠️ 2026-09 站点改版，旧的 `/cn/{slug}` 形式已经废了。请求它会吃到 301，
     * 而且落点不是详情页、是「最近更新」列表页：
     *
     * ```
     * GET https://njavtv.com/cn/scop-715
     *   → 301 Location: https://njavtv.com/dm539/cn/new      ← 列表页！
     * ```
     *
     * 于是 [NjavParser.video] 拿到的其实是一张列表页：`og:title` 抠不到、
     * [NjavPacker.extractM3u8] 也抠不到任何 m3u8，最后抛
     * `ParseException("nJAV：未能解析播放地址")`，UI 侧表现为「点进去播不了」
     * 并跳浏览器。**这就是 nJAV 详情页打不开的真凶**。
     *
     * 站内卡片现在给出的是不带语言前缀的规范地址（`https://njavtv.com/scop-715`），
     * 少数还带一层随机数字前缀（`https://njavtv.com/dm75/waaa-214`）——那个前缀
     * 是会变的（`/dm539/` 自己 301 到 `/dm339`），**不能照抄**，所以统一走裸 slug。
     * 实测 `GET https://njavtv.com/<slug>` → 200，且能正常解出 `playlist.m3u8`。
     */
    fun detailUrl(slug: String): String {
        val value = slug.trim()
        // 万一是外面传进来的绝对地址（分享链接 / 历史记录），原样放行。
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            return value
        }
        return BASE_URL + value.trimStart('/')
    }

    /** 播放与下载共用的防盗链域名，包含其子域名。 */
    private val PROTECTED_HOSTS = listOf("surrit.com", "fourhoi.com")

    /**
     * nJAV 的视频 CDN 有防盗链：不带 Referer 直接 403（Cloudflare）。
     * 播放器（含 HLS 的每个分片）与**下载 Worker** 都要把这组头带上，
     * 否则会「能解析出地址但播不了 / 一片也下不动」。
     *
     * ⚠️ 别只判 `surrit.com`：`fourhoi.com` 是同一套防盗链下的另一个域名，
     * 判漏了就会在换域名时变成「清一色 403」——而 403 的表现恰好是
     * 「有地址、有分片数，但一个字节都下不来」。
     */
    fun playbackHeadersFor(url: String): Map<String, String> {
        val host = url.toHttpUrlOrNull()?.host ?: return emptyMap()
        return if (PROTECTED_HOSTS.any { host == it || host.endsWith(".$it") }) {
            mapOf("Referer" to REFERER, "Origin" to ORIGIN)
        } else {
            emptyMap()
        }
    }

    private val dns = HDns()

    private val client: OkHttpClient by unsafeLazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(UserAgentInterceptor)
            .addInterceptor(UrlLoggingInterceptor())
            .cookieJar(HCookieJar())
            .proxySelector(HProxySelector())
            .proxyAuthenticator(HProxyAuthenticator.http)
            .dns(dns)
            .build()
    }

    val service: NjavService by unsafeLazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .build()
            .create(NjavService::class.java)
    }
}
