package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel

/**
 * 作者页分页条的**纯数学** —— 不碰网络、不碰状态流，所以能被单测钉死。
 *
 * ## 总页数从哪来（两条线，都拿不到就按已加载条数长）
 *
 * 1. **站点公布的作品数**：`87 Videos` / `5668 部影片` ⇒ [pagesFromCountText]。
 *    Pornhub 详情页的主模特块带这句；nJAV 的女优卡片虽然也带，但它那个数是**站点全站**
 *    口径（女优页根本翻不动，见 `ArtistViewModel.knownTotalPages`），所以 nJAV 不认。
 * 2. **站点自己的总页数**：hanime 的合成作者页只有这一条 —— 搜索页是 Laravel 分页，
 *    页码条里写着末页号 ⇒ [pagesFromSitePages]。
 *
 * ## 为什么要把它单独拿出来
 *
 * 用户报的现象很具体：**不管作者还是女优有多少视频，最多只显示十页**。
 * 根因不在取数，而在 26.9.0 那几行判断的**死锁**：
 *
 * ```
 * val displayTotal = maxOf(knownTotalPages() ?: 1, loadedPages)  // 总数拿不到时 = 已加载页数
 * canNext = safePage < displayTotal && (remoteHasMore || …)      // ← 必须「还没到总数」才给翻
 * ```
 *
 * 站点公布的作品总数那条线当时是坏的（[pagesFromCountText] 的注释里有完整说明），
 * 于是总页数**只剩「已加载条数 / 12」这一个来源**。而「下一页」又要求「还没到总页数」——
 * 站在最后一页就再也点不动了：想加载更多必须先点下一页，想点下一页又必须先加载更多。
 *
 * 什么时候正好卡在**十页**？已加载条数正好是应用内一页条数的整数倍时：
 * Pornhub 的作者页实测 40–49 条/站点页，连续拉三批 ≈ 120 条 = 10 页（12 条/页），
 * 用户看到的就是「第 10 / 10 页」，且「下一页」与 `›` 永远变灰 —— 而这位作者其实还有几百部。
 *
 * ## 现在怎么算
 *
 * - [PagerVerdict.totalPages]：站点公布了作品数（或站点自己的页数）就用它（第一页进来就一次算准）；
 *   拿不到就按已加载条数算，翻页时自然往上长。站点那边**已经确认没有了**
 *   （`remoteHasMore == false`）时，按**实际拿到的**算 —— 不把用户送进一个空白页。
 * - [PagerVerdict.canNext]：只看两件事 —— **本地已经攒下了下一页**，或者**站点那边可能还有**。
 *   刻意**不**再和总页数挂钩，死锁从根上没有了。
 * - [PagerVerdict.page]：停在「**已经拿到条数**的那一页」。跳页是异步的
 *   （`ArtistViewModel.loadRemotePage` 会连着补拉好几个站点页），目标页还没攒够时
 *   宁可停在原地，也不能先画一个空页出来。
 *
 * @param pageSize 应用内一页几条（`ArtistViewModel.PAGE_SIZE`，12）。
 */
internal data class PagerVerdict(
    val page: Int,
    val totalPages: Int,
    val canPrev: Boolean,
    val canNext: Boolean,
)

internal object ArtistPaging {

    /**
     * 分页条要画成什么样。
     *
     * @param requested 用户想去第几页（点页码 / 上一页下一页 / 首屏都走这一条）
     * @param loadedCount 已经从站点累积下来的条数（跨多个站点页）
     * @param knownTotalPages 由站点公布的作品数算出的总页数；拿不到传 null
     * @param remoteHasMore 站点那边还可能有下一页（内部累积的口径）
     */
    fun resolve(
        requested: Int,
        loadedCount: Int,
        knownTotalPages: Int?,
        remoteHasMore: Boolean,
        pageSize: Int,
    ): PagerVerdict {
        val safeLoaded = maxOf(loadedCount, 0)
        val loadedPages = maxOf(1, (safeLoaded + pageSize - 1) / pageSize)
        val known = knownTotalPages?.takeIf { it > 0 }
        // 总数拿不到就先按「已加载条数」画，翻页时再往上长。
        val displayTotal = maxOf(known ?: 1, loadedPages)
        // 站点已经确认没有了 → 总页数只能按实际拿到的算。
        val totalPages = if (remoteHasMore) displayTotal else loadedPages
        // ⚠️ 只画已经攒够条数的那一页：跳页要连着补拉好几批，中途把用户放到空页上更糟。
        val page = requested.coerceIn(1, maxOf(1, loadedPages))
        return PagerVerdict(
            page = page,
            totalPages = totalPages,
            canPrev = page > 1,
            // ⭐ 本地已经有下一页的条数，或者站点那边可能还有 —— 与 totalPages 无关（死锁就是这么来的）。
            canNext = safeLoaded > page * pageSize || remoteHasMore,
        )
    }

