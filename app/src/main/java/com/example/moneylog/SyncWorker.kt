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
        val forcePush = inputData.getBoolean("FORCE_PUSH", false)

        // FIX: If device is not linked, just return Success silently.
        // Returning 'failure' caused the "Skipped: Device not linked" popup.
        if (vaultId == null || secretKey == null) {
            return Result.success()
        }

        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "moneylog-db").build()
        val syncManager = SyncManager(applicationContext, db)

        try {
            val ref = FirebaseDatabase.getInstance().getReference("vaults").child(vaultId)

            // STEP 0: PROCESS PENDING DELETES
            val pendingDeletes = syncManager.getPendingDeletes()
            for (tsString in pendingDeletes) {
                val ts = tsString.toLongOrNull() ?: continue
                val stableId = syncManager.generateStableId(ts)
                try {
                    ref.child("transactions").child(stableId).removeValue().await()
                    ref.child("deleted").child(stableId).setValue(System.currentTimeMillis()).await()
                    syncManager.removePendingDelete(ts)
                } catch (e: Exception) { }
            }
            val activePendingDeletes = syncManager.getPendingDeletes().mapNotNull { it.toLongOrNull() }.toSet()

            // 1. Fetch Server Data
            val serverSnapshot = ref.child("transactions").get().await()
            val deletedSnapshot = ref.child("deleted").get().await()
            val deletedIds = mutableSetOf<String>()
            for (child in deletedSnapshot.children) {
                child.key?.let { deletedIds.add(it) }
            }

            // 3. Process Local Data (Push Logic)
            val localData = db.transactionDao().getAll()
            var changesCount = 0
            val pushedKeys = mutableSetOf<String>()

            for (t in localData) {
                val stableId = syncManager.generateStableId(t.timestamp)

                if (deletedIds.contains(stableId)) {
                    db.transactionDao().delete(t)
                    changesCount++
                } else {
                    // JSON Generation
                    val jsonObject = org.json.JSONObject()
                    jsonObject.put("o", t.originalText)
                    jsonObject.put("a", t.amount)
                    jsonObject.put("d", t.description)
                    jsonObject.put("t", t.timestamp)

                    val encryptedData = EncryptionHelper.encrypt(jsonObject.toString(), secretKey)

                    // Conflict Check
                    var shouldPush = true
                    if (serverSnapshot.hasChild(stableId)) {
                        val serverVal = serverSnapshot.child(stableId).value.toString()
                        if (serverVal == encryptedData) {
                            shouldPush = false
                        } else if (!forcePush) {
                            shouldPush = false
                        }
                    }

                    if (shouldPush) {
                        ref.child("transactions").child(stableId).setValue(encryptedData)
                        pushedKeys.add(stableId)
                    }
                }
            }

            // 4. Download/Update Server Items
            for (child in serverSnapshot.children) {
                val serverKey = child.key ?: continue
                if (pushedKeys.contains(serverKey)) continue

                val encryptedJson = child.getValue(String::class.java) ?: continue
                val jsonStr = EncryptionHelper.decrypt(encryptedJson, secretKey)

                if (jsonStr.isNotEmpty()) {
                    try {
                        val jsonObject = org.json.JSONObject(jsonStr)
                        val originalText = jsonObject.optString("o")
                        val amount = jsonObject.optDouble("a")
                        val desc = jsonObject.optString("d")
                        val timestamp = jsonObject.optLong("t")

                        if (activePendingDeletes.contains(timestamp)) continue

                        val existing = db.transactionDao().getByTimestamp(timestamp)

                        if (existing == null) {
                            db.transactionDao().insert(Transaction(
                                originalText = originalText,
                                amount = amount,
                                description = desc,
                                timestamp = timestamp
                            ))
                            changesCount++
                        } else {
                            if (existing.originalText != originalText || existing.amount != amount || existing.description != desc) {
                                val updated = existing.copy(
                                    originalText = originalText,
                                    amount = amount,
                                    description = desc
                                )
                                db.transactionDao().update(updated)
                                changesCount++
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
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