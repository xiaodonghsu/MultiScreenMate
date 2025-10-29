package com.bestlink.screenmate

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
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
import org.json.JSONArray

class ControlActivity : ComponentActivity() {
    private lateinit var configManager: ConfigManager
    private lateinit var host: Host
    private lateinit var wsClient: WsClient
    private var nfcAdapter: NfcAdapter? = null
    private var readerEnabled = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 控制界面保持屏幕常亮
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // 从Intent获取主机信息
        host = intent.getSerializableExtra("host") as Host
        configManager = ConfigManager(this)
        
        // 使用主机对象中保存的端口（从扫描结果中获取）
        val configuredHost = host
        
        // 创建WebSocket客户端
        val deviceId = UUIDManager.getOrCreateUUID(this)
        wsClient = WsClient(configuredHost, name = "ScreenMate", initialId = deviceId, useTls = true)
        
        setContent {
            CustomControlScreen(host = configuredHost, wsClient = wsClient, configManager = configManager)
        }
        
        // 初始化NFC适配器
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
    }
    
    override fun onResume() {
        super.onResume()
        // 连接WebSocket
        CoroutineScope(Dispatchers.IO).launch {
            wsClient.connect()
        }
        // 启用NFC读取器
        enableNfcReader()
    }
    
    override fun onPause() {
        super.onPause()
        // 断开WebSocket连接
        wsClient.disconnect()
        // 禁用NFC读取器
        disableNfcReader()
    }
    
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 处理音量键事件
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                handleVolumeKey("VOLUME_UP")
                return true  // 消费事件，防止系统音量调节
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                handleVolumeKey("VOLUME_DOWN")
                return true  // 消费事件，防止系统音量调节
            }
        }
        return super.onKeyDown(keyCode, event)
    }
    
    private fun handleVolumeKey(volumeKey: String) {
        val config = configManager.loadConfig()
        val command = config.volumeKeymap[volumeKey]
        
        if (command != null) {
            // 发送对应的按键命令，使用 keypress 作为命令参数
            CoroutineScope(Dispatchers.IO).launch {
                val id = wsClient.getHostId()
                val payload = "{\"id\":\"$id\",\"command\":\"keypress\",\"content\":\"$command\"}"
                wsClient.sendRawMessage(payload)
                
                // 根据配置决定是否震动
                if (config.enableVibration) {
                    try {
                        val vibratorManager = getSystemService(android.os.VibratorManager::class.java)
                        val vibrator = vibratorManager.defaultVibrator
                        vibrator.vibrate(android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_CLICK))
                    } catch (e: Exception) {
                        Log.e("VolumeKey", "震动失败: ${e.message}")
                    }
                }
            }
        }
    }
    
    private fun enableNfcReader() {
        if (readerEnabled) return
        nfcAdapter?.enableReaderMode(
            this,
            { tag -> onTagDiscovered(tag) },
            NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
            null
        )
        readerEnabled = true
    }

    private fun disableNfcReader() {
        nfcAdapter?.disableReaderMode(this)
        readerEnabled = false
    }

    // 处理NFC标签发现事件
    private fun onTagDiscovered(tag: Tag) {
        val idHex = tag.id.joinToString("") { b -> "%02X".format(b) }
        android.util.Log.d("NFC", "检测到NFC标签: $idHex")
        
        // 检查Tag ID是否匹配主机列表中的主机
        checkAndSwitchHost(idHex)
    }
    
    // 检查Tag ID是否匹配主机列表，如果匹配则切换到对应主机的控制界面
    private fun checkAndSwitchHost(tagId: String) {
        // 从配置文件中读取保存的主机列表
        val hosts = loadHostsFromConfig()
        val matchedHost = hosts.find { it.tagId?.equals(tagId, ignoreCase = true) == true }
        
        if (matchedHost != null && matchedHost.ip != host.ip) {
            android.util.Log.d("NFC", "找到匹配的主机: ${matchedHost.name ?: matchedHost.ip}, 切换到控制界面")
            runOnUiThread {
                // 断开当前WebSocket连接
                wsClient.disconnect()
                
                // 创建新的Intent并切换到匹配的主机
                val intent = Intent(this, ControlActivity::class.java)
                intent.putExtra("host", matchedHost)
                // 传递完整的主机列表，确保后续切换也能正常工作
                intent.putExtra("hosts", hosts.toTypedArray())
                startActivity(intent)
                
                // 结束当前Activity
                finish()
            }
        } else if (matchedHost != null) {
            android.util.Log.d("NFC", "Tag ID匹配当前主机，无需切换")
        } else {
            android.util.Log.d("NFC", "未找到匹配Tag ID的主机: $tagId")
        }
    }
    
    // 从Intent或共享存储中加载主机列表
    private fun loadHostsFromConfig(): List<Host> {
        // 尝试从Intent中获取主机列表
        val hostsFromIntent = intent.getSerializableExtra("hosts") as? Array<Host>
        if (hostsFromIntent != null) {
            return hostsFromIntent.toList()
        }
        
        // 尝试从SharedPreferences中获取主机列表
        val sharedPrefs = getSharedPreferences("screenmate_hosts", MODE_PRIVATE)
        val hostsJson = sharedPrefs.getString("hosts_list", null)
        
        if (hostsJson != null) {
            try {
                // 简单的JSON解析实现（实际项目中应该使用更健壮的解析方式）
                return parseHostsFromJson(hostsJson)
            } catch (e: Exception) {
                android.util.Log.e("NFC", "解析主机列表失败: ${e.message}")
            }
        }
        
        // 如果都没有，返回包含当前主机的列表
        return listOf(host)
    }
    
    // 简单的JSON到Host列表解析
    private fun parseHostsFromJson(json: String): List<Host> {
        val hosts = mutableListOf<Host>()
        try {
            val jsonArray = org.json.JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val hostObj = jsonArray.getJSONObject(i)
                val host = Host(
                    ip = hostObj.getString("ip"),
                    port = hostObj.getInt("port"),
                    name = if (hostObj.has("name")) hostObj.getString("name") else null,
                    id = if (hostObj.has("id")) hostObj.getString("id") else null,
                    tagId = if (hostObj.has("tagId")) hostObj.getString("tagId") else null,
                    connected = hostObj.optBoolean("connected", false)
                )
                hosts.add(host)
            }
        } catch (e: Exception) {
            android.util.Log.e("NFC", "JSON解析错误: ${e.message}")
        }
        return hosts
    }
}

// 旧的ControlScreen已被替换为CustomControlScreen