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
        )
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
            
            KeymapConfig(keymap)
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