import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sqrt
import org.jtransforms.fft.DoubleFFT_1D


class AudioEngine (context: Context) {
    private val SAMPLE_RATE = 24000
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

    private lateinit var audioRecord: AudioRecord
    private lateinit var audioManager: AudioManager
    private lateinit var audioTrack: AudioTrack
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioSampleQueue: ConcurrentLinkedQueue<ByteArray> = ConcurrentLinkedQueue()
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val executorServiceMicrophone = Executors.newSingleThreadExecutor()
    private val executorServicePlayback = Executors.newSingleThreadExecutor()
    private var speakerDevice: AudioDeviceInfo? = null

    // Playback loop coordination and queue bounding
    private val isPlaybackWorkerRunning = AtomicBoolean(false)
    private val MAX_QUEUE_SIZE = 64

    // FFT state (reused across calls)
    private val FFT_SIZE = 1024
    private val fft = DoubleFFT_1D(FFT_SIZE.toLong())
    private val fftPacked = DoubleArray(FFT_SIZE)
    private val fftWindow = DoubleArray(FFT_SIZE) { i ->
        0.5 - 0.5 * cos(2.0 * PI * i / (FFT_SIZE - 1))
    }
    private val fftMagnitudes = DoubleArray(FFT_SIZE / 2)
    private val fftBytes = ByteArray(FFT_SIZE / 2)
    private val outputRing = ByteArray(FFT_SIZE * 2)
    private var outputRingWritePos = 0
    @Volatile private var outputRingFilled = false
    private val outputRingLock = Any()

    var isRecording = false
    private var isRecordingBeforePause = false
    var isPlaying = false

    // Callbacks
    var onMicDataCallback: ((ByteArray) -> Unit)? = null
    var onInputVolumeCallback: ((Float) -> Unit)? = null
    var onOutputVolumeCallback: ((Float) -> Unit)? = null
    var onAudioInterruptionCallback: ((String) -> Unit)? = null

    init {
        initializeAudio(context)
    }

    @SuppressLint("NewApi")
    private fun initializeAudio(context:Context) {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        requestAudioFocus()

        // Route audio to external device if connected, otherwise route to speaker
        updateAudioRouting()

        // Listen for changes in audio routing
        audioManager.registerAudioDeviceCallback(object:android.media.AudioDeviceCallback(){
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                Log.d("AudioEngine", "onAudioDevicesAdded")
                super.onAudioDevicesAdded(addedDevices)
                updateAudioRouting()
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                Log.d("AudioEngine", "onAudioDevicesRemoved")
                super.onAudioDevicesRemoved(removedDevices)
                updateAudioRouting()
            }
        }, null)

