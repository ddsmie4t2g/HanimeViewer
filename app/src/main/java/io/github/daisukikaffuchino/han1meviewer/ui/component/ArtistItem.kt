package io.github.daisukikaffuchino.han1meviewer.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.model.SubscriptionItem
import io.github.daisukikaffuchino.han1meviewer.ui.preview.ComponentPreview
import io.github.daisukikaffuchino.han1meviewer.ui.preview.fakeArtists
import io.github.daisukikaffuchino.han1meviewer.ui.screen.RetryableImage
import io.github.daisukikaffuchino.utils.VibrationUtil


/**
 * 艺术家条目组件
 *
 * @param artist 艺术家订阅信息，包含名称和头像地址
 * @param onClickArtist 点击回调，参数为艺术家名称
 * @param onLongClickArtist 长按回调，参数为艺术家名称
 * @param badgeText 角标（站点短名，如 `Pornhub` / `nJAV` / `里番`）。
 *   ⭐ 26.6.5 起「关注的作者」是**跨站**显示的（你的关注是你的数据，不该被当前站点藏起来），
 *   所以每张卡要能看出这个人是哪个站的 —— 点进去才知道会去哪。
 * @param unreadCount **新作数**（9.0）。`> 0` 时在头像右上角画一个红点数字。
 *   取 0 就是不画 —— 不是「画一个 0」。
 * @param modifier 应用于根 [Column] 布局的修饰符，默认 [Modifier]
 *
 * @see SubscriptionItem
 * @see RetryableImage
 */
@Composable
fun ArtistItem(
    artist: SubscriptionItem,
    onClickArtist: (String) -> Unit,
    onLongClickArtist: (String) -> Unit,
    modifier: Modifier = Modifier,
    badgeText: String? = null,
    unreadCount: Int = 0,
) {
    val view = LocalView.current
    Column(
        modifier = modifier
            .width(72.dp)
            .combinedClickable(
                onClick = {
                    VibrationUtil.performHapticFeedback(view)
                    onClickArtist(artist.artistName)
                },
                onLongClick = {
                    VibrationUtil.performHapticFeedback(view)
                    onLongClickArtist(artist.artistName)
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 头像 + 右上角红点。用 Box 叠是刻意的：不占额外布局空间，
        // 20 列的格子排布不会被角标顶开（多出几 dp 就会让整屏换行）。
        Box(modifier = Modifier.size(56.dp)) {
            RetryableImage(
                model = artist.avatar,
                contentDescription = artist.artistName,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .border(4.dp, MaterialTheme.colorScheme.primaryContainer, CircleShape),
                placeholder = painterResource(R.drawable.h_chan_loading_small),
                error = painterResource(R.drawable.h_chan_load_failed_small),
            )
            if (unreadCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        // 三位数会把圆点撑成胶囊 —— 直接截到 99+，与多数 App 一致。
                        text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = artist.artistName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!badgeText.isNullOrBlank()) {
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ArtistItemPreview() {
    ComponentPreview {
        ArtistItem(
            artist = fakeArtists.first(),
            onClickArtist = {},
            onLongClickArtist = {},
        )
    }
}
