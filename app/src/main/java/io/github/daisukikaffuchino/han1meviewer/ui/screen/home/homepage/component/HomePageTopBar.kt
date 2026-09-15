package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.ui.component.IconButton
import io.github.daisukikaffuchino.han1meviewer.ui.component.appbar.HanimeTopAppBar
import io.github.daisukikaffuchino.han1meviewer.ui.preview.ComponentPreview
import io.github.daisukikaffuchino.han1meviewer.ui.theme.HanimeDefaults

/**
 * 首页右上角那个「功能入口」按钮的形态。
 *
 * 三个数据源的可用页面本来就不同，所以它不是装饰，而是**按站点切换的导航**：
 *
 * | 数据源 | 按钮 | 去向 |
 * |---|---|---|
 * | hanime | 📅 日历 / 新番 | [PreviewRoute]（按上市月归档 + 站方预告） |
 * | nJAV | 👥 浏览（女优一览 / 女优排行） | [ActressGalleryRoute] |
 * | Pornhub | **不显示** | —— |
 *
 * 为什么 Pornhub 干脆不给：日历页读的是 hanime 按月归档，
 * Pornhub 下点进去只有一整页空态（`getHanimeArchiveByMonth` 对 AV 站直接返回
 * NoMoreData），摆在那里只会让人以为坏了。
 */
enum class HomeTopBarAction {
    /** 日历 / 新番（hanime） */
    Preview,

    /** 浏览 —— 女优一览 / 排行（nJAV） */
    Browse,

    /** 什么都不放（Pornhub） */
    None,
}

/**
 * 渲染首页顶部栏，包含抽屉入口、搜索入口，以及一个**按数据源变脸**的功能入口
 * （日历 / 浏览 / 无，见 [HomeTopBarAction]）。
 *
 * @param onOpenDrawer 点击抽屉按钮时调用。
 * @param onSearchClick 点击搜索框时调用。
 * @param onNavigateToPreview 点击「日历 / 新番」时调用。
 * @param onNavigateToActressGallery 点击「浏览」时调用。
 * @param topBarAction 右上角放哪个入口。
 * @param modifier 应用于顶部栏根布局的修饰符。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePageTopBar(
    onOpenDrawer: () -> Unit,
    onSearchClick: () -> Unit,
    onNavigateToPreview: () -> Unit,
    onNavigateToActressGallery: () -> Unit,
    topBarAction: HomeTopBarAction,
    modifier: Modifier = Modifier,
    showNavigationIcon: Boolean = true,
    containerColor: Color = HanimeDefaults.Colors.pageSurface,
) {
    HanimeTopAppBar(
        modifier = modifier,
        title = {
            Han1meViewerText()
        },
        navigationIcon = if (showNavigationIcon) {
            {
                IconButton(onClick = onOpenDrawer) {
                    Icon(
                        painter = painterResource(R.drawable.ic_menu),
                        contentDescription = stringResource(R.string.open_menu),
                    )
                }
            }
        } else {
            {}
        },
        actions = {
            Row {
                IconButton(onClick = onSearchClick) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = stringResource(R.string.global_search),
                    )
                }
                when (topBarAction) {
                    HomeTopBarAction.Preview -> IconButton(onClick = onNavigateToPreview) {
                        Icon(
                            painter = painterResource(R.drawable.ic_newspaper),
                            contentDescription = stringResource(R.string.hanime_list),
                        )
                    }

                    HomeTopBarAction.Browse -> IconButton(onClick = onNavigateToActressGallery) {
                        Icon(
                            painter = painterResource(R.drawable.ic_move_group),
                            contentDescription = stringResource(R.string.browse_section),
                        )
                    }

                    HomeTopBarAction.None -> Unit
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = containerColor,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

private val robotoFont = FontFamily(
    Font(R.font.roboto)
)

@Composable
private fun Han1meViewerText(
    modifier: Modifier = Modifier,
    fontSize: Int = 20,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val annotatedString = buildAnnotatedString {
        withStyle(
            style = SpanStyle(
                color = MaterialTheme.colorScheme.primary,
                fontFamily = robotoFont,
                fontWeight = FontWeight.Bold,
            )
        ) {
            append("H")
        }
        withStyle(
            style = SpanStyle(
                color = onSurface,
                fontFamily = robotoFont,
                fontWeight = FontWeight.Bold,
            )
        ) {
            append("an1me")
        }
        withStyle(
            style = SpanStyle(
                color = onSurfaceVariant,
                fontFamily = robotoFont,
                fontWeight = FontWeight.Normal,
            )
        ) {
            append("Viewer")
        }
    }

    BasicText(
        text = annotatedString,
        modifier = modifier,
        style = androidx.compose.ui.text.TextStyle(
            fontSize = fontSize.sp,
        ),
        softWrap = false,
        maxLines = maxLines,
        overflow = overflow,
        autoSize = TextAutoSize.StepBased(
            minFontSize = 8.sp,
            maxFontSize = fontSize.sp,
        ),
    )
}

@Preview(showBackground = true, name = "首页顶栏")
@Composable
private fun HomePageTopBarPreview() {
    ComponentPreview {
        HomePageTopBar(
            onOpenDrawer = {},
            onSearchClick = {},
            onNavigateToPreview = {},
            onNavigateToActressGallery = {},
            topBarAction = HomeTopBarAction.Preview,
        )
    }
}

@Preview(showBackground = true, name = "首页顶栏（nJAV 浏览）")
@Composable
private fun HomePageTopBarBrowsePreview() {
    ComponentPreview {
        HomePageTopBar(
            onOpenDrawer = {},
            onSearchClick = {},
            onNavigateToPreview = {},
            onNavigateToActressGallery = {},
            topBarAction = HomeTopBarAction.Browse,
        )
    }
}

@Preview(showBackground = true, name = "首页顶栏(横)")
@Composable
private fun HomePageTopBarLandPreview() {
    ComponentPreview {
        HomePageTopBar(
            onOpenDrawer = {},
            onSearchClick = {},
            onNavigateToPreview = {},
            onNavigateToActressGallery = {},
            topBarAction = HomeTopBarAction.None,
            showNavigationIcon = false
        )
    }
}
