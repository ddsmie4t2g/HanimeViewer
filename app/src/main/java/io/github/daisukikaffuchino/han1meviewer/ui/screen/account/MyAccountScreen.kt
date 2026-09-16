package io.github.daisukikaffuchino.han1meviewer.ui.screen.account

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daisukikaffuchino.han1meviewer.R
import io.github.daisukikaffuchino.han1meviewer.logic.account.AccountApi
import io.github.daisukikaffuchino.han1meviewer.logic.account.AccountRepository
import io.github.daisukikaffuchino.han1meviewer.ui.component.appbar.HanimeScaffold
import io.github.daisukikaffuchino.utils.SonnerToast
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * **我的账号**（自建账号，26.7.0）。
 *
 * 这一页与 hanime 的登录页是**两件事**，界面上也刻意分开：
 * - 这里：你自己的账号，数据存在**你自己的服务器**上（关注 / 本机清单 / 观看记录）；
 * - 那边：hanime 站点账号（订阅 / 清单 / 评论），要登录的是 hanime。
 *
 * 未登录时是一个注册/登录表单；登录后是「同步面板」：看得到云端版本、上次同步时间，
 * 手动触发同步（双向）或只上传本机数据。
 *
 * @param navigateBack 返回
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MyAccountScreen(navigateBack: () -> Unit) {
    val state by AccountRepository.state.collectAsStateWithLifecycle(
        initialValue = AccountRepository.AccountState(),
    )
    val scope = rememberCoroutineScope()
    // `describe()` 在 `scope.launch { }` 里调用 —— 不是 composable 上下文，
    // 不能在里面用 `stringResource`，所以在这里把 context 取好传进去。
    val context = LocalContext.current

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    // 失败原因要**贴着按钮**显示（以前只在页面最底部，键盘一弹就被挡掉，
    // 用户看到的就是「原因见下方」而下面什么都没有）。
    var statusIsError by remember { mutableStateOf(false) }
    fun setStatus(text: String, isError: Boolean) {
        status = text
        statusIsError = isError
    }
    var showPasswordDialog by remember { mutableStateOf(false) }

    // ⚠️ 文案必须在**组合作用域**里先取好：协程（scope.launch）里不能调 stringResource。
    // 所以这里是「机器码 → 本地化句子」的表 + 一个纯函数，供下面所有回调使用。
    val errorStrings = mapOf(
        "username_invalid" to stringResource(R.string.account_err_username_invalid),
        "password_too_short" to stringResource(R.string.account_err_password_too_short),
        "username_taken" to stringResource(R.string.account_err_username_taken),
        "bad_credentials" to stringResource(R.string.account_err_bad_credentials),
        "rate_limited" to stringResource(R.string.account_err_rate_limited),
        "registration_disabled" to stringResource(R.string.account_err_registration_disabled),
        "account_limit" to stringResource(R.string.account_err_account_limit),
        "wrong_password" to stringResource(R.string.account_err_wrong_password),
        "invalid_token" to stringResource(R.string.account_err_token),
        "missing_token" to stringResource(R.string.account_err_token),
        "conflict" to stringResource(R.string.account_err_conflict),
    )
    val serverUnreachable = stringResource(
        R.string.account_server_unreachable,
        AccountRepository.serverUrl,
    )

    /**
     * 失败原因 —— **一定非空**。
     *
     * 用户报的就是这个：提示写着「原因见下方」，而下方什么都没有（异常 message 为 null
     * 时原来会渲染成空串）。所以这里两条路都有具体内容：认识机器码就用本地化句子，
     * 不认识就带上服务端原文与 HTTP 码。
     */
    fun explain(e: Throwable): String = when (e) {
        is AccountApi.AccountException ->
            errorStrings[e.errorCode] ?: "${e.message}（HTTP ${e.code}）"

        else -> AccountRepository.readableError(e)
    }

    // 探活一次：让用户先知道「服务器通不通」，而不是在表单上猜自己是不是填错了。
    LaunchedEffect(Unit) {
        val ok = AccountRepository.ping()
        setStatus(if (ok) AccountRepository.serverUrl else serverUnreachable, isError = !ok)
    }

    HanimeScaffold(
        title = stringResource(R.string.account_title),
        onBack = navigateBack,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.account_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 用户的原话是「njav 和 pornhub 账号都是没有的，也不能官方登录」——
            // 直接把这件事说清楚：那两个站点没有账号可登，这个账号就是它们的账号。
            Text(
                text = stringResource(R.string.account_no_site_accounts),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (!state.isLoggedIn) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(stringResource(R.string.username)) },
                            singleLine = true,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.password)) },
                            singleLine = true,
                            enabled = !busy,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.account_password_rule),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 输入了内容但还不合法时立刻说清楚 —— 灰按钮不该是个哑谜。
                        if (username.isNotBlank() && !isValidAccountUsername(username)) {
                            Text(
                                text = stringResource(R.string.account_err_username_invalid),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (status.isNotBlank()) {
                            Text(
                                text = status,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (statusIsError) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val result = AccountRepository.login(username, password)
                                        result.onSuccess {
                                            password = ""
                                            // ⭐ 登录成功后**自动同步一次**：用户装完新机、登录，
                                            // 期望的就是「数据自己回来」，而不是再去点一个按钮。
                                            val outcome = AccountRepository.sync()
                                            setStatus(outcome.describe(context), isError = !outcome.success)
                                            SonnerToast.success(R.string.account_logged_in)
                                        }.onFailure { e ->
                                            val reason = explain(e)
                                            setStatus(reason, isError = true)
                                            // 原因同时进 toast：即使正文被挡住，也知道到底怎么了。
                                            SonnerToast.error(reason)
                                        }
                                        busy = false
                                    }
                                },
                                enabled = !busy && isValidAccountUsername(username) &&
                                        password.length >= 6,
                            ) {
                                Text(stringResource(R.string.login))
                            }
                            OutlinedButton(
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val result = AccountRepository.register(username, password)
                                        result.onSuccess {
                                            password = ""
                                            // 新账号云端是空的 → 这次同步会把**本机数据带上去**。
                                            val outcome = AccountRepository.sync()
                                            setStatus(outcome.describe(context), isError = !outcome.success)
                                            SonnerToast.success(R.string.account_registered)
                                        }.onFailure { e ->
                                            val reason = explain(e)
                                            setStatus(reason, isError = true)
                                            SonnerToast.error(reason)
                                        }
                                        busy = false
                                    }
                                },
                                enabled = !busy && isValidAccountUsername(username) &&
                                        password.length >= 6,
                            ) {
                                Text(stringResource(R.string.account_register))
                            }
                        }
                    } else {
                        InfoRow(stringResource(R.string.username), state.username)
                        InfoRow(stringResource(R.string.account_server), AccountRepository.serverUrl)
                        InfoRow(
                            stringResource(R.string.account_cloud_revision),
                            state.revision.toString(),
                        )
                        InfoRow(
                            stringResource(R.string.account_last_sync),
                            state.lastSyncAt.takeIf { it > 0 }?.let { formatTime(it) }
                                ?: stringResource(R.string.account_never_synced),
                        )

                        Spacer(Modifier.height(2.dp))
                        Button(
                            onClick = {
                                busy = true
                                scope.launch {
                                    val outcome = AccountRepository.sync()
                                    busy = false
                                    setStatus(outcome.describe(context), isError = !outcome.success)
                                    if (outcome.success) {
                                        SonnerToast.success(R.string.account_sync_done)
                                    } else {
                                        SonnerToast.error(outcome.message)
                                    }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.account_sync_now))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        val outcome = AccountRepository.uploadLocal()
                                        busy = false
                                        setStatus(outcome.message, isError = !outcome.success)
                                        if (outcome.success) {
                                            SonnerToast.success(R.string.account_sync_done)
                                        } else {
                                            SonnerToast.error(outcome.message)
                                        }
                                    }
                                },
                                enabled = !busy,
                            ) {
                                Text(stringResource(R.string.account_upload_local))
                            }
                            OutlinedButton(
                                onClick = { showPasswordDialog = true },
                                enabled = !busy,
                            ) {
                                Text(stringResource(R.string.account_change_password))
                            }
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        AccountRepository.logout()
                                        setStatus("", isError = false)
                                        SonnerToast.success(R.string.account_logged_out)
                                    }
                                },
                                enabled = !busy,
                            ) {
                                Text(stringResource(R.string.logout))
                            }
                        }
                    }
                }
            }

            if (busy) {
                LoadingIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
            // 登录态下（卡片里没有内联状态位）仍然在这里显示一次结果。
            if (state.isLoggedIn && status.isNotBlank()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = stringResource(R.string.account_what_syncs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showPasswordDialog) {
        ChangePasswordDialog(
            onDismiss = { showPasswordDialog = false },
            onSubmit = { old, new ->
                showPasswordDialog = false
                scope.launch {
                    AccountRepository.changePassword(old, new)
                        .onSuccess {
                            SonnerToast.success(R.string.account_password_changed)
                            setStatus("", isError = false)
                        }
                        .onFailure { e ->
                            val reason = explain(e)
                            setStatus(reason, isError = true)
                            SonnerToast.error(reason)
                        }
                }
            },
        )
    }
}

/** 用户名合法吗（与服务端同规则，先在本机拦一次，省一次往返与一个 400）。 */
private fun isValidAccountUsername(name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.length !in 2..32) return false
    if (trimmed.any { it.isWhitespace() || it.isISOControl() }) return false
    return ACCOUNT_USERNAME.matches(trimmed)
}

