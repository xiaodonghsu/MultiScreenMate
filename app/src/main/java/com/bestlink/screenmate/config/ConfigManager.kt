package com.bestlink.screenmate.config

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.*

class ConfigManager(private val context: Context) {
    private val TAG = "ConfigManager"
    private val configFile = File(context.filesDir, "client.json")
    
    data class KeymapConfig(
        val keymap: Map<String, String> = mapOf(
            "L" to "Left",
            "R" to "Right", 
            "U" to "Up",
            "D" to "Down",
            "C" to "Space"
        ),
        val volumeKeymap: Map<String, String> = mapOf(
            "VOLUME_UP" to "Left",
            "VOLUME_DOWN" to "Right"
        ),
        val enableVibration: Boolean = true,
        val scanPort: Int = 56789
    )
    
    fun loadConfig(): KeymapConfig {
        return try {
            if (!configFile.exists()) {
                // 如果配置文件不存在，从assets复制默认配置
                copyDefaultConfig()
            }
            
            val jsonString = configFile.readText()
            val json = JSONObject(jsonString)
            val keymapJson = json.getJSONObject("keymap")
            
            val keymap = mutableMapOf<String, String>()
            val keys = keymapJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                keymap[key] = keymapJson.getString(key)
            }
            
            // 读取音量键映射配置
            val volumeKeymapJson = json.optJSONObject("volumeKeymap")
            val volumeKeymap = mutableMapOf<String, String>()
            if (volumeKeymapJson != null) {
                val volumeKeys = volumeKeymapJson.keys()
                while (volumeKeys.hasNext()) {
                    val key = volumeKeys.next()
                    volumeKeymap[key] = volumeKeymapJson.getString(key)
                }
            } else {
                // 使用默认音量键映射
                volumeKeymap["VOLUME_UP"] = "Left"
                volumeKeymap["VOLUME_DOWN"] = "Right"
            }
            
            // 读取震动配置，默认为true
            val enableVibration = json.optBoolean("enableVibration", true)
            
            // 读取端口配置，默认为56789
            val scanPort = json.optInt("scanPort", 56789)
            
            KeymapConfig(keymap, volumeKeymap, enableVibration, scanPort)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load config, using default", e)
            KeymapConfig()
        }
    }
    
    fun saveConfig(config: KeymapConfig): Boolean {
        return try {
            val json = JSONObject()
            val keymapJson = JSONObject()
            config.keymap.forEach { (key, value) ->
                keymapJson.put(key, value)
            }
            json.put("keymap", keymapJson)
            
            val volumeKeymapJson = JSONObject()
            config.volumeKeymap.forEach { (key, value) ->
                volumeKeymapJson.put(key, value)
            }
            json.put("volumeKeymap", volumeKeymapJson)
            
            json.put("enableVibration", config.enableVibration)
            json.put("scanPort", config.scanPort)
            
            configFile.writeText(json.toString())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save config", e)
            false
        }
    }
    
    private fun copyDefaultConfig() {
        try {
            val inputStream = context.assets.open("client.json")
            val outputStream = FileOutputStream(configFile)
            inputStream.copyTo(outputStream)
            inputStream.close()
            outputStream.close()
            Log.d(TAG, "Default config copied from assets")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy default config from assets", e)
        }
    }
}