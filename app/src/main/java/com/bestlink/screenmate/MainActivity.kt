package com.bestlink.screenmate

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import com.bestlink.screenmate.net.Host
import com.bestlink.screenmate.net.HostScanner
import com.bestlink.screenmate.net.WsClient
import com.bestlink.screenmate.config.ConfigManager
import com.bestlink.screenmate.util.UUIDManager
import kotlinx.coroutines.*
import java.io.File

class MainActivity : ComponentActivity() {
    // 当前选中的主机集合（IP）
    private var selectedIps: Set<String> = emptySet()

    private val appName = "ScreenMate"
    // 使用UUID作为设备标识
    private lateinit var deviceId: String

    private var nfcAdapter: NfcAdapter? = null
    private var readerEnabled = false

    // 管理 WebSocket 客户端
    private val clients = mutableMapOf<String, WsClient>() // key: ip
    // NFC读取的数据
    private var nfcData: String? = null
    // 配置管理器
    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 主界面允许熄屏，移除屏幕常亮标志
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        configManager = ConfigManager(this)
        
        // 初始化设备ID
        deviceId = UUIDManager.getOrCreateUUID(this)

        setContent {
            MyApplicationUI(onSelectionChanged = { selectedIps = it })
        }
    }

    override fun onResume() {
        super.onResume()
        enableNfcReader()
    }

    override fun onPause() {
        super.onPause()
        disableNfcReader()
    }

    // 音量键拦截：使用专门的音量键映射
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val config = configManager.loadConfig()
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                sendToSelected(config.volumeKeymap["VOLUME_UP"] ?: "Left")
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                sendToSelected(config.volumeKeymap["VOLUME_DOWN"] ?: "Right")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
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

    // 检查是否连接到WiFi
    private fun isWifiConnected(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        if (network == null) {
            return false
        }
        
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
        return networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    // 将 Tag ID 转为 hex 作为“设备代码”，匹配主机 id 并置为选中
    private fun onTagDiscovered(tag: Tag) {
        val idHex = tag.id.joinToString("") { b -> "%02X".format(b) }
        // 更新NFC数据
        val nfcInfo = "NFC标签ID: $idHex\n技术类型: ${tag.techList.joinToString(", ")}"
        nfcData = nfcInfo
        // 在 Compose state 中标记选中（通过 mutableStateOf 的 setter）
        selectById(idHex)
        // 更新UI显示
        updateNfcDisplay?.invoke(nfcInfo)
        
        // 检查是否有匹配的主机，如果有则自动跳转到控制界面
        checkAndNavigateToControl(idHex)
    }
    
    // 检查NFC标签ID是否匹配设备列表中的主机，如果匹配则自动跳转
    private fun checkAndNavigateToControl(tagId: String) {
        // 通过回调函数获取当前的主机列表
        getCurrentHosts?.invoke()?.let { hosts ->
            val matchedHost = hosts.find { it.tagId?.equals(tagId, ignoreCase = true) == true }
            if (matchedHost != null) {
                android.util.Log.d("NFC", "找到匹配的主机: ${matchedHost.name ?: matchedHost.ip}, 自动跳转到控制界面")
                runOnUiThread {
                    val intent = Intent(this, ControlActivity::class.java)
                    intent.putExtra("host", matchedHost)
                    // 传递完整的主机列表，确保后续NFC切换能正常工作
                    intent.putExtra("hosts", hosts.toTypedArray())
                    startActivity(intent)
                }
            } else {
                android.util.Log.d("NFC", "未找到匹配Tag ID的主机: $tagId")
            }
        }
    }

    // Compose UI 与状态
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun MyApplicationUI(onSelectionChanged: (Set<String>) -> Unit = {}) {
        var hosts by remember { mutableStateOf(listOf<Host>()) }
        var selected by remember { mutableStateOf(setOf<String>()) } // ip 集合
        var scanning by remember { mutableStateOf(false) }
        var cidrInput by remember { mutableStateOf(TextFieldValue("${HostScanner.getLocalIp(this@MainActivity)}/24")) }
        var portInput by remember { mutableStateOf(TextFieldValue("56789")) }
        var inputError by remember { mutableStateOf<String?>(null) }
        val uiScope = rememberCoroutineScope()
        var scanningIps by remember { mutableStateOf(listOf<String>()) }
        // NFC数据显示
        var nfcDisplayData by remember { mutableStateOf<String?>(null) }
        // 配置相关状态
        var showConfig by remember { mutableStateOf(false) }
        var keymapConfig by remember { mutableStateOf(configManager.loadConfig()) }

        // 应用启动时读取CIDR和端口文件
        LaunchedEffect(Unit) {
            val cidrFile = File(cacheDir, "cidr.txt")
            if (cidrFile.exists()) {
                val savedCidr = cidrFile.readText().trim()
                if (savedCidr.isNotEmpty()) {
                    cidrInput = TextFieldValue(savedCidr)
                }
            }
            
            val portFile = File(cacheDir, "port.txt")
            if (portFile.exists()) {
                val savedPort = portFile.readText().trim()
                if (savedPort.isNotEmpty()) {
                    portInput = TextFieldValue(savedPort)
                }
            }
        }

        fun toggleAll(selectAll: Boolean) {
            selected = if (selectAll) hosts.map { it.ip }.toSet() else emptySet()
            onSelectionChanged(selected)
        }

        fun toggleOne(ip: String) {
            selected = if (selected.contains(ip)) selected - ip else selected + ip
            onSelectionChanged(selected)
        }

        // 允许 NFC 选择更新
        fun selectByIdInternal(idHex: String) {
            val matched = hosts.find { it.id?.equals(idHex, ignoreCase = true) == true }
            if (matched != null) {
                selected = setOf(matched.ip)
            }
        }
        // 暴露到 Activity
        selectById = { id ->
            selectByIdInternal(id)
            onSelectionChanged(selected)
        }

        // 监听NFC数据变化
        LaunchedEffect(nfcData) {
            nfcData?.let { data ->
                nfcDisplayData = data
            }
        }

        // 设置NFC显示更新回调
        LaunchedEffect(Unit) {
            updateNfcDisplay = { data ->
                nfcDisplayData = data
            }
        }
        
        // 设置获取当前主机列表的回调
        LaunchedEffect(Unit) {
            getCurrentHosts = { hosts }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("多屏助手") },
                    actions = {
                        IconButton(onClick = { showConfig = true }) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Default.Settings,
                                contentDescription = "配置"
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // CIDR输入框和回填按钮行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = cidrInput,
                        onValueChange = {
                            cidrInput = it
                            inputError = null
                        },
                        label = { Text("扫描目标") },
                        placeholder = { Text("cidr: 192.168.1.6/24  socket: 192.168.1.6:12345") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    
                    // 回填当前WiFi IP按钮
                    IconButton(
                        onClick = {
                            // 检查WiFi连接
                            if (!isWifiConnected()) {
                                Toast.makeText(this@MainActivity, "请先连接到WiFi网络", Toast.LENGTH_LONG).show()
                                return@IconButton
                            }
                            
                            val currentIp = HostScanner.getLocalIp(this@MainActivity)
                            if (currentIp.isEmpty() || currentIp == "0.0.0.0") {
                                Toast.makeText(this@MainActivity, "无法获取当前WiFi IP地址", Toast.LENGTH_LONG).show()
                                return@IconButton
                            }
                            
                            // 构造CIDR格式（使用默认掩码24）
                            val newCidr = "$currentIp/24"
                            cidrInput = TextFieldValue(newCidr)
                            
                            // 保存到文件
                            val cidrFile = File(cacheDir, "cidr.txt")
                            cidrFile.writeText(newCidr)
                            
                            Toast.makeText(this@MainActivity, "已回填当前WiFi CIDR: $newCidr", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "回填当前WiFi CIDR"
                        )
                    }
                }

                // 扫描进度列表
                if (scanning && scanningIps.isNotEmpty()) {
                    Text("正在扫描以下IP：")
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                    ) {
                        items(scanningIps) { ip ->
                            Text(ip)
                        }
                    }
                }

                // 扫描按钮
                Button(
                    enabled = !scanning,
                    onClick = {
                        // 检查WiFi连接（对于Socket模式，不强制要求WiFi）
                        val scanTarget = HostScanner.parseScanTarget(cidrInput.text)
                        if (scanTarget == null) {
                            // 提供更详细的错误提示
                            val errorMsg = when {
                                cidrInput.text.trim().isEmpty() -> "请输入扫描目标"
                                else -> "请输入合法的扫描目标：\n1. CIDR格式：IP地址/掩码（如192.168.1.1/24）\n2. Socket格式：IP:端口 或 域名:端口（如192.168.1.100:56789）"
                            }
                            Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        
                        // 对于CIDR模式，检查WiFi连接
                        if (scanTarget.type == HostScanner.TargetType.CIDR && !isWifiConnected()) {
                            Toast.makeText(this@MainActivity, "CIDR扫描需要WiFi网络连接", Toast.LENGTH_LONG).show()
                            return@Button
                        }
                        
                        scanning = true
                        
                        // 保存输入到文件
                        val cidrFile = File(cacheDir, "cidr.txt")
                        cidrFile.writeText(cidrInput.text)
                        
                        scanningIps = emptyList()
                        CoroutineScope(Dispatchers.IO).launch {
                            // 使用ConfigManager中的端口配置（对于CIDR模式）
                            val config = configManager.loadConfig()
                            val defaultPort = config.scanPort
                            
                            val found = when (scanTarget.type) {
                                HostScanner.TargetType.CIDR -> {
                                    val cidrInfo = HostScanner.parseCidr(scanTarget.target)
                                    if (cidrInfo != null) {
                                        HostScanner.scanCidr(
                                            cidrInfo,
                                            appName,
                                            deviceId,
                                            port = defaultPort,
                                            onProgress = { ip -> uiScope.launch { scanningIps = scanningIps + ip } },
                                            onFinish = { ip -> uiScope.launch { scanningIps = scanningIps - ip } }
                                        )
                                    } else {
                                        emptyList()
                                    }
                                }
                                HostScanner.TargetType.SOCKET -> {
                                    val port = scanTarget.port ?: defaultPort
                                    HostScanner.scanSocket(
                                        scanTarget.target,
                                        port,
                                        appName,
                                        deviceId,
                                        onProgress = { ip -> uiScope.launch { scanningIps = scanningIps + ip } },
                                        onFinish = { ip -> uiScope.launch { scanningIps = scanningIps - ip } }
                                    )
                                }
                            }
                            
                            withContext(Dispatchers.Main) {
                                hosts = found
                                startClients(found)
                                
                                // 保存主机列表到SharedPreferences，确保NFC切换功能能获取正确的端口信息
                                saveHostsToSharedPreferences(found)
                                
                                scanning = false
                                // 清除选择，或按需保留
                                selected = emptySet()
                                onSelectionChanged(selected)
                                inputError = null
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (scanning) "扫描中..." else "扫描") }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f, fill = true)
                ) {
                    items(hosts, key = { it.ip }) { h ->
                        var showHostSettingsDialog by remember { mutableStateOf(false) }
                        
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onTap = {
                                            // 点击跳转到控制界面
                                            val intent = Intent(this@MainActivity, ControlActivity::class.java)
                                            intent.putExtra("host", h)
                                            // 传递当前主机列表，用于NFC匹配
                                            intent.putExtra("hosts", hosts.toTypedArray())
                                            startActivity(intent)
                                        },
                                        onLongPress = {
                                            // 长按显示设置对话框
                                            showHostSettingsDialog = true
                                        }
                                    )
                                }
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp)
                            ) {
                                Text("名称: ${h.name ?: "-"}")
                                Text("Tag ID: ${h.tagId ?: "-"}")
                                Text("Socket: ${h.ip}:${h.port}")
                                Text("状态: ${if (h.connected) "已连接" else "未连接"}")
                            }
                        }

                        // 主机设置对话框
                        if (showHostSettingsDialog) {
                            AlertDialog(
                                onDismissRequest = { showHostSettingsDialog = false },
                                title = { Text("主机设置") },
                                text = { Text("是否要设置此主机的名称和Tag ID？") },
                                confirmButton = {
                                    Button(
                                        onClick = {
                                            showHostSettingsDialog = false
                                            // 跳转到主机设置界面
                                            val intent = Intent(this@MainActivity, HostSettingsActivity::class.java)
                                            intent.putExtra("host", h)
                                            startActivity(intent)
                                        }
                                    ) {
                                        Text("主机设置")
                                    }
                                },
                                dismissButton = {
                                    Button(
                                        onClick = { showHostSettingsDialog = false }
                                    ) {
                                        Text("取消")
                                    }
                                }
                            )
                        }
                    }
                }

                // NFC数据显示区域
                nfcDisplayData?.let { data ->
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "NFC读取数据",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Button(
                                    onClick = { nfcDisplayData = null },
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("清除")
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = data,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }


            }
        }

        // 配置对话框
        if (showConfig) {
            // 获取资源中的按键选项
            val localContext = LocalContext.current
            val keyOptions = localContext.resources.getStringArray(R.array.key_options)
            val keyDisplayNames = localContext.resources.getStringArray(R.array.key_display_names)
            val screenKeyLabels = localContext.resources.getStringArray(R.array.screen_key_labels)
            val screenKeyValues = localContext.resources.getStringArray(R.array.screen_key_values)
            val volumeKeyLabels = localContext.resources.getStringArray(R.array.volume_key_labels)
            val volumeKeyValues = localContext.resources.getStringArray(R.array.volume_key_values)
            
            Dialog(
                onDismissRequest = { showConfig = false }
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .heightIn(max = 500.dp),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "通用配置",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // 可滚动的内容区域
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .weight(1f, fill = false)
                        ) {
                            // 端口配置选项（提升到第一排）
                            Text("扫描端口配置", style = MaterialTheme.typography.titleSmall)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("扫描端口:")
                                OutlinedTextField(
                                    value = keymapConfig.scanPort.toString(),
                                    onValueChange = { newValue ->
                                        val port = newValue.toIntOrNull() ?: 56789
                                        keymapConfig = keymapConfig.copy(scanPort = port)
                                    },
                                    modifier = Modifier.width(150.dp).height(48.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            // 屏幕按键映射
                            Text("屏幕按键映射", style = MaterialTheme.typography.titleSmall)
                            screenKeyValues.forEachIndexed { index, key ->
                                val currentValue = keymapConfig.keymap[key] ?: when (key) {
                                    "U" -> "Up"
                                    "D" -> "Down"
                                    "L" -> "Left"
                                    "R" -> "Right"
                                    "C" -> "Space"
                                    else -> "Left"
                                }
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(screenKeyLabels[index])
                                    
                                    var expanded by remember { mutableStateOf(false) }
                                    Box(
                                        modifier = Modifier.width(150.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = currentValue,
                                            onValueChange = {},
                                            readOnly = true,
                                            modifier = Modifier.fillMaxWidth().height(48.dp)
                                        )
                                        
                                        // 下拉箭头和点击区域
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .clickable { expanded = true }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.ArrowDropDown,
                                                contentDescription = "下拉选择",
                                                modifier = Modifier
                                                    .align(Alignment.CenterEnd)
                                                    .padding(end = 8.dp)
                                            )
                                        }
                                        
                                        DropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false }
                                        ) {
                                            keyOptions.forEach { option ->
                                                DropdownMenuItem(
                                                    text = { 
                                                        val displayIndex = keyOptions.indexOf(option)
                                                        Text(keyDisplayNames.getOrElse(displayIndex) { option })
                                                    },
                                                    onClick = {
                                                        val newKeymap = keymapConfig.keymap.toMutableMap()
                                                        newKeymap[key] = option
                                                        keymapConfig = keymapConfig.copy(keymap = newKeymap)
                                                        expanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // 音量键映射
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("音量键映射", style = MaterialTheme.typography.titleSmall)
                            
                            volumeKeyValues.forEachIndexed { index, volumeKey ->
                                val currentValue = keymapConfig.volumeKeymap[volumeKey] ?: when (volumeKey) {
                                    "VOLUME_UP" -> "Left"
                                    "VOLUME_DOWN" -> "Right"
                                    else -> "Left"
                                }
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(volumeKeyLabels[index])
                                    
                                    var expanded by remember { mutableStateOf(false) }
                                    Box(
                                        modifier = Modifier.width(150.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = currentValue,
                                            onValueChange = {},
                                            readOnly = true,
                                            modifier = Modifier.fillMaxWidth().height(48.dp)
                                        )
                                        
                                        // 下拉箭头和点击区域
                                        Box(
                                            modifier = Modifier
                                                .matchParentSize()
                                                .clickable { expanded = true }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.ArrowDropDown,
                                                contentDescription = "下拉选择",
                                                modifier = Modifier
                                                    .align(Alignment.CenterEnd)
                                                    .padding(end = 8.dp)
                                            )
                                        }
                                        
                                        DropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false }
                                        ) {
                                            keyOptions.forEach { option ->
                                                DropdownMenuItem(
                                                    text = { 
                                                        val displayIndex = keyOptions.indexOf(option)
                                                        Text(keyDisplayNames.getOrElse(displayIndex) { option })
                                                    },
                                                    onClick = {
                                                        val newVolumeKeymap = keymapConfig.volumeKeymap.toMutableMap()
                                                        newVolumeKeymap[volumeKey] = option
                                                        keymapConfig = keymapConfig.copy(volumeKeymap = newVolumeKeymap)
                                                        expanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // 震动控制选项
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("控制震动:")
                                Switch(
                                    checked = keymapConfig.enableVibration,
                                    onCheckedChange = { checked ->
                                        keymapConfig = keymapConfig.copy(enableVibration = checked)
                                    }
                                )
                            }
                            

                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // 按钮区域
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { showConfig = false },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text("取消")
                            }
                            
                            Button(
                                onClick = {
                                    if (configManager.saveConfig(keymapConfig)) {
                                        showConfig = false
                                    }
                                }
                            ) {
                                Text("保存")
                            }
                        }
                    }
                }
            }
        }
    }

    // 用于在 Composable 中调用的选择回调占位，运行时会被赋值
    private var selectById: (String) -> Unit = { }
    // 用于更新NFC显示数据的回调
    private var updateNfcDisplay: ((String) -> Unit)? = null
    // 用于获取当前主机列表的回调
    private var getCurrentHosts: (() -> List<Host>)? = null

    private fun startClients(found: List<Host>) {
        found.forEach { h ->
            val client = WsClient(h, name = appName, initialId = deviceId, useTls = true)
            clients[h.ip] = client
            // 已在扫描阶段完成首次连接并设置 connected；这里补充心跳
            CoroutineScope(Dispatchers.IO).launch {
                client.startHeartbeat(this)
            }
        }
    }

    private fun sendCommands(selectedIps: Set<String>, content: String) {
        selectedIps.forEach { ip ->
            CoroutineScope(Dispatchers.IO).launch {
                clients[ip]?.ensureConnectedAndSendKey(content)
            }
        }
    }

    private fun sendToSelected(content: String) {
        // 仅向当前选中的主机发送
        selectedIps.forEach { ip ->
            CoroutineScope(Dispatchers.IO).launch {
                clients[ip]?.ensureConnectedAndSendKey(content)
            }
        }
    }
    
    // 保存主机列表到SharedPreferences，确保NFC切换功能能获取正确的端口信息
    private fun saveHostsToSharedPreferences(hosts: List<Host>) {
        try {
            val jsonArray = org.json.JSONArray()
            hosts.forEach { host ->
                val hostObj = org.json.JSONObject()
                hostObj.put("ip", host.ip)
                hostObj.put("port", host.port)
                host.name?.let { hostObj.put("name", it) }
                host.id?.let { hostObj.put("id", it) }
                host.tagId?.let { hostObj.put("tagId", it) }
                hostObj.put("connected", host.connected)
                jsonArray.put(hostObj)
            }
            
            val sharedPrefs = getSharedPreferences("screenmate_hosts", MODE_PRIVATE)
            sharedPrefs.edit().putString("hosts_list", jsonArray.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "保存主机列表失败: ${e.message}")
        }
    }
}