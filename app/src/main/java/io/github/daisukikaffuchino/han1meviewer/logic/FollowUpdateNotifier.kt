package io.github.daisukikaffuchino.han1meviewer.logic

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.daisukikaffuchino.han1meviewer.FOLLOW_UPDATE_NOTIFICATION_CHANNEL
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.utils.applicationContext

/**
 * 「关注的作者有新作」通知（9.0）。
 *
 * ## 为什么不是一人一条
 *
 * 一轮检查可能十几个作者都有新作 —— 一人一条会把通知栏刷满，用户第一件事就是
 * 把整个渠道关掉。所以**整轮合成一条**：标题是总数，正文是「谁（几部）」列表，
 * 展开（`BigTextStyle`）看全。
 *
 * ## 权限
 *
 * Android 13+ 要 `POST_NOTIFICATIONS`。没有权限时**静默跳过** ——
 * 这是用户自己的选择（拒绝过），不该在界面上再弹一次。
 */
object FollowUpdateNotifier {

    /** 固定 id：每条新通知覆盖上一条，通知栏里永远只有一条「关注新作」。 */
    private const val NOTIFICATION_ID = 9_0001

    /**
     * 发一条汇总通知。
     *
     * @param items 「作者名 → 新作数」，只传 `> 0` 的那些
     */
    fun notifyNewWorks(items: List<Pair<String, Int>>) {
        val fresh = items.filter { it.second > 0 }
        if (fresh.isEmpty()) return

        // ⚠️ `applicationContext` 是别人文件里的 `lateinit var`，跨文件读不到它的
        // backing field（`::x.isInitialized` 编译不过），所以用 runCatching 兜：
        // 未初始化会抛 UninitializedPropertyAccessException，正好被它吞掉。
        val context = runCatching { applicationContext }.getOrNull() ?: return
        if (!canPost(context)) return

        val total = fresh.sumOf { it.second }
        // 「A (3)、B (1)…」；超过 5 个人只列前 5 个 + 「等 N 位」，免得正文长成一大段。
        // ⚠️ 连接符和每一项的格式都走资源：中文用「、」和全角括号，英文要用逗号加空格。
        val separator = context.getString(R.string.follow_update_notification_separator)
        val shown = fresh.take(5).joinToString(separator) { (name, count) ->
            context.getString(R.string.follow_update_notification_item, name, count)
        }
        val detail = if (fresh.size > 5) {
            context.getString(R.string.follow_update_notification_more, shown, fresh.size)
        } else {
            shown
        }

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val builder = NotificationCompat.Builder(context, FOLLOW_UPDATE_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_subscribtion)
            .setContentTitle(context.getString(R.string.follow_update_notification_title, total))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (contentIntent != null) builder.setContentIntent(contentIntent)

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
