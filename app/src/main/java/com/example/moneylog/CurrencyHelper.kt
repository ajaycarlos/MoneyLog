package com.example.moneylog

import android.content.Context

object CurrencyHelper {
    private const val PREFS_NAME = "moneylog_prefs"
    private const val KEY_CURRENCY = "key_currency"

    // Default currencies list
    val CURRENCIES = arrayOf("₹ INR", "$ USD", "€ EUR", "£ GBP", "¥ JPY", "₩ KRW", "₽ RUB")

    fun getSymbol(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Default to INR if nothing selected yet, but UI will force selection on first launch
        val stored = prefs.getString(KEY_CURRENCY, "₹ INR") ?: "₹ INR"
        return stored.split(" ")[0] // Returns just the symbol (e.g., "₹")
    }

    fun setCurrency(context: Context, selection: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CURRENCY, selection).apply()
    }

    fun isCurrencySet(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_CURRENCY)
    }
}