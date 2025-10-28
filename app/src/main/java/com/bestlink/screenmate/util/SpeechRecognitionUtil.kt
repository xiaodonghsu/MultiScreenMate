package com.bestlink.screenmate.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class SpeechRecognitionUtil(private val context: Context) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var recognitionIntent: Intent? = null
    
    // 语音识别状态
    val recognitionState = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    val recognizedText = MutableStateFlow("")
    
    // 权限检查
    fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    fun hasAllRequiredPermissions(): Boolean {
        return hasRecordAudioPermission() && hasLocationPermission()
    }
    
    // 初始化语音识别器
    fun initializeSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            recognitionIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN") // 中文识别
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    recognitionState.value = RecognitionState.Ready
                }
                
                override fun onBeginningOfSpeech() {
                    recognitionState.value = RecognitionState.Listening
                }
                
                override fun onRmsChanged(rmsdB: Float) {
                    // 可以用于显示音量波动
                }
                
                override fun onBufferReceived(buffer: ByteArray?) {
                    // 不需要处理
                }
                
                override fun onEndOfSpeech() {
                    recognitionState.value = RecognitionState.Processing
                }
                
                override fun onError(error: Int) {
                    val errorMessage = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                        SpeechRecognizer.ERROR_NO_MATCH -> "无匹配结果"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器繁忙"
                        SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                        else -> "未知错误"
                    }
                    recognitionState.value = RecognitionState.Error(errorMessage)
                }
                
                override fun onResults(results: android.os.Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        recognizedText.value = text
                        recognitionState.value = RecognitionState.Success(text)
                    } else {
                        recognitionState.value = RecognitionState.Error("无识别结果")
                    }
                }
                
                override fun onPartialResults(partialResults: android.os.Bundle?) {
                    val partialMatches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!partialMatches.isNullOrEmpty()) {
                        recognizedText.value = partialMatches[0]
                    }
                }
                
                override fun onEvent(eventType: Int, params: android.os.Bundle?) {
                    // 不需要处理
                }
            })
        } else {
            recognitionState.value = RecognitionState.Error("设备不支持语音识别")
        }
    }
    
    // 开始语音识别
    fun startListening() {
        if (!hasAllRequiredPermissions()) {
            recognitionState.value = RecognitionState.Error("需要录音和位置权限")
            return
        }
        
        speechRecognizer?.let { recognizer ->
            recognitionIntent?.let { intent ->
                try {
                    recognizer.startListening(intent)
                    recognitionState.value = RecognitionState.Starting
                } catch (e: Exception) {
                    recognitionState.value = RecognitionState.Error("启动语音识别失败: ${e.message}")
                }
            }
        }
    }
    
    // 停止语音识别
    fun stopListening() {
        speechRecognizer?.stopListening()
    }
    
    // 取消语音识别
    fun cancelListening() {
        speechRecognizer?.cancel()
        recognitionState.value = RecognitionState.Idle
        recognizedText.value = ""
    }
    
    // 释放资源
    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
    
    sealed class RecognitionState {
        object Idle : RecognitionState()
        object Starting : RecognitionState()
        object Ready : RecognitionState()
        object Listening : RecognitionState()
        object Processing : RecognitionState()
        data class Success(val text: String) : RecognitionState()
        data class Error(val message: String) : RecognitionState()
    }
}