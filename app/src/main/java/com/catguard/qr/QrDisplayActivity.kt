package com.catguard.qr

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.catguard.R

/**
 * Показує QR-код для цієї камери.
 * Значення QR: catguard://IP:8765
 * Глядач сканує -> автоматично підключається
 */
class QrDisplayActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_display)

        val content = intent.getStringExtra("qr_content") ?: return

        val qrBitmap = generateQr(content, 800)
        findViewById<ImageView>(R.id.ivQr).setImageBitmap(qrBitmap)
        findViewById<TextView>(R.id.tvQrLabel).text =
            "Відскануйте цей QR у Viewer Mode\n$content"

        findViewById<android.widget.Button>(R.id.btnClose).setOnClickListener { finish() }
    }

    private fun generateQr(content: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val writer = QRCodeWriter()
        val matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}
