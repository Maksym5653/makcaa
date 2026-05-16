package com.catguard.viewer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.catguard.databinding.ActivityViewerBinding
import com.catguard.network.Esp32
import com.catguard.network.StreamClient
import com.catguard.pairing.PairingManager
import kotlinx.coroutines.*
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QR_IP = "qr_ip"
    }

    private var _b: ActivityViewerBinding? = null
    private val b get() = _b!!

    private val statuses = ConcurrentHashMap<String, String>()
    private val clients  = ConcurrentHashMap<String, StreamClient>()

    private var alarmOn  = false
    private var alarmJob: Job? = null

    private var audioTrack: AudioTrack? = null
    private var audioEnabled = false

    private var esp32Connected = false

    // ─── Lifecycle ────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        _b = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupButtons()
        initAudio()
        checkEsp32()
        updateUI()

        // Перевіряємо чи введено pairing code — якщо ні, пропонуємо одразу
        if (PairingManager.getCode(this) == null) {
            showPairingDialog(firstTime = true)
        } else {
            updatePairingBadge()
        }

        // Авто-підключення якщо прийшли з QR
        intent.getStringExtra(EXTRA_QR_IP)?.let { ip -> connectCamera(ip) }
    }

    // ─── Кнопки ──────────────────────────────────────────────────────────────
    private fun setupButtons() {
        b.btnAdd.setOnClickListener          { showAddDialog() }
        b.btnAddQr.setOnClickListener        { startQrScan() }
        b.btnDisconnect.setOnClickListener   { disconnectAll() }
        b.btnAudio.setOnClickListener        { toggleAudio() }
        b.btnEsp32.setOnClickListener        { showEsp32Info() }
        b.btnTest.setOnClickListener         { testAlarm() }
        b.btnPairing.setOnClickListener      { showPairingDialog(firstTime = false) }
        b.btnTorchRemote.setOnClickListener  { sendToAllCameras("TORCH_TOGGLE") }
        b.btnMicRemote.setOnClickListener    { sendToAllCameras("MIC_TOGGLE") }
    }

    // ─── Pairing code ─────────────────────────────────────────────────────────
    private fun showPairingDialog(firstTime: Boolean) {
        val current = PairingManager.getCode(this)
        val input = EditText(this).apply {
            hint = "4-значний код з екрану ESP32"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
            setText(current ?: "")
            setPadding(60, 30, 60, 30)
        }
        val title = if (firstTime) "Введіть код з ESP32" else "Змінити код ESP32"
        val msg   = if (firstTime)
            "На екрані вашого ESP32 відображається 4-значний код.\nВведіть його нижче. Він збережеться автоматично."
        else
            "Поточний код: ${current ?: "не введено"}\nВведіть новий код з дисплея ESP32."

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setView(input)
            .setCancelable(!firstTime)
            .setPositiveButton("Зберегти") { _, _ ->
                val code = input.text.toString().trim()
                if (PairingManager.isValid(code)) {
                    PairingManager.saveCode(this, code)
                    updatePairingBadge()
                    Toast.makeText(this, "Код $code збережено ✓", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Код має бути 4 цифри (наприклад: 5739)", Toast.LENGTH_LONG).show()
                    if (firstTime) showPairingDialog(true) // показати знову
                }
            }
            .setNegativeButton(if (firstTime) "Пізніше" else "Скасувати", null)
            .apply { if (!firstTime) setNeutralButton("Очистити") { _, _ ->
                PairingManager.clearCode(this@ViewerActivity)
                updatePairingBadge()
                Toast.makeText(this@ViewerActivity, "Код видалено", Toast.LENGTH_SHORT).show()
            }}
            .show()
    }

    private fun updatePairingBadge() {
        val code = PairingManager.getCode(this)
        b.btnPairing.text = if (code != null) "🔑 $code" else "🔑 —"
        b.btnPairing.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (code != null) 0xFF4A148C.toInt() else 0xFF880000.toInt()
        )
    }

    // ─── ESP32 ────────────────────────────────────────────────────────────────
    private fun checkEsp32() {
        lifecycleScope.launch {
            esp32Connected = Esp32.ping()
            safeUi { updateEsp32Badge() }
        }
    }

    private fun updateEsp32Badge() {
        b.tvEsp32Status.text = if (esp32Connected) "ESP32 ✅" else "ESP32 ❌"
        b.tvEsp32Status.setBackgroundColor(
            if (esp32Connected) 0xFF1B5E20.toInt() else 0xFF880000.toInt()
        )
    }

    private fun showEsp32Info() {
        val code = PairingManager.getCode(this)
        AlertDialog.Builder(this)
            .setTitle("ESP32 / Хаб CatGuard")
            .setMessage(
                "Адреса хаба: catguard.local\n" +
                "Endpoint:    /cat?code=XXXX\n\n" +
                "Pairing code: ${code ?: "❌ не введено"}\n\n" +
                "Як отримати код:\n" +
                "1. Увімкніть ESP32 — він генерує новий 4-значний код\n" +
                "2. Код показується на TFT-дисплеї ESP32\n" +
                "3. Введіть його кнопкою 🔑\n\n" +
                "Статус з'єднання: ${if (esp32Connected) "✅ catguard.local доступний" else "❌ не доступний (чи в одній мережі?)"}"
            )
            .setPositiveButton("Перевірити знову") { _, _ -> checkEsp32() }
            .setNeutralButton("Ввести код") { _, _ -> showPairingDialog(false) }
            .setNegativeButton("OK", null)
            .show()
    }

    /**
     * Відправляє сигнал тривоги на ESP32 з pairing code.
     * Відповідь:
     *   200 OK          → ESP32 прийняв, вмикає тривогу
     *   401 Unauthorized → код невірний
     *   помилка мережі  → ESP32 недоступний
     */
    private fun sendCatAlert() {
        val code = PairingManager.getCode(this)
        if (!PairingManager.isValid(code)) {
            safeUi {
                Toast.makeText(this, "⚠️ Введіть pairing code (кнопка 🔑)", Toast.LENGTH_LONG).show()
                showPairingDialog(false)
            }
            return
        }
        lifecycleScope.launch {
            when (Esp32.sendCatAlert(code!!)) {
                is Esp32.SendResult.Ok           -> { /* успішно — ESP32 вмикає тривогу */ }
                is Esp32.SendResult.Unauthorized -> safeUi {
                    Toast.makeText(this@ViewerActivity,
                        "❌ ESP32: код $code невірний. Введіть правильний код.", Toast.LENGTH_LONG).show()
                    showPairingDialog(false)
                }
                is Esp32.SendResult.Unreachable  -> safeUi {
                    Toast.makeText(this@ViewerActivity,
                        "⚠️ catguard.local недоступний. Чи в одній WiFi-мережі?", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun testAlarm() {
        val code = PairingManager.getCode(this)
        if (!PairingManager.isValid(code)) {
            showPairingDialog(false)
            return
        }
        lifecycleScope.launch {
            val result = Esp32.sendCatAlert(code!!)
            safeUi {
                when (result) {
                    is Esp32.SendResult.Ok -> {
                        b.tvAlarm.text = "🔴 ТЕСТ: сигнал відправлено (код $code)"
                        b.tvAlarm.setBackgroundColor(0xFFFF1744.toInt())
                    }
                    is Esp32.SendResult.Unauthorized ->
                        Toast.makeText(this@ViewerActivity, "❌ Невірний код ($code)", Toast.LENGTH_LONG).show()
                    is Esp32.SendResult.Unreachable ->
                        Toast.makeText(this@ViewerActivity, "⚠️ ESP32 недоступний", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ─── Камери ──────────────────────────────────────────────────────────────
    private fun startQrScan() {
        startActivity(android.content.Intent(this, com.catguard.qr.QrScanActivity::class.java))
    }

    private fun showAddDialog() {
        if (clients.size >= 4) {
            Toast.makeText(this, "Максимум 4 камери", Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(this).apply {
            hint = "IP камери (напр.: 192.168.1.55)"
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(60, 30, 60, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("Підключити камеру")
            .setMessage("Введіть IP телефону-камери або використайте «📷 QR».")
            .setView(input)
            .setPositiveButton("Підключити") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) connectCamera(ip)
                else Toast.makeText(this, "Введіть IP", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Скасувати", null)
            .show()
    }

    private fun connectCamera(ip: String) {
        if (clients.containsKey(ip)) {
            Toast.makeText(this, "Вже підключено до $ip", Toast.LENGTH_SHORT).show()
            return
        }
        if (clients.size >= 4) {
            Toast.makeText(this, "Максимум 4 камери", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = try { URI("ws://$ip:8765") } catch (e: Exception) {
            Toast.makeText(this, "Невірна адреса: $ip", Toast.LENGTH_SHORT).show()
            return
        }
        val client = StreamClient(
            serverUri    = uri,
            cameraId     = ip,
            onStatus     = ::onCameraStatus,
            onAudio      = { pcm -> if (audioEnabled) audioTrack?.write(pcm, 0, pcm.size) },
            onConnect    = { camId ->
                statuses[camId] = "OBJECT_DETECTED"
                safeUi { updateUI() }
            },
            onDisconnect = { camId ->
                clients.remove(camId)
                statuses.remove(camId)
                safeUi {
                    updateUI()
                    Toast.makeText(this, "Камера $camId відключилась", Toast.LENGTH_SHORT).show()
                }
            }
        )
        clients[ip] = client
        try {
            client.connect()
            Toast.makeText(this, "Підключаюсь до $ip…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            clients.remove(ip)
            Toast.makeText(this, "Помилка: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Логіка виявлення ─────────────────────────────────────────────────────
    private fun onCameraStatus(cameraId: String, status: String) {
        statuses[cameraId] = status
        val anyFound = statuses.values.any  { it == "OBJECT_DETECTED" }
        val allLost  = statuses.isNotEmpty() && statuses.values.all { it == "OBJECT_LOST" }

        when {
            anyFound -> {
                // Кіт знайдений — скасовуємо тривогу
                alarmJob?.cancel()
                if (alarmOn) {
                    alarmOn = false
                    // Надсилаємо reset на ESP32 (можна додати /reset endpoint)
                }
            }
            allLost -> {
                // Кіт зник — чекаємо 2 сек і відправляємо сигнал
                alarmJob?.cancel()
                alarmJob = lifecycleScope.launch {
                    delay(2000)
                    if (!isActive) return@launch
                    if (statuses.values.all { it == "OBJECT_LOST" } && !alarmOn) {
                        alarmOn = true
                        sendCatAlert()   // ← тут відправляється з кодом
                    }
                }
            }
        }
        safeUi { updateUI() }
    }

    // ─── UI ──────────────────────────────────────────────────────────────────
    private fun updateUI() {
        val entries = clients.entries.toList()
        val count   = entries.size

        // Відображення панелей у сітці залежно від кількості камер
        // 1 камера  → займає весь екран (span 2 cols)
        // 2 камери  → ліво / право
        // 3 камери  → top-left / top-right / bottom-left (bottom-right пустий)
        // 4 камери  → 2×2

        val views = listOf(
            Triple(b.videoView1, b.tvCamera1Label, b.tvCamera1Status),
            Triple(b.videoView2, b.tvCamera2Label, b.tvCamera2Status),
            Triple(b.videoView3, b.tvCamera3Label, b.tvCamera3Status),
            Triple(b.videoView4, b.tvCamera4Label, b.tvCamera4Status)
        )

        if (count == 0) {
            views.forEach { (v, l, s) -> v.visibility = View.GONE; l.visibility = View.GONE; s.visibility = View.GONE }
            b.tvEmpty.visibility = View.VISIBLE
        } else {
            b.tvEmpty.visibility = View.GONE
            views.forEachIndexed { i, (panel, label, status) ->
                if (i < count) {
                    panel.visibility = View.VISIBLE
                    bindCameraPanel(entries[i].key, label, status, i + 1)
                } else {
                    panel.visibility  = View.GONE
                    label.visibility  = View.GONE
                    status.visibility = View.GONE
                }
            }
            // Для 1 камери — розтягуємо на весь ряд
            val lp1 = b.videoView1.layoutParams as? android.widget.GridLayout.LayoutParams
            lp1?.columnSpec = if (count == 1)
                android.widget.GridLayout.spec(0, 2, android.widget.GridLayout.FILL, 1f)
            else
                android.widget.GridLayout.spec(0, 1, android.widget.GridLayout.FILL, 1f)
            b.videoView1.layoutParams = lp1

            // Для 3 камер — третя займає нижній лівий
            val lp3 = b.videoView3.layoutParams as? android.widget.GridLayout.LayoutParams
            lp3?.columnSpec = if (count == 3)
                android.widget.GridLayout.spec(0, 2, android.widget.GridLayout.FILL, 1f)
            else
                android.widget.GridLayout.spec(1, 1, android.widget.GridLayout.FILL, 1f)
            b.videoView3.layoutParams = lp3
        }

        // Статус-бар тривоги
        when {
            alarmOn -> {
                b.tvAlarm.text = "🚨 ТРИВОГА! Сигнал відправлено на ESP32!"
                b.tvAlarm.setBackgroundColor(0xFFFF1744.toInt())
            }
            count == 0 -> {
                b.tvAlarm.text = "Немає підключених камер"
                b.tvAlarm.setBackgroundColor(0xFF1A1A2E.toInt())
            }
            else -> {
                val allOk = statuses.values.all { it == "OBJECT_DETECTED" }
                b.tvAlarm.text = if (allOk) "✅ Кіт під наглядом" else "⚠️ Кіт не виявлений!"
                b.tvAlarm.setBackgroundColor(if (allOk) 0xFF1B5E20.toInt() else 0xFFE65100.toInt())
            }
        }
    }

    private fun bindCameraPanel(
        ip: String,
        label: android.widget.TextView,
        statusView: android.widget.TextView,
        num: Int
    ) {
        val status = statuses[ip] ?: "—"
        val found  = status == "OBJECT_DETECTED"
        label.text = "📷 Камера $num  ($ip)"
        statusView.text = if (found) "🐱 Кіт знайдений" else "👁 Сканування…"
        statusView.setBackgroundColor(if (found) 0xCC1B5E20.toInt() else 0xCC1A1A2E.toInt())
        label.visibility      = View.VISIBLE
        statusView.visibility = View.VISIBLE
    }

    // ─── Аудіо ───────────────────────────────────────────────────────────────
    private fun initAudio() {
        val sampleRate = 44100
        val bufSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build())
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun toggleAudio() {
        audioEnabled = !audioEnabled
        if (audioEnabled) {
            audioTrack?.play()
            b.btnAudio.text = "🔇 Вимк"
            sendToAllCameras("MIC_ON")
        } else {
            audioTrack?.pause()
            b.btnAudio.text = "🔊 Звук"
            sendToAllCameras("MIC_OFF")
        }
    }

    private fun sendToAllCameras(command: String) {
        clients.values.forEach { try { it.sendCommand(command) } catch (_: Exception) {} }
    }

    // ─── Відключення ─────────────────────────────────────────────────────────
    private fun disconnectAll() {
        clients.values.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear(); statuses.clear()
        alarmJob?.cancel()
        alarmOn = false
        audioTrack?.pause()
        audioEnabled = false
        b.btnAudio.text = "🔊 Звук"
        updateUI()
    }

    private fun safeUi(action: () -> Unit) {
        if (!isDestroyed && !isFinishing) runOnUiThread { if (!isDestroyed) action() }
    }

    override fun onDestroy() {
        _b = null
        super.onDestroy()
        clients.values.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear(); statuses.clear()
        alarmJob?.cancel()
        audioTrack?.release()
        Esp32.reset()
    }
}
