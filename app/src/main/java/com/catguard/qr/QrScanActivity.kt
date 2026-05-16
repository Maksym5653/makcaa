package com.catguard.qr

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.catguard.R
import com.catguard.viewer.ViewerActivity
import com.google.zxing.integration.android.IntentIntegrator
import com.google.zxing.integration.android.IntentResult

/**
 * QR Scanner Activity
 * Очікує QR-код формату: catguard://IP:PORT або просто IP-адресу
 * Після сканування -> відкриває ViewerActivity з IP
 *
 * Як згенерувати QR для камери:
 *   Значення: catguard://192.168.1.55:8765
 */
class QrScanActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt("Відскануйте QR-код камери CatGuard")
        integrator.setCameraId(0)
        integrator.setBeepEnabled(true)
        integrator.setBarcodeImageEnabled(false)
        integrator.initiateScan()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result: IntentResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
            ?: run {
                super.onActivityResult(requestCode, resultCode, data)
                finish()
                return
            }

        val raw = result.contents
        if (raw == null) {
            Toast.makeText(this, "Сканування скасовано", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Підтримуємо:
        //  catguard://192.168.1.55:8765
        //  192.168.1.55:8765
        //  192.168.1.55
        val ip = parseIp(raw)
        if (ip == null) {
            Toast.makeText(this, "Невірний QR-код: $raw", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Toast.makeText(this, "Підключаємось до $ip…", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, ViewerActivity::class.java).apply {
            putExtra(ViewerActivity.EXTRA_QR_IP, ip)
        }
        startActivity(intent)
        finish()
    }

    private fun parseIp(raw: String): String? {
        return try {
            val cleaned = raw
                .removePrefix("catguard://")
                .removePrefix("http://")
                .removePrefix("ws://")
                .trim()
            // Беремо лише IP (без порту)
            val ip = cleaned.split(":").first()
            // Валідація — має бути 4 октети
            val parts = ip.split(".")
            if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) ip else null
        } catch (_: Exception) { null }
    }
}
