package com.bestlink.screenmate.util

import android.content.Context
import android.media.MediaRecorder
import android.os.Environment
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceRecorderUtil(private val context: Context) {
    private val TAG = "VoiceRecorderUtil"
    
    // 录音状态
    sealed class RecordingState {
        object Idle : RecordingState()
        object Starting : RecordingState()
        object Recording : RecordingState()
        object Stopping : RecordingState()
        object Processing : RecordingState()
        data class Success(val audioData: String) : RecordingState()
        data class Error(val message: String) : RecordingState()
    }
    
    // 录音时长（秒）
    private var recordingDuration = 0L
    
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    
    // 状态流
    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState
    
    // 录音时长流
    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDurationFlow: StateFlow<Long> = _recordingDuration
    
    // 开始录音
    fun startRecording(): Boolean {
        return try {
            _recordingState.value = RecordingState.Starting
            
            // 创建录音文件
            audioFile = createAudioFile()
            if (audioFile == null) {
                _recordingState.value = RecordingState.Error("无法创建录音文件")
                return false
            }
            
            // 初始化MediaRecorder
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile(audioFile?.absolutePath)
                
                // 设置较低的采样率以减少数据量
                setAudioSamplingRate(8000)
                setAudioEncodingBitRate(12200)
                
                prepare()
            }
            
            // 开始录音
            mediaRecorder?.start()
            recordingDuration = 0L
            _recordingDuration.value = 0L
            _recordingState.value = RecordingState.Recording
            
            // 启动计时器
            startRecordingTimer()
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "开始录音失败: ${e.message}", e)
            _recordingState.value = RecordingState.Error("开始录音失败: ${e.message}")
            releaseMediaRecorder()
            false
        }
    }
    
    // 停止录音
    fun stopRecording(): Boolean {
        return try {
            _recordingState.value = RecordingState.Stopping
            stopRecordingTimer()
            
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
            
            // 处理录音数据
            processRecording()
            true
        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败: ${e.message}", e)
            _recordingState.value = RecordingState.Error("停止录音失败: ${e.message}")
            releaseMediaRecorder()
            false
        }
    }
    
    // 取消录音
    fun cancelRecording() {
        stopRecordingTimer()
        releaseMediaRecorder()
        deleteAudioFile()
        _recordingState.value = RecordingState.Idle
        _recordingDuration.value = 0L
    }
    
    // 处理录音数据
    private fun processRecording() {
        _recordingState.value = RecordingState.Processing
        
        try {
            val file = audioFile ?: throw IllegalStateException("录音文件不存在")
            
            if (!file.exists() || file.length() == 0L) {
                throw IllegalStateException("录音文件为空或不存在")
            }
            
            // 读取音频文件并转换为Base64
            val inputStream = FileInputStream(file)
            val audioBytes = inputStream.readBytes()
            inputStream.close()
            
            // 使用Base64.NO_WRAP避免换行符，防止JSON格式错误
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
            
            // 删除临时文件
            deleteAudioFile()
            
            _recordingState.value = RecordingState.Success(base64Audio)
            
        } catch (e: Exception) {
            Log.e(TAG, "处理录音数据失败: ${e.message}", e)
            _recordingState.value = RecordingState.Error("处理录音数据失败: ${e.message}")
            deleteAudioFile()
        }
    }
    
    // 创建录音文件
    private fun createAudioFile(): File? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "VOICE_${timeStamp}.3gp"
            
            // 使用应用缓存目录
            val storageDir = context.cacheDir
            File.createTempFile("VOICE_", ".3gp", storageDir)
        } catch (e: Exception) {
            Log.e(TAG, "创建录音文件失败: ${e.message}", e)
            null
        }
    }
    
    // 删除录音文件
    private fun deleteAudioFile() {
        try {
            audioFile?.takeIf { it.exists() }?.delete()
            audioFile = null
        } catch (e: Exception) {
            Log.e(TAG, "删除录音文件失败: ${e.message}", e)
        }
    }
    
    // 释放MediaRecorder资源
    private fun releaseMediaRecorder() {
        try {
            mediaRecorder?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                    Log.w(TAG, "停止MediaRecorder失败: ${e.message}")
                }
                release()
            }
            mediaRecorder = null
        } catch (e: Exception) {
            Log.e(TAG, "释放MediaRecorder资源失败: ${e.message}", e)
        }
    }
    
    // 录音计时器
    private var recordingTimer: Thread? = null
    
    private fun startRecordingTimer() {
        recordingTimer = Thread {
            try {
                while (_recordingState.value == RecordingState.Recording) {
                    Thread.sleep(1000)
                    recordingDuration++
                    _recordingDuration.value = recordingDuration
                    
                    // 限制最大录音时长（30秒）
                    if (recordingDuration >= 30) {
                        stopRecording()
                        break
                    }
                }
            } catch (e: InterruptedException) {
                // 计时器被中断，正常退出
            } catch (e: Exception) {
                Log.e(TAG, "录音计时器错误: ${e.message}", e)
            }
        }.apply {
            start()
        }
    }
    
    private fun stopRecordingTimer() {
        recordingTimer?.interrupt()
        recordingTimer = null
    }
    
    // 清理资源
    fun destroy() {
        stopRecordingTimer()
        releaseMediaRecorder()
        deleteAudioFile()
        _recordingState.value = RecordingState.Idle
        _recordingDuration.value = 0L
    }
    
    // 权限检查
    fun hasRecordAudioPermission(): Boolean {
        return android.os.Process.myUid() == 1000 || // 系统进程
                android.Manifest.permission.RECORD_AUDIO.let { permission ->
                    context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                }
    }
    
    // 更新录音状态（供外部调用）
    fun updateRecordingState(state: RecordingState) {
        _recordingState.value = state
    }
}