package com.catguard.camera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.catguard.databinding.ActivityCameraBinding
import com.catguard.ml.CatDetector
import com.catguard.network.StreamServer
import kotlinx.coroutines.*
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {

    private lateinit var b: ActivityCameraBinding
    private lateinit var detector: CatDetector
    private lateinit var server: StreamServer
    private val executor = Executors.newSingleThreadExecutor()

    @Volatile private var lastBitmap: Bitmap? = null
    private var lastStatus = ""

    // Мікрофон
    private var audioRecord: AudioRecord? = null
    private var micEnabled = false
    private var micJob: Job? = null

    // Фонарик
    private var torchOn = false
    private var camera: Camera? = null

    // Роздільна здатність (можна перемикати)
    private var useHighRes = false  // false = 1280x720, true = 3840x2160 якщо підтримується

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val ip = getLocalIp()
        updateIpDisplay(ip)

        // Копіювати IP при натисканні
        b.tvCode.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("IP", "$ip:8765"))
            Toast.makeText(this, "IP скопійовано!", Toast.LENGTH_SHORT).show()
        }

        // QR-код для камери
        b.btnShowQr.setOnClickListener { showQrCode(ip) }

        // Фонарик
        b.btnTorch.setOnClickListener { toggleTorch() }

        // Мікрофон
        b.btnMic.setOnClickListener { toggleMic() }

        // Перемикання роздільної здатності
        b.btnResolution.setOnClickListener { toggleResolution() }

        // WebSocket сервер
        server = StreamServer(8765)
        server.onClientCount = { count ->
            runOnUiThread {
                b.tvViewers.text = "👁 Глядачів: $count"
            }
        }
        server.start()

        // TFLite детектор
        detector = CatDetector(this)
        lifecycleScope.launch(Dispatchers.IO) {
            detector.init()
            withContext(Dispatchers.Main) { b.tvStatus.text = "Готово. Сканую…" }
        }

        startCamera()
        startDetectionLoop()
    }

    private fun updateIpDisplay(ip: String) {
        b.tvCode.text = "📡 $ip:8765\n(натисни щоб копіювати)"
    }

    private fun showQrCode(ip: String) {
        // Показати QR з catguard://IP:8765
        val content = "catguard://$ip:8765"
        val intent = android.content.Intent(this, com.catguard.qr.QrDisplayActivity::class.java)
        intent.putExtra("qr_content", content)
        startActivity(intent)
    }

    private fun toggleTorch() {
        torchOn = !torchOn
        camera?.cameraControl?.enableTorch(torchOn)
        b.btnTorch.text = if (torchOn) "🔦 Вимк" else "🔦 Ввімк"
    }

    private fun toggleMic() {
        micEnabled = !micEnabled
        if (micEnabled) {
            startMic()
            b.btnMic.text = "🎙 Вимк мік"
        } else {
            stopMic()
            b.btnMic.text = "🎙 Мікрофон"
        }
    }

    private fun startMic() {
        val sampleRate = 44100
        val bufSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
            audioRecord?.startRecording()
            micJob = lifecycleScope.launch(Dispatchers.IO) {
                val buf = ShortArray(bufSize)
                while (isActive && micEnabled) {
                    val read = audioRecord?.read(buf, 0, buf.size) ?: break
                    if (read > 0) {
                        // Передаємо аудіо через WebSocket як бінарні дані
                        val bytes = ByteArray(read * 2)
                        for (i in 0 until read) {
                            bytes[i * 2]     = (buf[i].toInt() and 0xFF).toByte()
                            bytes[i * 2 + 1] = (buf[i].toInt() shr 8 and 0xFF).toByte()
                        }
                        server.broadcastAudio(bytes)
                    }
                }
            }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, "Помилка мікрофону: ${e.message}", Toast.LENGTH_SHORT).show() }
            micEnabled = false
        }
    }

    private fun stopMic() {
        micJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun toggleResolution() {
        useHighRes = !useHighRes
        val label = if (useHighRes) "4K" else "HD"
        b.btnResolution.text = "📹 $label"
        Toast.makeText(this, "Перезапуск камери у $label…", Toast.LENGTH_SHORT).show()
        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(b.previewView.surfaceProvider) }

            // Вибір роздільної здатності
            val targetSize = if (useHighRes)
                android.util.Size(3840, 2160)
            else
                android.util.Size(1280, 720)

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(targetSize)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(executor) { proxy ->
                lastBitmap = proxy.toBitmap()
                proxy.close()
            }

            try {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, analysis
                )
                camera = cam
                // Відновити стан фонарика
                cam.cameraControl.enableTorch(torchOn)
            } catch (e: Exception) {
                b.tvStatus.text = "Помилка камери: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startDetectionLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(800)
                val bmp = lastBitmap ?: continue
                val catFound = detector.hasCat(bmp)
                val status   = if (catFound) "OBJECT_DETECTED" else "OBJECT_LOST"

                if (status != lastStatus) {
                    lastStatus = status
                    server.broadcast(status)
                }

                withContext(Dispatchers.Main) {
                    if (catFound) {
                        b.tvStatus.text = "🐱 КІТ ЗНАЙДЕНИЙ"
                        b.tvStatus.setBackgroundColor(0xFF1B5E20.toInt())
                    } else {
                        b.tvStatus.text = "👁 Сканування… кота немає"
                        b.tvStatus.setBackgroundColor(0xFF1A1A2E.toInt())
                    }
                }
            }
        }
    }

    private fun getLocalIp(): String {
        return try {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo.ipAddress
            "%d.%d.%d.%d".format(
                ip and 0xff, ip shr 8 and 0xff,
                ip shr 16 and 0xff, ip shr 24 and 0xff
            )
        } catch (_: Exception) { "???" }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMic()
        executor.shutdown()
        detector.close()
        try { server.stop(500) } catch (_: Exception) {}
    }
}
