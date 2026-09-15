package io.github.daisukikaffuchino.han1meviewer.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.ui.preview.ComponentPreview
import io.github.daisukikaffuchino.han1meviewer.ui.preview.longText

private const val UrlAnnotationTag = "url"
private val UrlRegex = Regex("""(https?://\S+)""")

/** 行内 `code` 的底色。非 Composable 里拿不到主题色，用一层很淡的灰。 */
private val InlineCodeBackground = Color(0x1F808080)

/**
 * 可展开的富文本。
 *
 * @param text 原文
 * @param modifier 修饰符
 * @param maxCollapsedLines 折叠时最多显示几行
 * @param onLinkClick 点链接的回调；为 null 时用系统浏览器打开
 * @param markdown ⭐ 9.0：按 **markdown 子集**渲染。
 *   以前更新日志里的 `#` / `**加粗**` / `| 表格 |` 是**原样显示**的（用户报的就是这条）。
 * @param showContainer 是否自带一层 [Card] 背景。
 *   更新卡片本身已经是 Card，套两层会多出一圈灰色边框，那里传 false。
 */
@Composable
fun ExpandableRichText(
    text: String,
    modifier: Modifier = Modifier,
    maxCollapsedLines: Int = 4,
    onLinkClick: ((String) -> Unit)? = null,
    markdown: Boolean = false,
    showContainer: Boolean = true,
) {
    if (text.isBlank()) return

    val annotatedText = remember(text, markdown) {
        if (markdown) buildMarkdownAnnotatedString(text) else buildLinkAnnotatedString(text)
    }
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    var expanded by rememberSaveable { mutableStateOf(false) }
    var hasOverflow by remember { mutableStateOf(false) }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val body: @Composable () -> Unit = {
        Column(modifier = if (showContainer) Modifier.padding(12.dp) else Modifier) {
            AnimatedContent(
                targetState = expanded,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "expandable-rich-text",
            ) { isExpanded ->
                SelectionContainer {
                    Text(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = if (isExpanded) Int.MAX_VALUE else maxCollapsedLines,
                        overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            layoutResult = result
                            if (!isExpanded) {
                                hasOverflow = result.hasVisualOverflow
                            }
                        },
                        modifier = Modifier.pointerInput(annotatedText, layoutResult) {
                            detectTapGestures { tapOffset ->
                                val result = layoutResult ?: return@detectTapGestures
                                val offset = result.getOffsetForPosition(tapOffset)
                                annotatedText
                                    .getStringAnnotations(UrlAnnotationTag, offset, offset)
                                    .firstOrNull()
                                    ?.item
                                    ?.let { url ->
                                        if (onLinkClick != null) onLinkClick(url)
                                        else uriHandler.openUri(url)
                                    }
                            }
                        },
                    )
                }
            }
            if (!expanded && hasOverflow) {
                Text(
                    text = stringResource(R.string.expand),
                    color = linkColor,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.End)
                        .clickable { expanded = true }
                        .padding(4.dp)
                )
            }

            if (expanded) {
                Text(
                    text = stringResource(R.string.collapse),
                    color = linkColor,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.End)
                        .clickable { expanded = false }
                        .padding(4.dp)
                )
            }
        }
    }

    if (showContainer) {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            body()
        }
    } else {
        Box(modifier = modifier.fillMaxWidth()) {
            body()
        }
    }
}

private fun buildLinkAnnotatedString(text: String): AnnotatedString {
    return buildAnnotatedString {
        var currentIndex = 0
        UrlRegex.findAll(text).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            if (start > currentIndex) {
                append(text.substring(currentIndex, start))
            }
            val url = match.value
            pushStringAnnotation(tag = UrlAnnotationTag, annotation = url)
            pushStyle(
                SpanStyle(
                    color = Color.Unspecified,
                    textDecoration = TextDecoration.Underline,
                )
            )
            append(url)
            pop()
            pop()
            currentIndex = end
        }
        if (currentIndex < text.length) {
            append(text.substring(currentIndex))
        }
    }
}

// ────────────────────────────────────────────── markdown 子集（9.0）

/** `### 标题` */
private val HeadingRegex = Regex("""^(#{1,6})\s+(.*)$""")

/** `- 项目` / `* 项目` / `+ 项目` */
private val BulletRegex = Regex("""^\s*[-*+]\s+(.*)$""")

/** `> 引用` */
private val QuoteRegex = Regex("""^\s*>\s?(.*)$""")

/** `---` / `***` / `___` 分隔线 */
private val RuleRegex = Regex("""^\s*(?:-{3,}|\*{3,}|_{3,})\s*$""")

/** 表格的分隔行（`|---|---|`），要整行丢掉。 */
private val TableSeparatorRegex = Regex("""^\s*\|?[\s:\-|]+\|[\s:\-|]*$""")

/** 表格的数据行（`| a | b |`）。 */
private val TableRowRegex = Regex("""^\s*\|.*\|\s*$""")

