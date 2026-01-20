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
        val forcePush = inputData.getBoolean("FORCE_PUSH", false) // <-- Retrieve Flag

        if (vaultId == null || secretKey == null) {
            return Result.failure(workDataOf("MSG" to "Skipped: Device not linked"))
        }

        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "moneylog-db").build()

        try {
            val ref = FirebaseDatabase.getInstance().getReference("vaults").child(vaultId)

            // 1. Fetch Server Data
            val serverSnapshot = ref.child("transactions").get().await()
            val serverMap = mutableMapOf<Long, String>()

            // Map Timestamp -> Key (No changes here)
            for (child in serverSnapshot.children) {
                try {
                    val encryptedJson = child.getValue(String::class.java) ?: continue
                    val jsonStr = EncryptionHelper.decrypt(encryptedJson, secretKey)
                    val parts = jsonStr.replace("{", "").replace("}", "").split(",")
                    for (part in parts) {
                        if (part.contains("\"t\":")) {
                            val ts = part.split(":")[1].trim().toLong()
                            serverMap[ts] = child.key!!
                        }
                    }
                } catch (e: Exception) { }
            }

            // 2. Fetch Deleted List (No changes here)
            val deletedSnapshot = ref.child("deleted").get().await()
            val deletedIds = mutableSetOf<String>()
            for (child in deletedSnapshot.children) {
                child.key?.let { deletedIds.add(it) }
            }

            // 3. Process Local Data (Push Logic)
            val localData = db.transactionDao().getAll()
            var changesCount = 0
            // Track what we pushed so we don't re-download it immediately
            val pushedKeys = mutableSetOf<String>()

            for (t in localData) {
                val stableId = generateStableId(t.timestamp)

                if (deletedIds.contains(stableId)) {
                    db.transactionDao().delete(t)
                    changesCount++
                } else {
                    // Generate Data
                    val json = "{\"o\":\"${t.originalText}\", \"a\":${t.amount}, \"d\":\"${t.description}\", \"t\":${t.timestamp}}"
                    val encryptedData = EncryptionHelper.encrypt(json, secretKey)

                    // CONFLICT CHECK:
                    var shouldPush = true
                    if (serverSnapshot.hasChild(stableId)) {
                        val serverVal = serverSnapshot.child(stableId).value.toString()
                        // If content is identical, skip push
                        if (serverVal == encryptedData) {
                            shouldPush = false
                        } else if (!forcePush) {
                            // Content differs, and we are NOT forcing. SERVER WINS.
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

                // If we just pushed this, don't overwrite local with the old snapshot data
                if (pushedKeys.contains(serverKey)) continue

                val encryptedJson = child.getValue(String::class.java) ?: continue
                val jsonStr = EncryptionHelper.decrypt(encryptedJson, secretKey)

                if (jsonStr.isNotEmpty()) {
                    try {
                        // Parse JSON (Same as before)
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

                        // LOGIC CHANGE: Check for update
                        val existing = db.transactionDao().getByTimestamp(timestamp)

                        if (existing == null) {
                            // Insert New
                            db.transactionDao().insert(Transaction(
                                originalText = originalText,
                                amount = amount,
                                description = desc,
                                timestamp = timestamp
                            ))
                            changesCount++
                        } else {
                            // Update Existing if changed
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