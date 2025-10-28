package com.bestlink.screenmate.ui

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.bestlink.screenmate.net.WsClient
import com.bestlink.screenmate.util.VoiceRecorderUtil
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Composable
fun VoiceInputPanel(
    wsClient: WsClient,
    coroutineScope: CoroutineScope
) {
    val context = LocalContext.current
    
    // 语音录制工具
    val voiceRecorderUtil = remember {
        VoiceRecorderUtil(context)
    }
    
    // 权限请求启动器
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            // 权限被拒绝，更新状态显示
            voiceRecorderUtil.updateRecordingState(
                VoiceRecorderUtil.RecordingState.Error("需要录音权限才能使用语音功能")
            )
        }
    }
    
    // 语音录制状态
    val recordingState by voiceRecorderUtil.recordingState.collectAsState()
    val recordingDuration by voiceRecorderUtil.recordingDurationFlow.collectAsState()
    
    // 微信风格录音状态
    var isPressing by remember { mutableStateOf(false) } // 是否正在按压
    var showCancel by remember { mutableStateOf(false) } // 是否显示取消区域
    var isInCancelArea by remember { mutableStateOf(false) } // 手指是否在取消区域
    
    // 根据状态更新录音状态
    LaunchedEffect(recordingState) {
        when (recordingState) {
            VoiceRecorderUtil.RecordingState.Recording -> {
                // 录音开始
            }
            VoiceRecorderUtil.RecordingState.Idle -> {
                // 录音结束，重置状态
                isPressing = false
                showCancel = false
                isInCancelArea = false
            }
            is VoiceRecorderUtil.RecordingState.Success -> {
                // 录音成功，发送数据
                val successState = recordingState as VoiceRecorderUtil.RecordingState.Success
                if (!isInCancelArea) {
                    // 手指没有移动到取消区域，发送录音
                    sendVoiceData(wsClient, successState.audioData, coroutineScope)
                }
                // 重置状态
                isPressing = false
                showCancel = false
                isInCancelArea = false
            }
            is VoiceRecorderUtil.RecordingState.Error -> {
                // 录音错误，重置状态
                isPressing = false
                showCancel = false
                isInCancelArea = false
            }
            else -> {
                // 其他状态不改变录音状态
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.9f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 标题
        Text(
            text = "语音录音",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        // 取消区域（仅在按压时显示）
        if (showCancel) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)  // 减小高度，更靠近录音键
                    .background(
                        if (isInCancelArea) Color.Red.copy(alpha = 0.1f) 
                        else Color.Transparent
                    )
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "取消",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isInCancelArea) Color.Red else Color.Gray,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))  // 减小间距，让取消区域更靠近录音键
        
        // 状态显示
        Text(
            text = getStatusText(recordingState, recordingDuration, isInCancelArea),
            style = MaterialTheme.typography.bodySmall,
            color = getStatusColor(recordingState, isInCancelArea),
            modifier = Modifier.padding(bottom = 12.dp)
        )
        
        // 录音按钮（微信风格）
        val density = LocalDensity.current
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(40.dp))
                .background(
                    if (isPressing) Color.Red 
                    else MaterialTheme.colorScheme.primary
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { initialOffset ->
                            // 开始按压
                            isPressing = true
                            showCancel = true
                            isInCancelArea = false
                            
                            // 检查权限
                            if (!voiceRecorderUtil.hasRecordAudioPermission()) {
                                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return@detectTapGestures
                            }
                            
                            // 开始录音
                            voiceRecorderUtil.startRecording()
                            
                            // 使用awaitPointerEventScope来持续跟踪手指移动
                            val success = try {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val currentPosition = event.changes.first().position
                                        
                                        // 检测是否移动到取消区域（考虑屏幕密度）
                                        // 取消区域：Y坐标小于按钮高度的1/4（向上滑动），且X坐标在按钮宽度范围内
                                        val buttonHeight = with(density) { 80.dp.toPx() }
                                        val buttonWidth = with(density) { 80.dp.toPx() }
                                        val cancelThreshold = buttonHeight / 4  // 按钮高度的1/4作为取消阈值
                                        
                                        val isCancel = currentPosition.y < -cancelThreshold && 
                                                     currentPosition.x >= 0 && 
                                                     currentPosition.x <= buttonWidth
                                        
                                        if (isInCancelArea != isCancel) {
                                            isInCancelArea = isCancel
                                        }
                                        
                                        // 检查是否释放
                                        if (!event.changes.first().pressed) {
                                            break
                                        }
                                    }
                                    true
                                }
                            } catch (e: Exception) {
                                false
                            }
                            
                            // 手指释放后的处理
                            if (!isInCancelArea) {
                                // 在录音按钮区域释放，停止录音
                                voiceRecorderUtil.stopRecording()
                            } else {
                                // 在取消区域释放，取消录音
                                voiceRecorderUtil.cancelRecording()
                            }
                            
                            // 重置状态
                            isPressing = false
                            showCancel = false
                            isInCancelArea = false
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isPressing) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = if (isPressing) "松开发送" else "按住录音",
                modifier = Modifier.size(32.dp),
                tint = Color.White
            )
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        // 提示文本
        Text(
            text = if (isPressing) {
                if (isInCancelArea) "松开手指，取消发送" 
                else "松开手指，发送语音"
            } else {
                "按住按钮开始录音"
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(40.dp),
            color = if (isPressing) {
                if (isInCancelArea) Color.Red else Color.Gray
            } else Color.Gray
        )
    }
    
    // 清理资源
    DisposableEffect(Unit) {
        onDispose {
            voiceRecorderUtil.destroy()
        }
    }
}

