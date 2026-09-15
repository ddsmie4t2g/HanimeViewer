package io.github.daisukikaffuchino.han1meviewer.ui.viewmodel

/**
 * 作者页分页条的**纯数学** —— 不碰网络、不碰状态流，所以能被单测钉死。
 *
 * ## ⭐⭐ 26.9.4：一页 = **站点自己的一页**
 *
 * 26.8 ~ 26.9.3 的做法是「应用内固定 12 条一页」（2 列 × 6 行），再拿
 * 「站点公布的作品数 ÷ 12」倒推总页数。用户报的三个毛病全是从这里长出来的：
 *
 * | 现象 | 根因 |
 * |---|---|
 * | 「最后的 6 个怎么都翻不到，显示 404」 | 12 条一页的边界**不落在站点分页边界上**：要凑满第 N 页的最后 12 条，得去要站点那边**下一页**，而那一页已经不存在 ⇒ 站点 404 ⇒ 整个第 N 页报错 |
 * | 「有些作品遗失」 | 同上，只是换个方向：估算出的总页数比真实少，末页干脆没入口 |
 * | 「点第 34 页等于从第 1 页一路翻到 34 页」 | 应用页与站点页**没有对应关系**，只能把中间每一页都拉回来才知道第 34 页装的是哪 12 条 |
 *
 * 现在不再做这个换算：**站点的一页就是应用的一页**。
 *
 * - 跳到第 N 页 = **要站点第 N 页**，一次请求（[ArtistViewModel.goToPage]），
 *   不像以前那样把 1..N-1 全拉一遍；
 * - 一页装多少条由**站点**决定（hanime 41/59、Pornhub 40–49、nJAV 视站点而定），
 *   所以「末页不满」是天经地义的，不会再有「不足 12 条 = 视为 404」这回事；
 * - 一页的内容就是站点那一页的内容，**不可能漏掉也不可能重复**；
 * - 总页数也改用站点口径：站点自己说的总页数（hanime 的 Laravel 页码条、
 *   nJAV 女优页的页码条）优先，其次才是「作品数 ÷ 实测站点一页条数」。
 *
 * 拿不到任何总数时按「已经翻到过的最大页」往上长（这就是「动态调整」）。
 *
 * @param page 当前页（1 起）
 * @param totalPages 总页数：站点口径优先，拿不到就按已翻到的页数长
 * @param canPrev / [canNext] 站点那边还有没有上一页 / 下一页
 * @param numbers 分页条要画的页码；`null` 表示省略号。见 [strip]
 */
internal data class PagerVerdict(
    val page: Int,
    val totalPages: Int,
    val canPrev: Boolean,
    val canNext: Boolean,
    val numbers: List<Int?>,
)

internal object ArtistPaging {

    /**
     * 分页条**初始**画几个数字（`1..STRIP_INITIAL`）。
     *
     * ⚠️ 这个数与 [STRIP_WINDOW] 一起受**屏宽**约束：一行里还有 `‹` / `›` 两个箭头，
     * 一共 8 个 32dp 的圆 + 间距 ≈ 300dp。再多就会在 360dp 的机器上被裁掉。
     */
    const val STRIP_INITIAL = 6

    /**
     * 一次**展开**几页 —— 也是「当前页右侧永远留几格」。
     *
     * 用户原话：**「最好是只到 6，然后点了 6 之后会展开 7、8、9 三页」**。
     */
    const val STRIP_STEP = 3

    /** 页码条滑到中间时，一次画几个数字（左边留一个 `1 …` 锚点，合计 6 格）。 */
    private const val STRIP_WINDOW = 4

    /**
     * 分页条的结论。
     *
     * @param maxLoadedPage 已经**拿到过内容**的最大站点页（一页都没拿到时传 0）
     * @param knownTotalPages 站点自己说的总页数（或由作品数 ÷ 站点一页条数算出的）；拿不到传 null
     * @param hasNext 站点那边**还可能有下一页**（见 [ArtistViewModel.hasNextAfter]）——
     *   ⚠️ 这是「能不能前进」的**唯一**闸门，**不要**再去和总页数比较：
     *   总数一旦是估算值，拿它当闸门就会在末页锁死自己（26.9.0~26.9.1 的「最多十页」）。
     * @param page 已经确认有内容的那一页（调用方负责夹好，这里只兜个底）
     */
    fun resolve(
        page: Int,
        maxLoadedPage: Int,
        knownTotalPages: Int?,
        hasNext: Boolean,
    ): PagerVerdict {
        val safePage = maxOf(page, 1)
        val loaded = maxOf(maxLoadedPage, safePage)
        val known = knownTotalPages?.takeIf { it > 0 }
        // 站点已经确认没有了 ⇒ 总页数只能按「实际翻到过几页」算，不把用户送进空白页；
        // 还有 ⇒ 站点公布的总数优先（第一页进来就能一次画准），拿不到就跟着翻页往上长。
        val totalPages = if (hasNext) maxOf(known ?: 0, loaded) else loaded
        return PagerVerdict(
            page = safePage,
            totalPages = totalPages,
            canPrev = safePage > 1,
            // ⭐ 与 totalPages 彻底解耦：只看站点那边还有没有下一页（见 hasNext 的说明）。
            canNext = hasNext,
            numbers = strip(safePage, totalPages),
        )
    }

