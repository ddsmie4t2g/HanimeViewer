package io.github.daisukikaffuchino.han1meviewer.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * hanime 搜索页的「站点总页数」解析（`Parser.hanimeSearchTotalPages`）。
 *
 * 为什么需要它：hanime 的作者页是**合成**的（按名字搜索），头部没有 Pornhub / nJAV
 * 那种「共 N 部影片」文案 —— 没有总页数，分页条就只能「翻一页长一页」。
 * 站点自己的页码条里写着末页号，这里是把它读出来的回归测试。
 *
 * ⚠️ 下面两段 HTML 的**结构与真实快照逐字一致**（`build/tmp/s1.html` / `s20.html`，2026-09-15）：
 * 手机/桌面各一套 `ul.pagination`，页码是 `li.page-item`，**当前页渲染成 `span.page-link`
 * 而不是 `<a>`** —— 只读锚点会在最后一页上小看一页（实测 s20 会得到 19，真值 20）。
 */
class ParserHanimeTotalPagesTest {

    /** 第 1 页那种形态：首尾都画出来（`1 2 3 4 ... 19 20`），当前页 1 是 span。 */
    private val firstPage = """
        <html><body>
        <div class="search-pagination mobile-search-pagination">
          <ul class="pagination" role="navigation">
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=1">1</a></li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=2">2</a></li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=3">3</a></li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=4">4</a></li>
            <li class="page-item disabled" aria-disabled="true"><span class="page-link">...</span></li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=19">19</a></li>
            <li class="page-item active" aria-current="page"><span class="page-link">20</span></li>
          </ul>
        </div>
        </body></html>
    """.trimIndent()

    /** 中间页那种形态：上一页箭头 + 窗口 + 末页。 */
    private val middlePage = """
        <html><body>
          <ul class="pagination" role="navigation">
            <li class="page-item">
              <a class="page-link" href="?query=x&amp;page=9" rel="prev" aria-label="pagination.previous">&lsaquo;</a>
            </li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=1">1</a></li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=2">2</a></li>
            <li class="page-item disabled" aria-disabled="true"><span class="page-link">...</span></li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=9">9</a></li>
            <li class="page-item active" aria-current="page"><span class="page-link">10</span></li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=11">11</a></li>
            <li class="page-item disabled" aria-disabled="true"><span class="page-link">...</span></li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=19">19</a></li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=20">20</a></li>
          </ul>
        </body></html>
    """.trimIndent()

    /**
     * ⭐ **最后一页**那种形态：末页号自己就是当前页 ⇒ 渲染成 `span`。
     *
     * 这条如果红了，说明解析器又只认 `<a>`，hanime 作者页在最后一页上会少算一页。
     */
    private val lastPage = """
        <html><body>
          <ul class="pagination" role="navigation">
            <li class="page-item">
              <a class="page-link" href="?query=x&amp;page=19" rel="prev" aria-label="pagination.previous">&lsaquo;</a>
            </li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=1">1</a></li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=2">2</a></li>
            <li class="page-item disabled" aria-disabled="true"><span class="page-link">...</span></li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=16">16</a></li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=17">17</a></li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=18">18</a></li>
            <li class="page-item"><a class="page-link" href="?query=x&amp;page=19">19</a></li>
            <li class="page-item active" aria-current="page"><span class="page-link">20</span></li>
          </ul>
        </body></html>
    """.trimIndent()

    @Test
    fun firstPageReportsLastPageNumber() {
        assertEquals(20, Parser.hanimeSearchTotalPages(firstPage))
    }

    @Test
    fun middlePageReportsLastPageNumber() {
        assertEquals(20, Parser.hanimeSearchTotalPages(middlePage))
    }

    /** 当前页是末页时，末页号只在 `span.page-link` 里 —— 这条是「只认 `<a>` 会少一页」的回归。 */
    @Test
    fun lastPageStillReportsItself() {
        assertEquals(20, Parser.hanimeSearchTotalPages(lastPage))
    }

    /** 带分类的简化模板 13 页（实测 g1..g13），数字与板块无关，照样读得出来。 */
    @Test
    fun simplifiedTemplateAlsoWorks() {
        val html = """
            <html><body>
              <div class="home-rows-videos-wrapper"></div>
              <ul class="pagination">
                <li class="page-item"><a class="page-link" href="?query=x&amp;genre=y&amp;page=12">12</a></li>
                <li class="page-item active"><span class="page-link">13</span></li>
              </ul>
            </body></html>
        """.trimIndent()
        assertEquals(13, Parser.hanimeSearchTotalPages(html))
    }

    /** 没有页码条 / 页码条里只有省略号与箭头 ⇒「不知道」，调用方退回已加载条数口径。 */
    @Test
    fun missingPaginationIsNull() {
        assertNull(Parser.hanimeSearchTotalPages("<html><body><div>无分页</div></body></html>"))
        assertNull(
            Parser.hanimeSearchTotalPages(
                """<ul class="pagination">
                     <li class="page-item disabled"><span class="page-link">...</span></li>
                     <li class="page-item"><a class="page-link" href="?page=2">&rsaquo;</a></li>
                   </ul>"""
            )
        )
    }
}