// 发送语音数据
private fun sendVoiceData(
    wsClient: WsClient,
    audioData: String,
    coroutineScope: CoroutineScope
) {
    // 使用与sendKey相同的逻辑，包含id字段
    val id = wsClient.getHostId()
    
    // 使用JSONObject正确构建JSON，避免Base64数据中的特殊字符导致JSON格式错误
    val jsonObject = org.json.JSONObject().apply {
        put("id", id)
        put("command", "voice")
        put("content", audioData)
    }
    val payload = jsonObject.toString()
    
    coroutineScope.launch {
        try {
            // 使用sendRawMessage方法发送包含id字段的语音消息
            val success = wsClient.sendRawMessage(payload)
            if (success) {
                android.util.Log.d("VoiceInputPanel", "语音数据发送成功")
            } else {
                android.util.Log.e("VoiceInputPanel", "语音数据发送失败")
            }
        } catch (e: Exception) {
            android.util.Log.e("VoiceInputPanel", "发送语音数据失败: ${e.message}")
        }
    }
}

// 获取状态文本
private fun getStatusText(state: VoiceRecorderUtil.RecordingState, duration: Long, isInCancelArea: Boolean): String {
    return when (state) {
        VoiceRecorderUtil.RecordingState.Idle -> "准备就绪"
        VoiceRecorderUtil.RecordingState.Starting -> "正在启动录音..."
        VoiceRecorderUtil.RecordingState.Recording -> {
            if (isInCancelArea) "松开取消录音" 
            else "正在录音... ${duration}秒"
        }
        VoiceRecorderUtil.RecordingState.Stopping -> "正在停止录音..."
        VoiceRecorderUtil.RecordingState.Processing -> "正在处理语音数据..."
        is VoiceRecorderUtil.RecordingState.Success -> "录音完成"
        is VoiceRecorderUtil.RecordingState.Error -> "错误: ${state.message}"
    }
}

// 获取状态颜色
private fun getStatusColor(state: VoiceRecorderUtil.RecordingState, isInCancelArea: Boolean): Color {
    return when (state) {
        VoiceRecorderUtil.RecordingState.Idle -> Color.Gray
        VoiceRecorderUtil.RecordingState.Starting -> Color.Blue
        VoiceRecorderUtil.RecordingState.Recording -> {
            if (isInCancelArea) Color.Red else Color.Red
        }
        VoiceRecorderUtil.RecordingState.Stopping -> Color.Blue
        VoiceRecorderUtil.RecordingState.Processing -> Color.Blue
        is VoiceRecorderUtil.RecordingState.Success -> Color.Green
        is VoiceRecorderUtil.RecordingState.Error -> Color.Red
    }
}