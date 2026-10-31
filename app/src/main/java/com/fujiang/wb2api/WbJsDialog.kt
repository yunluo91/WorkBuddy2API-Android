package com.fujiang.wb2api

import android.webkit.JsPromptResult
import android.webkit.JsResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** JS 对话框的类型。 */
enum class JsDialogKind { ALERT, CONFIRM, PROMPT }

/**
 * 一个待处理的 JS 对话框。
 *
 * result 装的是 WebView 给的 JsResult / JsPromptResult —— 必须结掉
 * （confirm 或 cancel），否则页面脚本会一直停在那句话上，后面全都不执行。
 */
data class JsDialogState(
    val kind: JsDialogKind,
    val title: String,
    val message: String,
    val defaultValue: String,
    val showCancel: Boolean,
    val result: Any,
)

/**
 * 状态驱动的 JS 对话框。
 *
 * 用 androidx.compose.ui.window.Dialog —— 它跟宿主 Activity 在同一棵
 * 组合树上，能正常拿到 ViewTreeLifecycleOwner，不会像
 * android.app.Dialog + ComposeView 那样在 attach 阶段抛
 * "ViewTreeLifecycleOwner not found from AndroidComposeView"
 * 导致整个进程崩（那个异常发生在 show() 返回之后，外层 try/catch 兜不住）。
 *
 * 界面跟之前那版保持一致：MD3 Surface + 圆角 20dp + 主色标题，
 * 正文可滚动可选中（错误信息要能长按复制），输入框聚焦自动弹键盘。
 */
@Composable
fun WbJsDialog(
    state: JsDialogState,
    onCancel: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(state) { mutableStateOf(state.defaultValue) }
    val focus = remember { FocusRequester() }
    val withInput = state.kind == JsDialogKind.PROMPT

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = true,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = WbCard,
            tonalElevation = 0.dp,
        ) {
            Column(modifier = Modifier.padding(22.dp)) {
                Text(
                    text = state.title,
                    color = WbAccent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                val msg = state.message.trim()
                if (msg.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .heightIn(max = 300.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        SelectionContainer {
                            Text(
                                text = msg,
                                color = WbText,
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }

                if (withInput) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                        placeholder = {
                            Text("请输入", color = WbMuted, fontSize = 14.sp)
                        },
                        textStyle = LocalTextStyle.current.copy(
                            color = WbText,
                            fontSize = 14.sp
                        ),
                        maxLines = 4,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = WbAccent,
                            unfocusedBorderColor = WbLine,
                            focusedContainerColor = WbBg,
                            unfocusedContainerColor = WbBg,
                            cursorColor = WbAccent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onConfirm(text) })
                    )
                    LaunchedEffect(Unit) {
                        try { focus.requestFocus() } catch (_: Throwable) {}
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (state.showCancel) {
                        TextButton(onClick = onCancel) {
                            Text("取消", color = WbMuted, fontSize = 14.sp)
                        }
                        Spacer(Modifier.width(4.dp))
                    }
                    TextButton(onClick = { onConfirm(text) }) {
                        Text(
                            "确定",
                            color = WbAccent,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
