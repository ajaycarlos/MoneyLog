package com.example.moneylog

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.security.MessageDigest
import java.util.UUID

class SyncManager(private val context: Context, private val db: AppDatabase) {

    private val prefs = context.getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)

    fun scheduleSync(forcePush: Boolean = false): UUID {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val data = androidx.work.workDataOf("FORCE_PUSH" to forcePush)

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "JotPaySyncQueue",
            androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
            syncRequest
        )
        return syncRequest.id
    }

    fun queueDelete(timestamp: Long) {
        val pending = getPendingDeletes().toMutableSet()
        pending.add(timestamp.toString())
        prefs.edit().putStringSet("pending_deletes", pending).apply()
    }

    fun getPendingDeletes(): MutableSet<String> {
        return prefs.getStringSet("pending_deletes", mutableSetOf()) ?: mutableSetOf()
    }

    fun removePendingDelete(timestamp: Long) {
        val pending = getPendingDeletes().toMutableSet()
        pending.remove(timestamp.toString())
        prefs.edit().putStringSet("pending_deletes", pending).apply()
    }

    fun generateStableId(timestamp: Long): String {
        val input = "$timestamp"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // --- NEW: Device Identity & Unlink Logic ---

    fun getInstallationId(): String {
        var id = prefs.getString("installation_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("installation_id", id).apply()
        }
        return id!!
    }

    fun unlinkDevice() {
        // Reset to a FRESH random vault, effectively "Unlinking" from the shared one
        val newVault = UUID.randomUUID().toString()
        val newKey = UUID.randomUUID().toString().substring(0, 16)

        prefs.edit()
            .putString("vault_id", newVault)
            .putString("secret_key", newKey)
            .apply()
    }
}