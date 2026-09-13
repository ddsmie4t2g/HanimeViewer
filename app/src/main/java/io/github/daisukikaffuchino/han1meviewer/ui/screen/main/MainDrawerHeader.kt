package io.github.daisukikaffuchino.han1meviewer.ui.screen.main

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.account.AccountRepository
import io.github.daisukikaffuchino.han1meviewer.ui.preview.ComponentPreview
import io.github.daisukikaffuchino.utils.VibrationUtil

@Composable
fun MainDrawerHeader(
    avatarUrl: String?,
    username: String?,
    isLoggedIn: Boolean,
    isLoading: Boolean,
    currentSite: String,
    onAvatarClick: () -> Unit,
    onAvatarLongClick: () -> Unit,
    onSwitchSiteClick: () -> Unit,
) {
    val view = LocalView.current
    // ⭐ 头部显示的是**自建账号**（一个号统筹三个站点），不再是 hanime 站点账号。
    //
    // 以前这里显示 hanime 的用户名 —— 切到 Pornhub / nJAV 时它照样显示 hanime 的登录态，
    // 于是「hanime 像把三个都管了」（用户 2026-09-13 的原话）。站点账号现在只在抽屉的
    // 「账号」分区里以「hanime 站点账号」出现，名字里就写明归属。
    val account by AccountRepository.state.collectAsStateWithLifecycle(
        initialValue = AccountRepository.AccountState(),
    )
    val cardShape = RoundedCornerShape(28.dp)
    val cardInteractionSource = remember { MutableInteractionSource() }
    val isCardPressed = cardInteractionSource.collectIsPressedAsState().value
    val cardScale = animateFloatAsState(
        targetValue = if (isCardPressed) 0.98f else 1f,
        label = "drawerHeaderCardScale"
    ).value
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp)
    ) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp)
                .graphicsLayer {
                    scaleX = cardScale
                    scaleY = cardScale
                }
                .clip(cardShape)
                .combinedClickable(
                    interactionSource = cardInteractionSource,
                    indication = ripple(),
                    onClick = {
                        VibrationUtil.performHapticFeedback(view)
                        onAvatarClick()
                    },
                ),
            shape = cardShape,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(132.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .combinedClickable(
                            onClick = {
                                VibrationUtil.performHapticFeedback(view)
                                onAvatarClick()
                            },
                            onLongClick = {
                                VibrationUtil.performHapticFeedback(view)
                                onAvatarLongClick()
                            },
                        ),
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(id = R.drawable.h_chan_default_avatar),
                    fallback = painterResource(id = R.drawable.h_chan_default_avatar),
                    error = painterResource(id = R.drawable.h_chan_default_avatar),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp, end = 8.dp)
                        .align(Alignment.CenterVertically)
                ) {
                    Text(
                        text = when {
                            isLoading -> stringResource(R.string.loading)
                            account.isLoggedIn -> account.username
                            else -> stringResource(R.string.account_not_logged_in_tap)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = currentSite,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    // 副标题把「这个账号管什么」说清楚：自建账号管三站的数据；
                    // hanime 的站点账号只在它后面加一句状态，不再冒充全局身份。
                    Text(
                        text = if (account.isLoggedIn) {
                            stringResource(R.string.account_header_subtitle_synced)
                        } else {
                            stringResource(R.string.account_header_subtitle_hint)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Column(
                    modifier = Modifier
                        .width(IntrinsicSize.Min)
                        .align(Alignment.CenterVertically)
                        .padding(top = 6.dp)
                        .clickable(
                            onClick = {
                                VibrationUtil.performHapticFeedback(view)
                                onSwitchSiteClick()
                            },
                            indication = ripple(bounded = false),
                            interactionSource = remember { MutableInteractionSource() }
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_switch),
                            contentDescription = stringResource(R.string.switch_site)
                        )
                    }

                    Text(
                        text = stringResource(R.string.switch_site),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.alpha(0.7f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainDrawerHeaderPreview() {
    ComponentPreview {
        MainDrawerHeader(
            avatarUrl = "https://www.baidu.com",
            username = "用户名",
            isLoggedIn = true,
            isLoading = false,
            currentSite = "https://www.baidu.com",
            onAvatarClick = {},
            onAvatarLongClick = {},
            onSwitchSiteClick = {}
        )
    }
}