    /**
     * 分页条要画哪几个页码（`null` = 省略号）。
     *
     * ## 为什么不是「1 2 3 4 … 34」
     *
     * 26.9.0~26.9.3 画的是「首页 + 当前页附近 + 末页」，用户很不满意：
     * **「跨度太大，最好是只到 6，然后点了 6 之后会展开 7、8、9 三页」**。
     * 末页那个锚点还会被误读成「点它就直接跳到最后一页」，而它其实只是个数字。
     *
     * 现在改成**跟着当前页长**：右端永远至少画到「当前页 + [STRIP_STEP]」，且至少画到第
     * [STRIP_INITIAL] 个。于是
     *
     * | 当前页 | 画出来 |
     * |---|---|
     * | 1 | `1 2 3 4 5 6`（点 6 = 翻到第 6 页，同时右端长到 9） |
     * | 6 | `1 … 6 7 8 9`（7/8/9 就是被「展开」出来的三页） |
     * | 9 | `1 … 9 10 11 12` |
     * | 34（共 34 页） | `1 … 31 32 33 34` |
     *
     * 左边太长时收成 `1 …` + 4 个数字（[STRIP_WINDOW]），一行最多 6 格 —— 页数再多也不会把
     * 这一行撑爆，也**不会**出现「一下跨到末页」的跳变。
     *
     * @param page 当前页
     * @param totalPages 总页数
     */
    fun strip(page: Int, totalPages: Int): List<Int?> {
        if (totalPages <= 1) return emptyList()
        val head = STRIP_INITIAL
        if (totalPages <= head) return (1..totalPages).toList()
        // 右端：至少画出「当前页 + 3」，而且第一屏至少画到第 6 个（用户说的「只到 6」）。
        val end = minOf(totalPages, maxOf(page + STRIP_STEP, head))
        // 还在开头附近（end 不超过第一屏）就老老实实从 1 画起，不要为了省格子去藏 2、3。
        val start = if (end <= head) 1 else maxOf(1, end - (STRIP_WINDOW - 1))
        return buildList {
            if (start > 1) {
                add(1)
                add(null)
            }
            for (p in start..end) add(p)
        }
    }

    /**
     * 站点文案里的「共 N 部影片」→ **站点页数**；解析不出来就返回 null。
     *
     * ⚠️ 除数必须是**实测的站点一页条数**，不是应用内一页的条数（26.9.3 以前这里是
     * 固定的 12，于是「87 部」被算成 8 页，而站点其实只有 2 页 —— 这就是用户报的
     * 「页数做的也不对 / 有些作品遗失」）。站点一页条数由 `ArtistViewModel.sitePageSize`
     * 从**真拉回来的那一页**量出来，所以这个换算只在已经拿到第一页之后才成立；
     * 拿不到就返回 null（调用方退回「按已翻到的页数长」）。
     *
     * ## 两个必须当心的点
     *
     * 1. **必须把千分位与尾巴删干净再转数字**。正则 `(\d[\d,\s.]*)` 是**贪婪**的：
     *    `87 Videos` 抠出来的是 `"87 "`、`5668 部影片` 抠出来的是 `"5668 "`，
     *    而带尾巴的字符串 `toIntOrNull()` **恒为 null**。26.9.0 就是死在这一步 ——
     *    「总页数一次算准」这个功能其实**从来没有生效过**。
     * 2. **`1.2K Videos` 这种简写不能当总数**：反推不出准确条数（1.2K 是 1200 还是 1249？），
     *    宁可当「不知道」，让总页数跟着已翻到的页数往上长。
     */
    fun pagesFromCountText(raw: String?, perSitePage: Int): Int? {
        if (perSitePage <= 0) return null
        val text = raw?.takeIf { it.isNotBlank() } ?: return null
        val match = COUNT_DIGITS.find(text) ?: return null
        val suffix = text.getOrNull(match.range.last + 1)
        if (suffix != null && suffix.uppercaseChar() in COUNT_ABBREVIATIONS) return null
        val digits = match.groupValues[1].filter { it.isDigit() }
        if (digits.isEmpty() || digits.length > MAX_COUNT_DIGITS) return null
        val count = digits.toIntOrNull() ?: return null
        if (count <= 0) return null
        return (count + perSitePage - 1) / perSitePage
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