    /**
     * 站点文案里的「共 N 部影片」→ 总页数；解析不出来就返回 null（调用方退回「已加载条数」口径）。
     *
     * ## 两个必须当心的点
     *
     * 1. **必须把千分位与尾巴删干净再转数字**。[COUNT_DIGITS] 是**贪婪**的：
     *    `87 Videos` 抠出来的是 `"87 "`、`5668 部影片` 抠出来的是 `"5668 "`，
     *    而带尾巴的字符串 `toIntOrNull()` **恒为 null**。26.9.0 就是死在这一步 ——
     *    「总页数一次算准」这个功能其实**从来没有生效过**，于是总页数只剩
     *    「已加载条数 / 12」一个来源，才有了用户报的「最多十页」。
     * 2. **`1.2K Videos` 这种简写不能当总数**：反推不出准确条数（1.2K 是 1200 还是 1249？），
     *    宁可当「不知道」，让总页数跟着已加载条数往上长。
     */
    fun pagesFromCountText(raw: String?, pageSize: Int): Int? {
        val text = raw?.takeIf { it.isNotBlank() } ?: return null
        val match = COUNT_DIGITS.find(text) ?: return null
        val suffix = text.getOrNull(match.range.last + 1)
        if (suffix != null && suffix.uppercaseChar() in COUNT_ABBREVIATIONS) return null
        val digits = match.groupValues[1].filter { it.isDigit() }
        if (digits.isEmpty() || digits.length > MAX_COUNT_DIGITS) return null
        val count = digits.toIntOrNull() ?: return null
        if (count <= 0) return null
        return (count + pageSize - 1) / pageSize
    }

    /**
     * 站点那边的**站点页数** → 应用内页数。
     *
     * hanime 的合成作者页头部没有「共 N 部影片」，只有搜索页页码条里的末页号
     * （见 `Parser.hanimeSearchTotalPages`）。要换算成应用内页数，得知道**站点一页几条** ——
     * 站点不给这个数，只能拿**实测**的值（拉回来的站点页里最大的那一页，见
     * `ArtistViewModel.sitePageSize`），所以这是个**上界估计**：
     * 末页通常不满，真实总条数会略少，界面上最多多出末页那一格；
     * 用户点到那儿会被夹回真正的最后一页（站点确认没有之后总页数按实际拿到的算）。
     *
     * 宁可估大也不估小：估小会让「最后一页」看起来已经到了，而其实还有。
     *
     * @param siteTotalPages 站点自己公布的页数；拿不到传 null
     * @param sitePageSize 站点一页实测条数（没拉到过任何一页时传 0 ⇒ 返回 null）
     */
    fun pagesFromSitePages(siteTotalPages: Int?, sitePageSize: Int, pageSize: Int): Int? {
        val sitePages = siteTotalPages?.takeIf { it > 0 } ?: return null
        val perSitePage = sitePageSize.takeIf { it > 0 } ?: return null
        val items = sitePages.toLong() * perSitePage
        return ((items + pageSize - 1) / pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 作品数文案里的第一个数字串（允许 `,` / 空格 / `.` 作千分位分隔符）。
     *
     * ⚠️ 只抽数字串、不猜格式是刻意的：站点文案五花八门（`1,234` / `1234 部影片` /
     * `1234 videos`），去猜格式反而会在站点改文案时出错；抽不出数字就当「不知道」。
     */
    private val COUNT_DIGITS = Regex("""(\d[\d,\s.]*)""")

    /** 数字后面跟这些字母说明是 `1.2K` / `3M` 这类简写，不能当准确条数用。 */
    private val COUNT_ABBREVIATIONS = setOf('K', 'M', 'B')

    /** 超过这么多位的数字不可能是作品数（也顺手避开 Int 溢出）。 */
    private const val MAX_COUNT_DIGITS = 9
}
