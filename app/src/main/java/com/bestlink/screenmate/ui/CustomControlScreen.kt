package com.bestlink.screenmate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.platform.LocalDensity
import android.util.Log
import com.bestlink.screenmate.config.ButtonConfig
import com.bestlink.screenmate.config.ButtonLayoutManager
import com.bestlink.screenmate.config.ConfigManager
import com.bestlink.screenmate.net.Host
import com.bestlink.screenmate.net.WsClient
import kotlinx.coroutines.launch

@Composable
fun CustomControlScreen(
    host: Host,
    wsClient: WsClient,
    configManager: ConfigManager
) {
    val context = LocalContext.current
    val buttonLayoutManager = remember { ButtonLayoutManager(context) }
    var buttonLayout by remember { mutableStateOf(buttonLayoutManager.loadLayout()) }
    var editMode by remember { mutableStateOf(false) }
    var selectedButtonId by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    
    // 图标资源 - 使用文本标签替代图标
    val buttonLabels = mapOf(
        "U" to "↑",
        "D" to "↓", 
        "L" to "←",
        "R" to "→",
        "C" to "●"
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.LightGray)
            .clipToBounds()
    ) {
        // 共享的画布容器
        Box(
            modifier = Modifier
                .size(400.dp)
                .background(Color.White.copy(alpha = 0.1f))
                .align(Alignment.Center)
        ) {
            // 渲染所有按钮到同一个画布
            buttonLayout.buttons.forEach { config ->
                DraggableButton(
                    config = config,
                    label = buttonLabels[config.key] ?: "?",
                    editMode = editMode,
                    isSelected = selectedButtonId == config.key,
                    onConfigChange = { newConfig ->
                        buttonLayout = buttonLayout.copy(
                            buttons = buttonLayout.buttons.map { 
                                if (it.key == newConfig.key) newConfig else it 
                            }
                        )
                    },
                    onButtonClick = { key ->
                        if (!editMode) {
                            val keymap = configManager.loadConfig().keymap
                            val command = when (key) {
                                "U" -> keymap["U"] ?: "Up"
                                "D" -> keymap["D"] ?: "Down"
                                "L" -> keymap["L"] ?: "Left"
                                "R" -> keymap["R"] ?: "Right"
                                "C" -> keymap["C"] ?: "Space"
                                else -> ""
                            }
                            if (command.isNotEmpty()) {
                                coroutineScope.launch {
                                    wsClient.ensureConnectedAndSendKey(command)
                                }
                            }
                        } else {
                            // 编辑模式下点击按钮：选中或取消选中
                            selectedButtonId = if (selectedButtonId == key) null else key
                            Log.d("CustomControlScreen", "编辑模式选中按钮: $selectedButtonId")
                        }
                    }
                )
            }
        }
        
        // 编辑模式控制按钮和主机信息
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Button(
                    onClick = {
                        editMode = !editMode
                        if (!editMode) {
                            // 保存布局
                            coroutineScope.launch {
                                buttonLayoutManager.saveLayout(buttonLayout)
                            }
                        }
                    }
                ) {
                    Text(if (editMode) "保存布局" else "编辑布局")
                }
                
                // 主机信息卡片
                Card(
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text("主机: ${host.name ?: host.ip}", style = MaterialTheme.typography.bodySmall)
                        Text("状态: ${if (host.connected) "已连接" else "未连接"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun DraggableButton(
    config: ButtonConfig,
    label: String,
    editMode: Boolean,
    isSelected: Boolean,
    onConfigChange: (ButtonConfig) -> Unit,
    onButtonClick: (String) -> Unit
) {
    var position by remember { mutableStateOf(Offset(config.x, config.y)) }
    var size by remember { mutableStateOf(Offset(config.width, config.height)) }
    val density = LocalDensity.current
    
    Box(
        modifier = Modifier
            .offset(x = (position.x * 400).dp, y = (position.y * 400).dp)
            .size(width = (size.x * 400).dp, height = (size.y * 400).dp)
            .pointerInput(editMode, isSelected, density) {
                if (editMode) {
                    Log.d("DraggableButton", "开始手势检测: label=$label, editMode=$editMode, isSelected=$isSelected")
                    detectTransformGestures { centroid, pan, zoom, rotation ->
                        Log.d("DraggableButton", "检测到手势: label=$label, centroid=$centroid, pan=$pan, zoom=$zoom, rotation=$rotation, editMode=$editMode, sSelected=$isSelected")
                        
                        if (isSelected) {
                            // 选中状态下：处理缩放
                            if (zoom != 1f) {
                                Log.d("DraggableButton", "处理缩放: 当前尺寸=$size, 缩放因子=$zoom")
                                // 计算新的尺寸（限制在最小0.05到最大0.67之间）
                                val newWidth = (size.x * zoom).coerceIn(0.05f, 0.67f)
                                val newHeight = (size.y * zoom).coerceIn(0.05f, 0.67f)
                                
                                Log.d("DraggableButton", "新尺寸: width=$newWidth, height=$newHeight")
                                
                                // 保持中心点稳定
                                val scaleChangeX = (newWidth - size.x) / 2f
                                val scaleChangeY = (newHeight - size.y) / 2f
                                
                                position = Offset(
                                    (position.x - scaleChangeX).coerceIn(0f, 1f - newWidth),
                                    (position.y - scaleChangeY).coerceIn(0f, 1f - newHeight)
                                )
                                size = Offset(newWidth, newHeight)
                                
                                Log.d("DraggableButton", "缩放后位置: $position, 尺寸: $size")
                                
                                onConfigChange(config.copy(
                                    x = position.x,
                                    y = position.y,
                                    width = size.x,
                                    height = size.y
                                ))
                            }
                        } else {
                            // 未选中状态下：处理平移（拖拽）
                            if (pan != Offset.Zero) {
                                Log.d("DraggableButton", "处理平移: pan=$pan")
                                with(density) {
                                    val panDpX = pan.x.toDp()
                                    val panDpY = pan.y.toDp()
                                    
                                    position = Offset(
                                        (position.x + panDpX.value / 400f).coerceIn(0f, 1f - size.x),
                                        (position.y + panDpY.value / 400f).coerceIn(0f, 1f - size.y)
                                    )
                                }
                                Log.d("DraggableButton", "平移后位置: $position")
                                
                                onConfigChange(config.copy(
                                    x = position.x,
                                    y = position.y,
                                    width = size.x,
                                    height = size.y
                                ))
                            }
                        }
                    }
                }
            }
    ) {
        // 按钮本身（仅处理点击）
        Button(
            onClick = { 
                onButtonClick(config.key)
            },
            modifier = Modifier.fillMaxSize(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (editMode) {
                    if (isSelected) Color.Red.copy(alpha = 0.5f) 
                    else Color.Blue.copy(alpha = 0.3f)
                } else MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineMedium
            )
        }
    }
}

// 辅助函数：获取资源ID（保留以备后用）
private fun getResourceId(context: android.content.Context, name: String, defType: String): Int {
    return context.resources.getIdentifier(name, defType, context.packageName)
}