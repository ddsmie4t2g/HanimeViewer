package io.github.daisukikaffuchino.han1meviewer.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.daisukikaffuchino.han1meviewer.logic.ArtistUpdateChecker
import io.github.daisukikaffuchino.han1meviewer.logic.SettingsRepository
import io.github.daisukikaffuchino.utils.LogUtil
import java.util.concurrent.TimeUnit

/**
 * **关注作者新作**的周期检查（9.0）。
 *
 * ## 为什么要有它
 *
 * 「软件内角标」只要用户打开订阅页就会自己算出来，不需要后台任务；但**手机通知**
 * 必须有人在他没打开 App 的时候去看一眼 —— 那就是这个 Worker。
 *
 * ## 只做通知，不做别的
 *
 * 通知渠道关掉（设置里选「关闭」或「仅软件内」）时**直接返回，一次网络都不发**：
 * WorkManager 每 12 小时叫醒我们一次不花用户的电，但几十个跨境请求会。
 */
class FollowUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (SettingsRepository.followUpdateAlert != ArtistUpdateChecker.MODE_NOTIFICATION) {
            return Result.success()
        }
        return runCatching { ArtistUpdateChecker.check(force = true) }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { error ->
                    LogUtil.w(TAG, "follow update check failed: ${error.message}")
                    // 站点抖一下不该判死刑；指数退避后再来一次。
                    Result.retry()
                },
            )
    }

    companion object {
        private const val TAG = "FollowUpdateWorker"
        private const val UNIQUE_NAME = "follow_artist_update_check"

        /** 12 小时一次：新作不是快讯，一天两趟足够，也免得排队挤占中转账号。 */
        private const val INTERVAL_HOURS = 12L

        /**
         * 排上周期检查（幂等）。
         *
         * ⚠️ [ExistingPeriodicWorkPolicy.KEEP]：**别改成 REPLACE**。
         * 每次启动都 REPLACE 会把「已经等到的下一次执行时刻」推回 12 小时之后 ——
         * 天天开 App 的人就永远等不到它跑。
         */
        fun schedule(context: Context) {
            runCatching {
                val request = PeriodicWorkRequestBuilder<FollowUpdateWorker>(
                    INTERVAL_HOURS,
                    TimeUnit.HOURS,
                )
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build()
                    )
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }.onFailure { LogUtil.w(TAG, "schedule failed: ${it.message}") }
        }
    }
}
