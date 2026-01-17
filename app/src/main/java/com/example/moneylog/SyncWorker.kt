package com.example.moneylog

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)
        val vaultId = prefs.getString("vault_id", null)
        val secretKey = prefs.getString("secret_key", null)

        if (vaultId == null || secretKey == null) {
            return Result.failure(workDataOf("MSG" to "Skipped: Device not linked"))
        }

        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "moneylog-db").build()

        try {
            val ref = FirebaseDatabase.getInstance().getReference("vaults").child(vaultId)

            // 1. Fetch Server Data First (To map timestamps to keys)
            val serverSnapshot = ref.child("transactions").get().await()
            val serverMap = mutableMapOf<Long, String>() // Map Timestamp -> Firebase Key

            for (child in serverSnapshot.children) {
                try {
                    val encryptedJson = child.getValue(String::class.java) ?: continue
                    val jsonStr = EncryptionHelper.decrypt(encryptedJson, secretKey)
                    // Extract timestamp purely to identify the item
                    val parts = jsonStr.replace("{", "").replace("}", "").split(",")
                    for (part in parts) {
                        if (part.contains("\"t\":")) {
                            val ts = part.split(":")[1].trim().toLong()
                            serverMap[ts] = child.key!! // Store "1000" -> "Old_Key_ABC"
                        }
                    }
                } catch (e: Exception) { /* Ignore corrupt items */ }
            }

            // 2. Fetch Deleted List
            val deletedSnapshot = ref.child("deleted").get().await()
            val deletedIds = mutableSetOf<String>()
            for (child in deletedSnapshot.children) {
                child.key?.let { deletedIds.add(it) }
            }

            // 3. Process Local Data (Upload & Clean Old Keys)
            val localData = db.transactionDao().getAll()
            var changesCount = 0

            for (t in localData) {
                val stableId = generateStableId(t.timestamp)

                if (deletedIds.contains(stableId)) {
                    // It was deleted on another device
                    db.transactionDao().delete(t)
                    changesCount++
                } else {
                    // CLEANUP: If this transaction exists on server under a DIFFERENT key (Old Bug), delete the old key
                    val oldKey = serverMap[t.timestamp]
                    if (oldKey != null && oldKey != stableId) {
                        ref.child("transactions").child(oldKey).removeValue()
                    }

                    // Upload with New Stable ID
                    val json = "{\"o\":\"${t.originalText}\", \"a\":${t.amount}, \"d\":\"${t.description}\", \"t\":${t.timestamp}}"
                    val encryptedData = EncryptionHelper.encrypt(json, secretKey)
                    ref.child("transactions").child(stableId).setValue(encryptedData)
                }
            }

            // 4. Download New Items (That we don't have locally)
            for (child in serverSnapshot.children) {
                val serverKey = child.key ?: continue

                // If we just uploaded this, skip
                if (localData.any { generateStableId(it.timestamp) == serverKey }) continue

                val encryptedJson = child.getValue(String::class.java) ?: continue
                val jsonStr = EncryptionHelper.decrypt(encryptedJson, secretKey)

                if (jsonStr.isNotEmpty()) {
                    try {
                        val clean = jsonStr.replace("{", "").replace("}", "").replace("\"", "")
                        val parts = clean.split(",")
                        var originalText = ""; var amount = 0.0; var desc = ""; var timestamp = 0L

                        for (part in parts) {
                            val kv = part.split(":")
                            if (kv.size >= 2) {
                                when (kv[0].trim()) {
                                    "o" -> originalText = kv[1]
                                    "a" -> amount = kv[1].toDouble()
                                    "d" -> desc = kv[1]
                                    "t" -> timestamp = kv[1].toLong()
                                }
                            }
                        }

                        // Use strict Timestamp check to avoid duplicates
                        val exists = db.transactionDao().countTimestamp(timestamp)
                        if (exists == 0) {
                            db.transactionDao().insert(Transaction(
                                originalText = originalText,
                                amount = amount,
                                description = desc,
                                timestamp = timestamp
                            ))
                            changesCount++
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }

            db.close()

            val msg = if (changesCount > 0) "Synced: $changesCount updates" else "Sync Complete"
            return Result.success(workDataOf("MSG" to msg))

        } catch (e: Exception) {
            db.close()
            return Result.failure(workDataOf("MSG" to "Error: ${e.message}"))
        }
    }

    private fun generateStableId(timestamp: Long): String {
        val input = "$timestamp"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}