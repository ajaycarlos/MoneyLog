package com.example.moneylog

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)
        val vaultId = prefs.getString("vault_id", null)
        val secretKey = prefs.getString("secret_key", null)

        if (vaultId == null || secretKey == null) {
            return Result.failure(workDataOf("MSG" to "Skipped: Device not linked"))
        }

        // Initialize DB specifically for this background thread
        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "moneylog-db").build()

        try {
            val ref = FirebaseDatabase.getInstance().getReference("vaults").child(vaultId)

            // STEP 1: Download the "Graveyard" (Deleted IDs)
            val deletedSnapshot = ref.child("deleted").get().await()
            val deletedIds = mutableSetOf<String>()
            for (child in deletedSnapshot.children) {
                child.key?.let { deletedIds.add(it) }
            }

            // STEP 2: Process Local Data (Delete dead ones, Upload living ones)
            var deletedOps = 0
            val localData = db.transactionDao().getAll()
            for (t in localData) {
                val uniqueId = generateId(t, secretKey)
                if (deletedIds.contains(uniqueId)) {
                    db.transactionDao().delete(t)
                    deletedOps++
                } else {
                    // Upload encrypted
                    val json = "{\"o\":\"${t.originalText}\", \"a\":${t.amount}, \"d\":\"${t.description}\", \"t\":${t.timestamp}}"
                    val encryptedData = EncryptionHelper.encrypt(json, secretKey)
                    ref.child("transactions").child(uniqueId).setValue(encryptedData)
                }
            }

            // STEP 3: Download New Data
            val serverSnapshot = ref.child("transactions").get().await()
            var newItemsCount = 0

            for (child in serverSnapshot.children) {
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

                        val exists = db.transactionDao().checkDuplicate(amount, desc, timestamp - 100, timestamp + 100)
                        if (exists == 0) {
                            db.transactionDao().insert(Transaction(
                                originalText = originalText,
                                amount = amount,
                                description = desc,
                                timestamp = timestamp
                            ))
                            newItemsCount++
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }

            db.close()

            // Return Success with a message
            val msg = if (newItemsCount > 0 || deletedOps > 0) {
                "Synced: $newItemsCount new, $deletedOps deleted"
            } else {
                "Sync Complete: Up to date"
            }
            return Result.success(workDataOf("MSG" to msg))

        } catch (e: Exception) {
            db.close()
            return Result.failure(workDataOf("MSG" to "Error: ${e.message}"))
        }
    }

    private fun generateId(t: Transaction, secretKey: String): String {
        val rawString = "${t.amount}|${t.description}|${t.timestamp}"
        return EncryptionHelper.encrypt(rawString, secretKey)
            .replace("/", "_").replace("+", "-").replace("=", "")
    }
}