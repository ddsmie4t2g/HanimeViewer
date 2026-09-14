package io.github.daisukikaffuchino.han1meviewer.ui.component.content

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.ui.preview.ComponentPreview

/**
 * 空状态视图组件。
 *
 * 展示占位图片和提示文本，用于列表或内容为空时的视觉反馈。
 *
 * @param hint 主提示文本
 * @param subHint 副提示文本，默认为空
 * @param picRes 占位图片资源 ID，默认为 h_chan_speechless
 * @param action 可选的行动按钮。空态里最该做的事往往不是「重试」，而是
 *   「换个地方找」——比如月度归档为空时直接跳 Getchu 的当月预告。没有出口的空态
 *   只会让人反复下拉刷新。
 */
@Composable
fun EmptyContent(
    hint: String,
    subHint: String = "",
    picRes: Int = R.drawable.h_chan_speechless,
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                modifier = Modifier
                    .padding(16.dp)
                    .width(150.dp),
                painter = painterResource(picRes),
                contentDescription = stringResource(R.string.here_is_empty),
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Text(
                text = subHint,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            action?.let {
                Box(modifier = Modifier.padding(top = 16.dp)) { it() }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyViewPreview() {
    ComponentPreview {
        EmptyContent(hint = "发生了一些事情", subHint = "reason: 404 Not Found")
    }
}