        val bufferSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AUDIO_FORMAT
        )

        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            audioManager.generateAudioSessionId()
        ).apply {
            play()
        }
    }

    private fun updateAudioRouting() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var isExternalDeviceConnected = false
        var selectedDevice: AudioDeviceInfo? = null

        for (device in devices) {
            if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                speakerDevice = device
            }
            if (device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                isExternalDeviceConnected = true
                selectedDevice = device
                break
            } else if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                selectedDevice = device
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Use the modern API for Android S and above
            try {
                selectedDevice?.let {
                    audioManager.setCommunicationDevice(it)
                }
            }catch (e:Exception){
                Log.e("AudioEngine", "Error setting communication device. Using speaker")
                speakerDevice?.let {
                    audioManager.setCommunicationDevice(it)
                }
            }

        } else {
            // Fall back to deprecated method for older Android versions
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = !isExternalDeviceConnected
        }
    }

    @SuppressLint("NewApi")
    private fun requestAudioFocus() {
        val focusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            Log.d("AudioEngine", "Audio focus lost")
                            onAudioInterruptionCallback?.let { it("blocked") }
                        }
                    }
                }
                .build()

        audioFocusRequest = focusRequest
        val result = audioManager.requestAudioFocus(focusRequest)

        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            throw RuntimeException("Audio focus request failed")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    private fun startRecording(){
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            throw RuntimeException("Audio Record can't initialize!")
        }

        if (AcousticEchoCanceler.isAvailable()){
            echoCanceler = AcousticEchoCanceler.create(audioRecord.audioSessionId)
            if (echoCanceler != null) {
                echoCanceler?.enabled = true
                Log.i("AudioEngine", "Echo Canceler enabled")
            }
        }

        if (NoiseSuppressor.isAvailable()){
            noiseSuppressor = NoiseSuppressor.create(audioRecord.audioSessionId)
            if (noiseSuppressor != null) {
                noiseSuppressor?.enabled = true
                Log.i("AudioEngine", "Noise Suppressor enabled")
            }
        }

        audioRecord.startRecording()
        isRecording = true
        startMicSampleTap()
    }

    private fun startMicSampleTap(){
        executorServiceMicrophone.execute {
            val buffer = ByteArray(1024)
            try {
                while (isRecording) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val data = buffer.copyOf(read)
                        val micVolume = calculateRMSLevel(data)
                        onInputVolumeCallback?.invoke(micVolume)
                        onMicDataCallback?.invoke(data)
                    }
                }
                Log.d("AudioEngine", "Mic sample tap stopped.")
            }catch (e: Exception){
                Log.e("AudioEngine", "Error reading mic sample data", e)
                isRecording = false
                tearDown()
                throw e
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            audioRecord.stop()
            audioRecord.release()
        }
        onInputVolumeCallback?.invoke(0.0F)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun toggleRecording(value: Boolean): Boolean {
        if (value == isRecording) return isRecording

        if (value) {
            startRecording()
        } else {
            stopRecording()
        }

        isRecording = value
        return isRecording
    }

    fun playPCMData(data: ByteArray) {
        // Bound the queue to keep latency under control
        while (audioSampleQueue.size >= MAX_QUEUE_SIZE) {
            audioSampleQueue.poll()
        }
        audioSampleQueue.add(data)
        if (isPlaybackWorkerRunning.compareAndSet(false, true)) {
            playAudioFromSampleQueue()
        }
    }

    fun getByteFrequencyData(): ByteArray? {
        if (!outputRingFilled) return fftBytes

        synchronized(outputRingLock) {
            var readPos = outputRingWritePos
            var si = 0
            while (si < FFT_SIZE) {
                val b0 = outputRing[readPos].toInt()
                val next = if (readPos + 1 < outputRing.size) readPos + 1 else 0
                val b1 = outputRing[next].toInt()
                val sample = (b0 or (b1 shl 8)).toShort().toInt()
                val x = sample / 32768.0
                fftPacked[si] = x * fftWindow[si]

                readPos += 2
                if (readPos >= outputRing.size) readPos = 0
                si++
            }

            fft.realForward(fftPacked)

            val n2 = FFT_SIZE / 2
            fftMagnitudes[0] = abs(fftPacked[0])
            fftMagnitudes[n2 - 1] = abs(fftPacked[1])
            var k = 1
            while (k < n2 - 1) {
                val re = fftPacked[2 * k]
                val im = fftPacked[2 * k + 1]
                fftMagnitudes[k] = sqrt(re * re + im * im)
                k++
            }

            var maxMag = 0.0
            for (i in 0 until n2) if (fftMagnitudes[i] > maxMag) maxMag = fftMagnitudes[i]
            val denom = if (maxMag > 0.0) maxMag else 1.0
            var i = 0
            while (i < n2) {
                val v = (fftMagnitudes[i] / denom) * 255.0
                fftBytes[i] = v.toInt().coerceIn(0, 255).toByte()
                i++
            }
        }
        return fftBytes
    }


    private fun playAudioFromSampleQueue() {
        executorServicePlayback.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            isPlaying = true
            try {
                while (true) {
                    val data = audioSampleQueue.poll() ?: break
                    playSample(data)
                    feedOutputRing(data)
                    val audioVolume = calculateRMSLevel(data)
                    onOutputVolumeCallback?.invoke(audioVolume)
                }
            } catch (e: Exception) {
                Log.e("AudioEngine", "Error playing audio", e)
                e.printStackTrace()
            } finally {
                isPlaying = false
                isPlaybackWorkerRunning.set(false)
                onOutputVolumeCallback?.invoke(0.0F)
            }
        }
    }

    private fun playSample(data: ByteArray) {
        audioTrack.write(data, 0, data.size)
    }

    private fun feedOutputRing(data: ByteArray) {
        synchronized(outputRingLock) {
            var i = 0
            while (i < data.size) {
                outputRing[outputRingWritePos] = data[i]
                val nextPos = outputRingWritePos + 1
                outputRing[if (nextPos < outputRing.size) nextPos else 0] = data[i + 1]

                outputRingWritePos += 2
                if (outputRingWritePos >= outputRing.size) {
                    outputRingWritePos = 0
                    outputRingFilled = true
                }
                i += 2
            }
        }
    }

    fun bypassVoiceProcessing(bypass: Boolean) {
        if (bypass) {
            echoCanceler?.enabled = false
            noiseSuppressor?.enabled = false
        } else {
            echoCanceler?.enabled = true
            noiseSuppressor?.enabled = true
        }
    }

    fun clearAudioQueue() {
        audioSampleQueue.clear()
        audioTrack.flush()
        onOutputVolumeCallback?.invoke(0.0f)
    }


    @RequiresApi(Build.VERSION_CODES.Q)
    fun pauseRecordingAndPlayer() {
        isRecordingBeforePause = isRecording
        isRecording = toggleRecording(false)
        audioTrack.pause()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun resumeRecordingAndPlayer() {
        requestAudioFocus()
        isRecording = toggleRecording(isRecordingBeforePause)
        audioTrack.play()
    }

    @SuppressLint("NewApi")
    fun tearDown() {
        stopRecording()
    audioTrack.stop()
    audioTrack.release()
        audioManager.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        audioFocusRequest?.let { request ->
            audioManager.abandonAudioFocusRequest(request)
        }
        executorServiceMicrophone.shutdownNow()
    executorServicePlayback.shutdownNow()
    }


    private fun calculateRMSLevel(buffer: ByteArray): Float {
        val epsilon = 1e-5f // To avoid log(0)
        val sampleCount = buffer.size / 2
        if (sampleCount == 0) return 0f
        var sumSquares = 0.0
        var i = 0
        while (i < buffer.size) {
            val sample = (buffer[i].toInt() or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
            val x = sample / 32768.0
            sumSquares += x * x
            i += 2
        }
        val rmsValue = kotlin.math.sqrt((sumSquares / sampleCount).toFloat())
        val dbValue = 20 * kotlin.math.log10(maxOf(rmsValue, epsilon))
        val minDb = -80.0f
        val normalizedValue = maxOf(0.0f, minOf(1.0f, (dbValue - minDb) / kotlin.math.abs(minDb)))
        val expFactor = 2.0f
        return normalizedValue.pow(expFactor)
    }

}
