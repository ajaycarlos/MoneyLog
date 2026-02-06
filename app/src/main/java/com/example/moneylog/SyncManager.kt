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
            // CHANGE THIS from APPEND_OR_REPLACE to REPLACE
            androidx.work.ExistingWorkPolicy.REPLACE,
            syncRequest
        )
        return syncRequest.id
    }

    // --- PENDING DELETES ---
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

    // --- NEW: PENDING EDITS (Tokenized Fix) ---
    // Protects local edits from being overwritten by older server data
    // Uses a "Timestamp:Token" format to handle race conditions

    private fun getRawPendingEdits(): MutableSet<String> {
        return prefs.getStringSet("pending_edits", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    }

    fun queueEdit(timestamp: Long) {
        val pending = getRawPendingEdits()
        // Remove any old token for this timestamp
        pending.removeAll { it.startsWith("$timestamp:") }

        // Add new unique token
        val token = UUID.randomUUID().toString().substring(0, 8)
        pending.add("$timestamp:$token")

        prefs.edit().putStringSet("pending_edits", pending).apply()
    }

    // Returns a Map of Timestamp -> Token
    fun getPendingEditsSnapshot(): Map<Long, String> {
        val map = mutableMapOf<Long, String>()
        getRawPendingEdits().forEach { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                map[parts[0].toLong()] = parts[1]
            }
        }
        return map
    }

    // Only remove if the token matches exactly.
    // If the token changed (user edited again), we do NOT remove it.
    fun removePendingEdit(timestamp: Long, token: String) {
        val pending = getRawPendingEdits()
        val entry = "$timestamp:$token"
        if (pending.contains(entry)) {
            pending.remove(entry)
            prefs.edit().putStringSet("pending_edits", pending).apply()
        }
    }

    // Helper for Step 3: Check if ANY pending edit exists for this timestamp
    fun hasPendingEdit(timestamp: Long): Boolean {
        return getRawPendingEdits().any { it.startsWith("$timestamp:") }
    }

    // --- HELPERS ---
    fun generateStableId(timestamp: Long): String {
        val input = "$timestamp"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun getInstallationId(): String {
        var id = prefs.getString("installation_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("installation_id", id).apply()
        }
        return id!!
    }

    fun unlinkDevice() {
        val newVault = UUID.randomUUID().toString()
        val newKey = UUID.randomUUID().toString().substring(0, 16)

        prefs.edit()
            .putString("vault_id", newVault)
            .putString("secret_key", newKey)
            .apply()
    }
}