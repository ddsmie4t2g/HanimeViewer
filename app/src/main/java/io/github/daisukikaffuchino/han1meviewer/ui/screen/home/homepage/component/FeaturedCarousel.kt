package io.github.daisukikaffuchino.han1meviewer.ui.screen.home.homepage.component

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.HanimeInfo
import io.github.daisukikaffuchino.han1meviewer.ui.preview.ComponentPreview
import io.github.daisukikaffuchino.han1meviewer.ui.preview.fakeHomePageVideos
import io.github.daisukikaffuchino.han1meviewer.ui.screen.RetryableImage
import kotlinx.coroutines.delay

/**
 * 一屏一张的**大图轮播**（26.9.6）。
 *
 * 与 [CategoryRow] 的区别只在**画法**：后者是一排等宽小卡片横着滚，
 * 这里是整块大图、一次只显示一张、左右滑。用户的要求是
 * 「把这个推荐的做成占据空间大点的那种单个轮播」。
 *
 * ## ⚠️ 图片清晰度的天花板（实测过，别再找）
 *
 * 这一行用的是**列表缩略图 258×145**，铺满全宽（手机上约 1 000 px）会被放大 ~4 倍，
 * 观感偏软。这不是 bug，是站点就没给大图 —— 实测（`build/probe_ph_big_thumb.py`）：
 *
 * | 来源 | 尺寸 | 代价 |
 * |---|---|---|
 * | 列表卡片 `data-mediumthumb` | **258×145** | 0（列表页本来就有） |
 * | `/webmasters/search` JSON 的 `thumb` | 320×240 | 0（另一个接口） |
 * | **详情页 `og:image`** | **640×360** | **每张要抓 1.65 MB 的详情页** |
 *
 * ⇒ 所以这里**只用列表缩略图 + 大标题 + 底部压暗**：
 * 视觉重心交给白字标题，图片当底，放大后的软被渐变盖掉大半。
 * **不要**为了「更清晰」去逐张抓详情页 —— 21 条就是 ~35 MB，中转是限速的。
 *
 * ## 自动轮播
 *
 * 每 [autoAdvanceMillis] 前进一张，**用户正在拖动时跳过这一次**（不然会跟手指抢）。
 * 传 `<= 0` 可关掉。
 *
 * @param title 这一行的标题（现在固定是「推荐」）。
 * @param videos 轮播内容；内部按 `videoCode` 去重，空列表则整块不画。
 * @param onMoreClick 点「更多」时调用。
 * @param onVideoClick 点某一页时调用，参数为视频编号。
 * @param modifier 应用于轮播根布局的修饰符。
 * @param autoAdvanceMillis 自动前进间隔；`<= 0` 表示不自动轮播。
 * @param onShuffle 点「换一批」时调用；传 `null` 就不画这个按钮（默认）。
 * @param isShuffling 是否正在换一批 —— 会禁用按钮并换成「换一批中…」。
 */
@Composable
fun FeaturedCarousel(
    title: String,
    videos: List<HanimeInfo>,
    onMoreClick: () -> Unit,
    onVideoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    autoAdvanceMillis: Long = 7_000L,
    onShuffle: (() -> Unit)? = null,
    isShuffling: Boolean = false,
) {
    // ⚠️ 去重要在**建 pager 之前**：`page` 是直接当索引用的，
    //    外面传进来的列表如果有重复 vkey，索引与内容就会错位。
    val items = remember(videos) { videos.distinctBy { it.videoCode } }
    if (items.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { items.size })
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // 换了一批内容 ⇒ 回到第 1 张。不写这段的话，用户在第 15 张按「换一批」，
    // 新的一批（21 条）会从第 15 张开始显示，看起来像「没换」。
    // ⚠️ 用 scrollToPage（瞬移）：animateScrollToPage 会白滚一段动画。
    LaunchedEffect(items) {
        if (items.isNotEmpty() && pagerState.currentPage != 0) pagerState.scrollToPage(0)
    }

    LaunchedEffect(pagerState, items.size, autoAdvanceMillis) {
        if (items.size <= 1 || autoAdvanceMillis <= 0L) return@LaunchedEffect
        while (true) {
            delay(autoAdvanceMillis)
            // 手指还按着就别抢；下一次循环再补。
            if (pagerState.isScrollInProgress) continue
            val last = items.size - 1
            if (pagerState.currentPage >= last) {
                // ⚠️ 回卷必须用 scrollToPage（瞬移）。这里默认有 21 张，
                //    animateScrollToPage(0) 会真的从第 21 张一路滚回第 1 张 ——
                //    动画又长又白拉一批图。故事条那种「瞬回」才是对的。
                pagerState.scrollToPage(0)
            } else {
                pagerState.animateScrollToPage(pagerState.currentPage + 1)
            }
        }
    }

    Column(modifier = modifier) {
        // 标题行与 CategoryRow 保持一致（同样的字号、同样的「更多」）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            if (onShuffle != null) {
                // 「换一批」：真去站点要下一批（见 HomePageViewModel.shufflePhCarousel）。
                // ⚠️ 正在换的时候**连点击一起禁掉** —— 那一下会真的发起一趟 1 MB+ 的请求，
                //    没有反馈的按钮会被连点好几下（虽然 ViewModel 里也有互斥兜底）。
                Text(
                    text = stringResource(
                        if (isShuffling) R.string.ph_shuffling else R.string.ph_shuffle
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary.copy(
                        alpha = if (isShuffling) 0.45f else 1f
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = !isShuffling) { onShuffle() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Text(
                text = stringResource(R.string.more),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onMoreClick() }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            val aspectRatio = 16f / 9f
            val naturalHeight = maxWidth / aspectRatio
            val carouselHeight =
                if (isLandscape) minOf(naturalHeight, 240.dp) else naturalHeight

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(carouselHeight)
                    .clip(RoundedCornerShape(12.dp))
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(0.dp),
                    pageSpacing = 0.dp,
                    beyondViewportPageCount = 1
                ) { page ->
                    val video = items[page.coerceIn(items.indices)]
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { onVideoClick(video.videoCode) }
                    ) {
                        RetryableImage(
                            model = video.coverUrl,
                            contentDescription = video.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            placeholder = painterResource(R.drawable.h_chan_loading),
                            error = painterResource(R.drawable.h_chan_load_failed)
                        )
                        // 底部压暗：白字压在亮封面上会读不出来，这层是必须的。
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(150.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color.Black.copy(alpha = 0.78f)
                                        )
                                    )
                                )
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
                        ) {
                            Text(
                                text = video.title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            val meta = listOfNotNull(
                                video.duration?.trim()?.takeIf { it.isNotEmpty() },
                                video.views?.trim()?.takeIf { it.isNotEmpty() },
                            ).joinToString("  ·  ")
                            if (meta.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    text = meta,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.82f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // ⭐ 进度用「故事条」而不是圆点：这一行有 21 张，
                //    21 个圆点既挤又数不清；等分细条能表达「第几张 / 共几张」。
                if (items.size > 1) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        repeat(items.size) { index ->
                            val reached = index <= pagerState.currentPage
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (reached) Color.White
                                        else Color.White.copy(alpha = 0.32f)
                                    )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "推荐大轮播")
@Composable
private fun FeaturedCarouselPreview() {
    ComponentPreview {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 12.dp)
        ) {
            FeaturedCarousel(
                title = "推荐",
                videos = fakeHomePageVideos,
                onMoreClick = {},
                onVideoClick = {},
            )
        }
    }
}
