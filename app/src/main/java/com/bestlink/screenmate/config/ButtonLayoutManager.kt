package com.bestlink.screenmate.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ButtonConfig(
    val key: String, // U, D, L, R, C
    val x: Float,   // 相对位置 (0-1)
    val y: Float,    // 相对位置 (0-1)
    val width: Float, // 相对大小 (0-1)
    val height: Float // 相对大小 (0-1)
)

data class ButtonLayout(
    val buttons: List<ButtonConfig>
)

class ButtonLayoutManager(private val context: Context) {
    private val layoutFile = File(context.filesDir, "button_layout.json")
    
    fun loadLayout(): ButtonLayout {
        return try {
            if (layoutFile.exists()) {
                val json = layoutFile.readText()
                parseLayoutFromJson(json)
            } else {
                // 默认布局：九宫格中间位置
                createDefaultLayout()
            }
        } catch (e: Exception) {
            createDefaultLayout()
        }
    }
    
    fun saveLayout(layout: ButtonLayout): Boolean {
        return try {
            val json = convertLayoutToJson(layout)
            layoutFile.writeText(json)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    private fun convertLayoutToJson(layout: ButtonLayout): String {
        val jsonArray = JSONArray()
        layout.buttons.forEach { button ->
            val jsonObject = JSONObject().apply {
                put("key", button.key)
                put("x", button.x)
                put("y", button.y)
                put("width", button.width)
                put("height", button.height)
            }
            jsonArray.put(jsonObject)
        }
        return JSONObject().put("buttons", jsonArray).toString()
    }
    
    private fun parseLayoutFromJson(json: String): ButtonLayout {
        val jsonObject = JSONObject(json)
        val buttonsArray = jsonObject.getJSONArray("buttons")
        val buttons = mutableListOf<ButtonConfig>()
        
        for (i in 0 until buttonsArray.length()) {
            val buttonObj = buttonsArray.getJSONObject(i)
            buttons.add(ButtonConfig(
                key = buttonObj.getString("key"),
                x = buttonObj.getDouble("x").toFloat(),
                y = buttonObj.getDouble("y").toFloat(),
                width = buttonObj.getDouble("width").toFloat(),
                height = buttonObj.getDouble("height").toFloat()
            ))
        }
        
        return ButtonLayout(buttons)
    }
    
    private fun createDefaultLayout(): ButtonLayout {
        return ButtonLayout(
            buttons = listOf(
                ButtonConfig("U", 0.3f, 0.1f, 0.2f, 0.2f), // 上
                ButtonConfig("D", 0.5f, 0.4f, 0.2f, 0.2f), // 下
                ButtonConfig("L", 0.1f, 0.1f, 0.2f, 0.2f), // 左
                ButtonConfig("R", 0.1f, 0.5f, 0.4f, 0.4f), // 右
                ButtonConfig("C", 0.5f, 0.1f, 0.2f, 0.2f)  // 中
            )
        )
    }
}