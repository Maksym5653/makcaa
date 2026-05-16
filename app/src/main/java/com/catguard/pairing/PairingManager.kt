package com.catguard.pairing

import android.content.Context
import android.content.SharedPreferences

/**
 * Зберігає 4-значний pairing code у SharedPreferences.
 * Код вводиться один раз і зберігається між запусками.
 *
 * Використання:
 *   val code = PairingManager.getCode(context)   // null якщо не введено
 *   PairingManager.saveCode(context, "5739")
 *   PairingManager.clearCode(context)
 */
object PairingManager {

    private const val PREFS_NAME = "catguard_pairing"
    private const val KEY_CODE   = "pairing_code"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Повертає збережений код або null якщо ще не введено */
    fun getCode(ctx: Context): String? {
        val code = prefs(ctx).getString(KEY_CODE, null)
        return if (code.isNullOrBlank()) null else code
    }

    /** Зберігає код (очищує пробіли) */
    fun saveCode(ctx: Context, code: String) {
        prefs(ctx).edit().putString(KEY_CODE, code.trim()).apply()
    }

    /** Видаляє збережений код (наприклад, при зміні ESP32) */
    fun clearCode(ctx: Context) {
        prefs(ctx).edit().remove(KEY_CODE).apply()
    }

    /** Перевіряє що код валідний: 4 цифри */
    fun isValid(code: String?): Boolean =
        code != null && code.trim().matches(Regex("\\d{4}"))
}
