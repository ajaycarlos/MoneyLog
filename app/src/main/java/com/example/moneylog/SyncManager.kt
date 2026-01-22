package com.example.moneylog

import android.content.Context
import androidx.room.Room
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


        // This ensures that if an Offline Edit (Force=True) is pending,
        // a subsequent 'onResume' Sync (Force=False) MUST wait for the Edit to finish first.
        // Prevents the "Refresh" from overwriting the "Edit".
        WorkManager.getInstance(context).enqueueUniqueWork(
            "JotPaySyncQueue",
            androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
            syncRequest
        )

        return syncRequest.id
    }

    // FIX: Instead of trying to delete immediately (which might fail),
    // we just save the timestamp to a "Pending List" and let the Worker handle it.
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

    // Helper to generate the Stable ID (used by Worker)
    fun generateStableId(timestamp: Long): String {
        val input = "$timestamp"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}