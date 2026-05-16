package com.catguard.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * ESP32 хаб — доступний через mDNS: http://catguard.local
 *
 * Авторизація через 4-значний pairing code:
 *   GET http://catguard.local/cat?code=XXXX   → вмикає тривогу (якщо код вірний)
 *   GET http://catguard.local/ping             → перевірка зв'язку
 *   401 Unauthorized                           → код невірний
 *
 * Код генерується ESP32 при старті і показується на TFT-екрані.
 * Користувач вводить його один раз в додаток → зберігається у SharedPreferences.
 */
object Esp32 {

    private const val HOST = "catguard.local"
    private val BASE = "http://$HOST"

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Результат відправки запиту
    sealed class SendResult {
        object Ok         : SendResult()
        object Unauthorized : SendResult()   // 401 — невірний код
        object Unreachable  : SendResult()   // мережева помилка
    }

    /**
     * Відправляє GET /cat?code=XXXX на ESP32.
     * ESP32 вмикає тривогу якщо код збігається, інакше повертає 401.
     */
    suspend fun sendCatAlert(pairingCode: String): SendResult = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE/cat?code=${pairingCode.trim()}"
            val req = Request.Builder().url(url).build()
            val resp = http.newCall(req).execute()
            Log.i("ESP32", "sendCatAlert → HTTP ${resp.code} | url=$url")
            when (resp.code) {
                200, 204    -> SendResult.Ok
                401, 403    -> SendResult.Unauthorized
                else        -> SendResult.Unreachable
            }
        } catch (e: Exception) {
            Log.e("ESP32", "sendCatAlert помилка: ${e.message}")
            SendResult.Unreachable
        }
    }

    /**
     * Перевіряє доступність хаба.
     * Повертає true якщо catguard.local відповідає.
     */
    suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("$BASE/ping").build()
            val resp = http.newCall(req).execute()
            resp.isSuccessful
        } catch (_: Exception) { false }
    }

    fun reset() { /* нічого не кешуємо */ }
}
