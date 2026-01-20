package com.example.moneylog

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

class SyncManager(private val context: Context, private val db: AppDatabase) {

    private val prefs = context.getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)

    fun scheduleSync(forcePush: Boolean = false): UUID {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val data = androidx.work.workDataOf("FORCE_PUSH" to forcePush) // Pass flag

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(data) // Attach data
            .build()

        WorkManager.getInstance(context).enqueue(syncRequest)
        return syncRequest.id
    }

    fun pushDelete(t: Transaction) {
        val vaultId = prefs.getString("vault_id", null)
        val secretKey = prefs.getString("secret_key", null) ?: return
        if (vaultId == null) return

        GlobalScope.launch(Dispatchers.IO) {
            // Use the new STABLE ID (based on timestamp only)
            val uniqueId = generateStableId(t.timestamp)
            val ref = FirebaseDatabase.getInstance().getReference("vaults").child(vaultId)

            ref.child("transactions").child(uniqueId).removeValue()
            ref.child("deleted").child(uniqueId).setValue(System.currentTimeMillis())
        }
    }

    // New ID Logic: MD5(timestamp). Stable even if text changes.
    private fun generateStableId(timestamp: Long): String {
        val input = "$timestamp"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}