package com.bestlink.screenmate.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import java.net.InetAddress

object HostScanner {

    // 获取当前设备的完整IP地址，如 192.168.1.23；未连接则返回 "0.0.0.0"
    fun getLocalIp(context: Context): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo.ipAddress
            val bytes = byteArrayOf(
                (ip and 0xFF).toByte(),
                (ip shr 8 and 0xFF).toByte(),
                (ip shr 16 and 0xFF).toByte(),
                (ip shr 24 and 0xFF).toByte()
            )
            InetAddress.getByAddress(bytes).hostAddress ?: "0.0.0.0"
        } catch (_: Exception) {
            "0.0.0.0"
        }
    }

    // 从 CIDR（如 "192.168.1.23/24"）解析CIDR信息；支持掩码24-32，返回CIDR对象
    data class CidrInfo(val baseIp: String, val mask: Int, val startIp: String, val endIp: String, val totalIps: Int)
    
    // 解析扫描目标，支持两种格式：
    // 1. CIDR格式：192.168.1.0/24
    // 2. Socket格式：192.168.1.100:56789 或 domain.com:56789
    data class ScanTarget(
        val type: TargetType,
        val target: String,
        val port: Int? = null
    )
    
    enum class TargetType {
        CIDR, // CIDR格式扫描
        SOCKET // 单机Socket格式扫描
    }
    
    fun parseScanTarget(input: String): ScanTarget? {
        val cleanInput = input.trim()
        if (cleanInput.isEmpty()) return null
        
        // 检查是否是Socket格式（包含冒号但不包含斜杠）
        if (cleanInput.contains(":") && !cleanInput.contains("/")) {
            val parts = cleanInput.split(":")
            if (parts.size == 2) {
                val target = parts[0].trim()
                val port = parts[1].trim().toIntOrNull()
                if (port != null && port in 1..65535) {
                    return ScanTarget(TargetType.SOCKET, target, port)
                }
            }
        }
        
        // 检查是否是CIDR格式
        if (cleanInput.contains("/")) {
            val cidrInfo = parseCidr(cleanInput)
            if (cidrInfo != null) {
                return ScanTarget(TargetType.CIDR, cleanInput)
            }
        }
        
        return null
    }
    
    fun parseCidr(cidr: String): CidrInfo? {
        // 严格处理空格：去除所有空格，包括CIDR内部
        val cleanCidr = cidr.replace("\\s+".toRegex(), "")
        if (cleanCidr.isEmpty()) return null
        
        val parts = cleanCidr.split("/")
        if (parts.size != 2) return null
        
        val ip = parts[0].trim()
        val mask = parts[1].trim().toIntOrNull() ?: return null
        if (mask < 24 || mask > 32) return null
        if (ip == "0.0.0.0") return null
        
        // 严格验证IP地址格式
        val octets = ip.split(".")
        if (octets.size != 4) return null
        
        // 验证每个IP段都是有效的数字
        for (octet in octets) {
            val num = octet.toIntOrNull() ?: return null
            if (num < 0 || num > 255) return null
        }
        
        // 计算网络地址和广播地址
        val ipInt = ipToInt(ip)
        val networkMask = (0xFFFFFFFFL shl (32 - mask)).toInt()
        val networkAddress = ipInt and networkMask
        val broadcastAddress = networkAddress or (networkMask.inv())
        
        // 计算可用IP范围（排除网络地址和广播地址）
        val startIp = if (mask == 32) networkAddress else networkAddress + 1
        val endIp = if (mask == 32) networkAddress else broadcastAddress - 1
        val totalIps = if (mask == 32) 1 else (endIp - startIp + 1)
        
        return CidrInfo(
            baseIp = ip,
            mask = mask,
            startIp = intToIp(startIp),
            endIp = intToIp(endIp),
            totalIps = totalIps
        )
    }
    
    private fun ipToInt(ip: String): Int {
        val octets = ip.split(".").map { it.toInt() }
        return (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]
    }
    
    private fun intToIp(ipInt: Int): String {
        return "${(ipInt shr 24) and 0xFF}.${(ipInt shr 16) and 0xFF}.${(ipInt shr 8) and 0xFF}.${ipInt and 0xFF}"
    }
    
    // 向后兼容的方法
    fun parseCidrPrefix(cidr: String): String? {
        val cidrInfo = parseCidr(cidr)
        return if (cidrInfo?.mask == 24) {
            val octets = cidrInfo.baseIp.split(".")
            "${octets[0]}.${octets[1]}.${octets[2]}"
        } else {
            null
        }
    }

    // 单机Socket扫描，扫描指定IP或域名的指定端口
    suspend fun scanSocket(
        target: String,
        port: Int,
        name: String,
        initId: String,
        onProgress: (String) -> Unit = {},
        onFinish: (String) -> Unit = {}
    ): List<Host> {
        return coroutineScope {
            val job = async(Dispatchers.IO) {
                try {
                    onProgress(target)
                    val host = Host(ip = target, port = port)
                    val client = WsClient(host, name, initId, useTls = true)
                    val ok = try { client.connect() } catch (_: Exception) { false }
                    if (ok) listOf(host) else emptyList()
                } finally {
                    onFinish(target)
                }
            }
            job.await()
        }
    }

    // 并发扫描指定CIDR范围，尝试 WebSocket 握手，返回有效主机
    suspend fun scanCidr(
        cidrInfo: CidrInfo,
        name: String,
        initId: String,
        port: Int = 56789,
        onProgress: (String) -> Unit = {},
        onFinish: (String) -> Unit = {}
    ): List<Host> {
        val dispatcher = Dispatchers.IO.limitedParallelism(32)
        
        // 生成要扫描的IP列表
        val startIpInt = ipToInt(cidrInfo.startIp)
        val endIpInt = ipToInt(cidrInfo.endIp)
        val ipsToScan = (startIpInt..endIpInt).map { intToIp(it) }
        
        return coroutineScope {
            val jobs = ipsToScan.map { ip ->
                async(dispatcher) {
                    try {
                        onProgress(ip)
                        val host = Host(ip = ip, port = port)
                        val client = WsClient(host, name, initId, useTls = true)
                        val ok = try { client.connect() } catch (_: Exception) { false }
                        if (ok) host else null
                    } finally {
                        onFinish(ip)
                    }
                }
            }
            jobs.awaitAll().filterNotNull()
        }
    }
    
    // 向后兼容的方法
    suspend fun scanPrefix(
        prefix: String,
        name: String,
        initId: String,
        port: Int = 56789,
        onProgress: (String) -> Unit = {},
        onFinish: (String) -> Unit = {}
    ): List<Host> {
        // 创建临时的CIDR信息（掩码24）
        val cidrInfo = CidrInfo(
            baseIp = "$prefix.0",
            mask = 24,
            startIp = "$prefix.1",
            endIp = "$prefix.254",
            totalIps = 254
        )
        return scanCidr(cidrInfo, name, initId, port, onProgress, onFinish)
    }

    // 保留原行为：按当前连接的 /24 子网扫描
    suspend fun scan(
        context: Context,
        name: String,
        initId: String,
        port: Int = 56789,
        onProgress: (String) -> Unit = {},
        onFinish: (String) -> Unit = {}
    ): List<Host> {
        val localIp = getLocalIp(context)
        val cidrInfo = parseCidr("$localIp/24") ?: return emptyList()
        return scanCidr(cidrInfo, name, initId, port, onProgress, onFinish)
    }
}