package io.github.daisukikaffuchino.han1meviewer.ui.screen.account

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.daisukikaffuchino.han1meviewer.R
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

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var showPasswordDialog by remember { mutableStateOf(false) }

    // 探活一次：让用户先知道「服务器通不通」，而不是在表单上猜自己是不是填错了。
    LaunchedEffect(Unit) {
        status = if (AccountRepository.ping()) {
            AccountRepository.serverUrl
        } else {
            "连不上账号服务器（${AccountRepository.serverUrl}）"
        }
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
                                            status = AccountRepository.sync().describe()
                                            SonnerToast.success(R.string.account_logged_in)
                                        }.onFailure {
                                            status = it.message.orEmpty()
                                            SonnerToast.error(R.string.account_failed)
                                        }
                                        busy = false
                                    }
                                },
                                enabled = !busy && username.isNotBlank() && password.length >= 6,
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
                                            status = AccountRepository.sync().describe()
                                            SonnerToast.success(R.string.account_registered)
                                        }.onFailure {
                                            status = it.message.orEmpty()
                                            SonnerToast.error(R.string.account_failed)
                                        }
                                        busy = false
                                    }
                                },
                                enabled = !busy && username.isNotBlank() && password.length >= 6,
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
                                    status = outcome.describe()
                                    if (outcome.success) {
                                        SonnerToast.success(R.string.account_sync_done)
                                    } else {
                                        SonnerToast.error(R.string.account_failed)
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
                                        status = outcome.message
                                        if (outcome.success) {
                                            SonnerToast.success(R.string.account_sync_done)
                                        } else {
                                            SonnerToast.error(R.string.account_failed)
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
                                        status = ""
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
            if (status.isNotBlank()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            status = ""
                        }
                        .onFailure {
                            status = it.message.orEmpty()
                            SonnerToast.error(R.string.account_failed)
                        }
                }
            },
        )
    }
}

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

/** 同步结果的一句话描述：说清「这次带来了什么」，而不是只说「成功」。 */
private fun AccountRepository.SyncOutcome.describe(): String {
    val d = diff ?: return message
    if (d.isEmpty) return "$message（本机与云端已一致）"
    return "$message：新增关注 ${d.newFollows}、观看记录 ${d.newHistory}、清单条目 ${d.newListItems}"
}
