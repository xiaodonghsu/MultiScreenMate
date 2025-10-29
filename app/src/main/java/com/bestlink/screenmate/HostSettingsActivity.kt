package com.bestlink.screenmate

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import com.bestlink.screenmate.net.Host
import com.bestlink.screenmate.net.WsClient
import com.bestlink.screenmate.util.UUIDManager
import kotlinx.coroutines.*
import org.json.JSONObject

class HostSettingsActivity : ComponentActivity() {
    private lateinit var host: Host
    private lateinit var wsClient: WsClient
    private var nfcAdapter: NfcAdapter? = null
    private var readerEnabled = false
    private lateinit var deviceId: String
    
    // NFC读取的数据
    private var _nfcTagId by mutableStateOf<String?>(null)
    private val nfcTagId: String? get() = _nfcTagId

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        deviceId = UUIDManager.getOrCreateUUID(this)
        
        // 从Intent获取主机信息（兼容API 33+的新方法）
        host = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra("host", Host::class.java)!!
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("host") as Host
        }
        
        // 创建WebSocket客户端
        wsClient = WsClient(host, name = "ScreenMate", initialId = deviceId, useTls = true)
        
        setContent {
            HostSettingsScreen(
                host = host,
                nfcTagId = nfcTagId,
                onNfcTagRead = { tagId -> 
                    if (tagId.isEmpty()) {
                        // 重置NFC标签ID
                        _nfcTagId = null
                        // 通过回调更新UI
                        updateNfcDisplay?.invoke("")
                        // 显示提示信息
                        showMessage?.invoke("NFC标签ID已重置，请贴近新的NFC标签")
                    } else {
                        // 更新NFC标签ID
                        _nfcTagId = tagId
                        // 通过回调更新UI
                        updateNfcDisplay?.invoke(tagId)
                    }
                },
                onSaveSettings = { name, tagId -> saveHostSettings(name, tagId) },
                onShowMessage = { message -> showMessage?.invoke(message) },
                onShowError = { error -> showError?.invoke(error) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        enableNfcReader()
        // 连接WebSocket
        CoroutineScope(Dispatchers.IO).launch {
            wsClient.connect()
        }
    }

    override fun onPause() {
        super.onPause()
        disableNfcReader()
        wsClient.disconnect()
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

    // 将 Tag ID 转为 hex
    private fun onTagDiscovered(tag: Tag) {
        val idHex = tag.id.joinToString("") { b -> "%02X".format(b) }
        _nfcTagId = idHex
        // 更新UI显示
        updateNfcDisplay?.invoke(idHex)
    }

    private fun saveHostSettings(name: String, tagId: String?) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 发送设置命令
                val content = JSONObject().apply {
                    put("name", name)
                    if (tagId != null) {
                        put("tag_id", tagId)
                    }
                }
                val payload = JSONObject().apply {
                    put("id", deviceId)
                    put("command", "set")
                    put("content", content)
                }
                
                val success = wsClient.sendRawMessage(payload.toString())
                if (success) {
                    // 等待响应
                    delay(1000)
                    
                    // 发送handshake命令更新主机信息
                    wsClient.sendHandshake()
                    
                    // 返回结果
                    withContext(Dispatchers.Main) {
                        setResult(RESULT_OK)
                        finish()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        // 显示错误信息
                        showError?.invoke("发送设置命令失败")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError?.invoke("设置失败: ${e.message}")
                }
            }
        }
    }

    // 用于更新NFC显示的回调
    private var updateNfcDisplay: ((String) -> Unit)? = null
    private var showError: ((String) -> Unit)? = null
    private var showMessage: ((String) -> Unit)? = null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostSettingsScreen(
    host: Host,
    nfcTagId: String?,
    onNfcTagRead: (String) -> Unit,
    onSaveSettings: (String, String?) -> Unit,
    onShowMessage: (String) -> Unit = {},
    onShowError: (String) -> Unit = {}
) {
    var hostName by remember { mutableStateOf(TextFieldValue(host.name ?: "")) }
    var currentTagId by remember { mutableStateOf(nfcTagId ?: host.tagId ?: "") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    
    // 监听NFC标签读取
    LaunchedEffect(nfcTagId) {
        nfcTagId?.let { tagId ->
            currentTagId = tagId
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("主机设置") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 主机信息显示
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("当前主机信息", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Socket: ${host.ip}:${host.port}")
                    Text("ID: ${host.id ?: "-"}")
                }
            }

            // 主机名称设置
            OutlinedTextField(
                value = hostName,
                onValueChange = { 
                    if (it.text.length <= 20) {
                        hostName = it
                        errorMessage = null
                    }
                },
                label = { Text("主机名称") },
                supportingText = {
                    Text("最大长度20字符，当前${hostName.text.length}/20")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().height(40.dp)
            )

            // Tag ID设置
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = currentTagId,
                    onValueChange = { 
                        currentTagId = it
                        errorMessage = null
                    },
                    label = { Text("Tag ID") },
                    supportingText = {
                        Text("通过NFC读取或手动输入")
                    },
                    singleLine = true,
                    modifier = Modifier.weight(0.7f).height(40.dp)
                )

                Button(
                    onClick = {
                        // NFC读取按钮 - 启动NFC读取模式
                        errorMessage = "请将NFC标签贴近设备"
                        onShowMessage("请将NFC标签贴近设备")
                        // 通过回调通知Activity重置NFC标签ID
                        onNfcTagRead("")
                    },
                    modifier = Modifier.weight(0.3f).height(40.dp)
                ) {
                    Text("NFC读取")
                }
            }

            // 错误信息显示
            errorMessage?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // 提交按钮
            Button(
                onClick = {
                    if (hostName.text.isBlank()) {
                        errorMessage = "请输入主机名称"
                        return@Button
                    }
                    isLoading = true
                    onSaveSettings(hostName.text, if (currentTagId.isBlank()) null else currentTagId)
                },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("保存设置")
                }
            }
        }
    }
}