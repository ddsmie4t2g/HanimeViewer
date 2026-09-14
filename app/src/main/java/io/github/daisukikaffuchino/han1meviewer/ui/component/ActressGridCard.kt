package io.github.daisukikaffuchino.han1meviewer.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.NjavActress
import io.github.daisukikaffuchino.han1meviewer.ui.screen.RetryableImage

/**
 * **女优卡片**（圆形头像 + 名字 + 一行小字）—— 26.8.2 从「搜索页的女优选择弹窗」里提出来共用。
 *
 * 女优一览、女优排行、以及高级搜索里的女优筛选，三处画的其实是同一张卡：
 * 站点这两个页面的卡片结构**完全同构**（同样的 `<li>` / `<h4>` / `fourhoi.com/actress/<id>-t.jpg`），
 * 所以这里也只需要一个组件 —— 差别只有那行小字：
 *
 * | 来源 | 小字 |
 * |---|---|
 * | 女优一览 | `5669 部影片 · 2008 出道` |
 * | 女优排行 | `第 1 名` 徽章 |
 *
 * @param selected 选中态（高级搜索里当筛选器用时要画出来）
 * @param onClick 点击回调
 */
@Composable
fun ActressGridCard(
    actress: NjavActress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    Color.Transparent
                }
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            modifier = Modifier.size(68.dp),
        ) {
            // ⚠️ 头像域名是 `fourhoi.com`：大陆直连不通，走的是 CDN 中转
            // （见 `ImageNetworkClient` + `CdnRelayInterceptor`）。
            // 用 RetryableImage 而不是裸 AsyncImage：中转节点偶尔会抖一下，
            // 重试一次比直接画一个空圈好。
            RetryableImage(
                model = actress.avatarUrl,
                contentDescription = actress.name,
                contentScale = ContentScale.Crop,
                placeholder = painterResource(R.drawable.h_chan_loading_small),
                error = painterResource(R.drawable.h_chan_default_avatar),
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = actress.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        // 排行页给的是名次徽章、一览页给的是「作品数 · 出道年」；两者都没有就不画这一行。
        actress.rank?.let { rank ->
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.actress_rank_badge, rank),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        } ?: ActressSubtitle(actress)
    }
}

/** 「5669 部影片 · 2008 出道」；两个字段都可能解析不出来，都空就不画。 */
@Composable
private fun ActressSubtitle(actress: NjavActress) {
    val parts = listOfNotNull(
        actress.videoCount?.let { stringResource(R.string.actress_video_count, it) },
        actress.debutYear?.let { stringResource(R.string.actress_debut_year, it) },
    )
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
    )
}
