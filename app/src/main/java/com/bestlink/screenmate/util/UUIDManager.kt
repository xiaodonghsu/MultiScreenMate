package com.bestlink.screenmate.util

import android.content.Context
import java.io.File
import java.util.UUID

object UUIDManager {
    private const val UUID_FILENAME = "uuid"
    
    fun getOrCreateUUID(context: Context): String {
        val uuidFile = File(context.filesDir, UUID_FILENAME)
        
        return if (uuidFile.exists()) {
            // 读取现有的UUID
            try {
                uuidFile.readText().trim()
            } catch (e: Exception) {
                // 如果读取失败，生成新的UUID
                generateAndSaveUUID(uuidFile)
            }
        } else {
            // 生成新的UUID并保存
            generateAndSaveUUID(uuidFile)
        }
    }
    
    private fun generateAndSaveUUID(uuidFile: File): String {
        val newUUID = UUID.randomUUID().toString()
        try {
            uuidFile.writeText(newUUID)
        } catch (e: Exception) {
            // 如果保存失败，返回UUID但不保存（下次启动会重新生成）
        }
        return newUUID
    }
}