/**
 * 允许「任意语言的字母/数字 + `_ . -`」，2–32 个。
 *
 * ⚠️ 别改成 `[A-Za-z0-9]`：用户第一次注册打的是中文名，被服务端挡下（400），
 * 而界面又没显示出原因 —— 支持中文用户名是硬需求。
 * 量词 `{2,32}` 是安全的（ICU 只禁裸大括号）。
 */
private val ACCOUNT_USERNAME = Regex("""^[\p{L}\p{N}_.\-]{2,32}$""")

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = old,
                    onValueChange = { old = it },
                    label = { Text(stringResource(R.string.account_old_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = new,
                    onValueChange = { new = it },
                    label = { Text(stringResource(R.string.account_new_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    text = stringResource(R.string.account_password_change_kicks_sessions),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(old, new) },
                enabled = old.isNotBlank() && new.length >= 6,
            ) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/** 同步结果的一句话描述：说清「这次带来了什么」，而不是只说「成功」。
 *
 * ⚠️ 这里是**非 composable** 的扩展函数（在 `scope.launch { }` 里调用），
 * 所以文案走 `context.getString` 而不是 `stringResource`。26.9.9 之前这几句
 * 是写死的中文，切英文 / 繁中界面不会跟着变。
 */
private fun AccountRepository.SyncOutcome.describe(context: Context): String {
    val d = diff ?: return message
    if (d.isEmpty) return context.getString(R.string.sync_consistent_suffix, message)
    return context.getString(
        R.string.sync_result_summary_format,
        message,
        d.newFollows,
        d.newHistory,
        d.newListItems,
    )
}
