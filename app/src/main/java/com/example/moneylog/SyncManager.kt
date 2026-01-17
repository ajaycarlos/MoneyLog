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
import java.util.UUID

class SyncManager(private val context: Context, private val db: AppDatabase) {

    private val prefs = context.getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)

    // Helper: Enqueue the WorkManager Job
    fun scheduleSync(): UUID {
        // Constraint: Only run when connected to Network
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueue(syncRequest)
        return syncRequest.id
    }

    // "Push Delete" is small enough to keep as a quick fire-and-forget
    fun pushDelete(t: Transaction) {
        val vaultId = prefs.getString("vault_id", null)
        val secretKey = prefs.getString("secret_key", null) ?: return
        if (vaultId == null) return

        GlobalScope.launch(Dispatchers.IO) {
            val uniqueId = generateId(t, secretKey)
            val ref = FirebaseDatabase.getInstance().getReference("vaults").child(vaultId)
            ref.child("transactions").child(uniqueId).removeValue()
            ref.child("deleted").child(uniqueId).setValue(System.currentTimeMillis())
        }
    }

    private fun generateId(t: Transaction, secretKey: String): String {
        val rawString = "${t.amount}|${t.description}|${t.timestamp}"
        return EncryptionHelper.encrypt(rawString, secretKey)
            .replace("/", "_").replace("+", "-").replace("=", "")
    }
}