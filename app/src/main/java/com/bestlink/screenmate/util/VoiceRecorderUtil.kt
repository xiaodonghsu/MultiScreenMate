package com.bestlink.screenmate.util

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VoiceRecorderUtil(private val context: Context) {
    private val TAG = "VoiceRecorderUtil"
    
    // PCM录音参数
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    
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
    
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    
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
            
            // 初始化AudioRecord
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                _recordingState.value = RecordingState.Error("AudioRecord初始化失败")
                return false
            }
            
            // 开始录音
            audioRecord?.startRecording()
            isRecording = true
            recordingDuration = 0L
            _recordingDuration.value = 0L
            _recordingState.value = RecordingState.Recording
            
            // 启动录音线程
            startRecordingThread()
            
            // 启动计时器
            startRecordingTimer()
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "开始录音失败: ${e.message}", e)
            _recordingState.value = RecordingState.Error("开始录音失败: ${e.message}")
            releaseAudioRecord()
            false
        }
    }
    
    // 停止录音
    fun stopRecording(): Boolean {
        return try {
            _recordingState.value = RecordingState.Stopping
            stopRecordingTimer()
            
            isRecording = false
            
            // 停止录音线程
            recordingThread?.join(1000)
            recordingThread = null
            
            // 停止AudioRecord
            audioRecord?.stop()
            releaseAudioRecord()
            
            // 处理录音数据
            processRecording()
            true
        } catch (e: Exception) {
            Log.e(TAG, "停止录音失败: ${e.message}", e)
            _recordingState.value = RecordingState.Error("停止录音失败: ${e.message}")
            releaseAudioRecord()
            false
        }
    }
    
    // 取消录音
    fun cancelRecording() {
        stopRecordingTimer()
        
        isRecording = false
        recordingThread?.join(1000)
        recordingThread = null
        
        releaseAudioRecord()
        deleteAudioFile()
        _recordingState.value = RecordingState.Idle
        _recordingDuration.value = 0L
    }
    
    // 录音线程
    private fun startRecordingThread() {
        recordingThread = Thread {
            val buffer = ByteArray(BUFFER_SIZE)
            var outputStream: FileOutputStream? = null
            
            try {
                outputStream = FileOutputStream(audioFile)
                
                while (isRecording) {
                    val bytesRead = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: 0
                    if (bytesRead > 0) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
                
                outputStream.flush()
            } catch (e: Exception) {
                Log.e(TAG, "录音线程错误: ${e.message}", e)
            } finally {
                try {
                    outputStream?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "关闭文件流失败: ${e.message}", e)
                }
            }
        }.apply {
            start()
        }
    }
    
    // 处理录音数据
    private fun processRecording() {
        _recordingState.value = RecordingState.Processing
        
        try {
            val file = audioFile ?: throw IllegalStateException("录音文件不存在")
            
            if (!file.exists() || file.length() == 0L) {
                throw IllegalStateException("录音文件为空或不存在")
            }
            
            // 读取PCM音频数据
            val pcmData = file.readBytes()
            
            // 将PCM数据转换为WAV格式（添加WAV文件头）
            val wavData = convertPcmToWav(pcmData, SAMPLE_RATE, 1, 16)
            
            // 使用Base64.NO_WRAP避免换行符，防止JSON格式错误
            val base64Audio = Base64.encodeToString(wavData, Base64.NO_WRAP)
            
            // 删除临时文件
            deleteAudioFile()
            
            _recordingState.value = RecordingState.Success(base64Audio)
            
        } catch (e: Exception) {
            Log.e(TAG, "处理录音数据失败: ${e.message}", e)
            _recordingState.value = RecordingState.Error("处理录音数据失败: ${e.message}")
            deleteAudioFile()
        }
    }
    
    // 将PCM数据转换为WAV格式
    private fun convertPcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        
        val outputStream = ByteArrayOutputStream()
        
        try {
            // WAV文件头
            // RIFF头
            outputStream.write("RIFF".toByteArray())                    // ChunkID
            outputStream.write(intToByteArray(36 + dataSize))           // ChunkSize
            outputStream.write("WAVE".toByteArray())                    // Format
            
            // fmt子块
            outputStream.write("fmt ".toByteArray())                    // Subchunk1ID
            outputStream.write(intToByteArray(16))                      // Subchunk1Size
            outputStream.write(shortToByteArray(1))                    // AudioFormat (PCM = 1)
            outputStream.write(shortToByteArray(channels.toShort()))    // NumChannels
            outputStream.write(intToByteArray(sampleRate))              // SampleRate
            outputStream.write(intToByteArray(byteRate))                // ByteRate
            outputStream.write(shortToByteArray(blockAlign.toShort()))  // BlockAlign
            outputStream.write(shortToByteArray(bitsPerSample.toShort())) // BitsPerSample
            
            // data子块
            outputStream.write("data".toByteArray())                    // Subchunk2ID
            outputStream.write(intToByteArray(dataSize))                // Subchunk2Size
            outputStream.write(pcmData)                                 // PCM数据
            
            return outputStream.toByteArray()
        } finally {
            outputStream.close()
        }
    }
    
    // 辅助函数：int转byte数组（小端序）
    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
    
    // 辅助函数：short转byte数组（小端序）
    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
    }
    
    // 创建录音文件
    private fun createAudioFile(): File? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "VOICE_${timeStamp}.pcm"
            
            // 使用应用缓存目录
            val storageDir = context.cacheDir
            File.createTempFile("VOICE_", ".pcm", storageDir)
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
    
    // 释放AudioRecord资源
    private fun releaseAudioRecord() {
        try {
            audioRecord?.apply {
                try {
                    stop()
                } catch (e: Exception) {
                    Log.w(TAG, "停止AudioRecord失败: ${e.message}")
                }
                release()
            }
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "释放AudioRecord资源失败: ${e.message}", e)
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
        
        isRecording = false
        recordingThread?.join(1000)
        recordingThread = null
        
        releaseAudioRecord()
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