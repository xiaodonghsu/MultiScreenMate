package com.bestlink.screenmate

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.bestlink.screenmate.config.ConfigManager
import com.bestlink.screenmate.net.Host
import com.bestlink.screenmate.net.WsClient
import com.bestlink.screenmate.ui.CustomControlScreen
import com.bestlink.screenmate.util.UUIDManager
import kotlinx.coroutines.*
import java.io.File

class ControlActivity : ComponentActivity() {
    private lateinit var configManager: ConfigManager
    private lateinit var host: Host
    private lateinit var wsClient: WsClient
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 控制界面保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // 从Intent获取主机信息
        host = intent.getSerializableExtra("host") as Host
        configManager = ConfigManager(this)
        
        // 读取端口配置
        val portFile = File(cacheDir, "port.txt")
        val port = if (portFile.exists()) {
            portFile.readText().trim().toIntOrNull() ?: 56789
        } else {
            56789
        }
        
        // 使用配置的端口创建主机对象
        val configuredHost = host.copy(port = port)
        
        // 创建WebSocket客户端
        val deviceId = UUIDManager.getOrCreateUUID(this)
        wsClient = WsClient(configuredHost, name = "ScreenMate", initialId = deviceId, useTls = true)
        
        setContent {
            CustomControlScreen(host = configuredHost, wsClient = wsClient, configManager = configManager)
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 连接WebSocket
        CoroutineScope(Dispatchers.IO).launch {
            wsClient.connect()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // 断开WebSocket连接
        wsClient.disconnect()
    }
}

// 旧的ControlScreen已被替换为CustomControlScreen