/**
 * 把 **markdown 子集**渲染成 [AnnotatedString]。
 *
 * ## 为什么只做子集
 *
 * 真接一个 markdown 库要引依赖、要处理嵌套与安全；而这里唯一的来源是**我们自己的
 * 更新日志**（`update.json` 的 `updateDescription`），实际用到的东西一共就这几种：
 * 标题、加粗、行内代码、列表、引用、分隔线、链接、表格。**够用就好**，
 * 遇到不认识的语法原样输出（至少不会比现在更差）。
 *
 * ⚠️ 刻意**不做**的：深层嵌套、HTML、图片、脚注。
 * 也**不做** `*斜体*`：它跟「行首 `*` 列表」太容易撞，收益远小于误判风险。
 *
 * ## 表格怎么处理
 *
 * 手机上放不下真表格，所以：分隔行整行丢掉，数据行用 ` · ` 连成一行
 * （`| A | B |` → `A  ·  B`）。比满屏 `|` 和 `---` 好读得多。
 *
 * ## 正则注意
 *
 * 不用裸 `{` `}`（量词除外）—— 见工程里那条「ICU 正则」的老经验，
 * 这套代码以后可能被搬到用 ICU 的地方。
 */
private fun buildMarkdownAnnotatedString(text: String): AnnotatedString {
    val lines = text.replace("\r\n", "\n")
        .split('\n')
        .filterNot { line -> TableSeparatorRegex.matches(line) && line.contains('-') }
        .map { line ->
            if (TableRowRegex.matches(line)) {
                line.trim().trim('|').split('|')
                    .joinToString("  ·  ") { it.trim() }
            } else {
                line
            }
        }

    return buildAnnotatedString {
        lines.forEachIndexed { index, raw ->
            if (index > 0) append('\n')

            if (RuleRegex.matches(raw)) {
                append("────────────")
                return@forEachIndexed
            }

            var line = raw
            var headingStyle: SpanStyle? = null

            HeadingRegex.find(line)?.let { match ->
                val level = match.groupValues[1].length
                headingStyle = SpanStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = when (level) {
                        1 -> 17.sp
                        2 -> 15.5.sp
                        else -> 14.5.sp
                    },
                )
                line = match.groupValues[2]
            }

            QuoteRegex.find(line)?.let { match -> line = "│ " + match.groupValues[1] }
            BulletRegex.find(line)?.let { match -> line = "• " + match.groupValues[1] }

            appendInlineMarkdown(line, headingStyle)
        }
    }
}

/**
 * 处理一行里的行内语法：`**加粗**`、`` `代码` ``、`[文字](链接)`、裸链接。
 *
 * 手写扫描而不是正则：这几条规则互相嵌套（`**[x](y)**`），一个大的交替正则
 * 分组编号很快就会变成没人敢改的东西。
 */
private fun AnnotatedString.Builder.appendInlineMarkdown(line: String, lineStyle: SpanStyle?) {
    if (lineStyle != null) pushStyle(lineStyle)
    val n = line.length
    var i = 0
    while (i < n) {
        when {
            line.startsWith("**", i) -> {
                val close = line.indexOf("**", i + 2)
                if (close > i + 2) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(line.substring(i + 2, close))
                    pop()
                    i = close + 2
                } else {
                    append(line[i])
                    i++
                }
            }

            line[i] == '`' -> {
                val close = line.indexOf('`', i + 1)
                if (close > i + 1) {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = InlineCodeBackground,
                        )
                    )
                    append(line.substring(i + 1, close))
                    pop()
                    i = close + 1
                } else {
                    append(line[i])
                    i++
                }
            }

            line[i] == '[' -> {
                val closeBracket = line.indexOf(']', i + 1)
                val openParen = if (closeBracket > i) line.indexOf('(', closeBracket + 1) else -1
                val closeParen =
                    if (openParen == closeBracket + 1) line.indexOf(')', openParen + 1) else -1
                if (closeBracket > i && closeParen > openParen + 1) {
                    val label = line.substring(i + 1, closeBracket)
                    val url = line.substring(openParen + 1, closeParen)
                    pushStringAnnotation(tag = UrlAnnotationTag, annotation = url)
                    pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                    append(label.ifBlank { url })
                    pop()
                    pop()
                    i = closeParen + 1
                } else {
                    append(line[i])
                    i++
                }
            }

            line.startsWith("http://", i) || line.startsWith("https://", i) -> {
                var j = i
                while (j < n && !line[j].isWhitespace() && line[j] != ')' && line[j] != ']') j++
                val url = line.substring(i, j)
                pushStringAnnotation(tag = UrlAnnotationTag, annotation = url)
                pushStyle(SpanStyle(textDecoration = TextDecoration.Underline))
                append(url)
                pop()
                pop()
                i = j
            }

            else -> {
                append(line[i])
                i++
            }
        }
    }
    if (lineStyle != null) pop()
}

@Preview(showBackground = true, widthDp = 420)
@Composable
private fun ExpandableRichTextPreview() {
    ComponentPreview {
        ExpandableRichText(
            text = longText
        )
    }
}

@Preview(showBackground = true, widthDp = 420)
@Composable
private fun MarkdownRichTextPreview() {
    ComponentPreview {
        ExpandableRichText(
            text = """
                # 26.9.0 更新

                这一版修了三件事，**收藏夹**是新功能。

                ## 一、网络

                | 位置 | 旧 | 新 |
                |---|---|---|
                | `CdnRelay` | 直连优先 | 保持 |

                - 收藏夹与播放清单**分开**
                - 关注作者新作角标

                > 详见 [仓库](https://github.com/ddsmie4ksmi/)
            """.trimIndent(),
            markdown = true,
        )
    }
}
