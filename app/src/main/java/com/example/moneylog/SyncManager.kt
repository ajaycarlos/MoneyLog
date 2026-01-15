package com.example.moneylog

import android.content.Context
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class SyncManager(private val context: Context, private val db: AppDatabase) {

    private val prefs = context.getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)

    // Helper to generate the unique ID for a transaction
    private fun generateId(t: Transaction, secretKey: String): String {
        val rawString = "${t.amount}|${t.description}|${t.timestamp}"
        return EncryptionHelper.encrypt(rawString, secretKey)
            .replace("/", "_").replace("+", "-").replace("=", "")
    }

    // NEW: Called when user hits "Delete"
    fun pushDelete(t: Transaction) {
        val vaultId = prefs.getString("vault_id", null)
        val secretKey = prefs.getString("secret_key", null) ?: return
        if (vaultId == null) return

        GlobalScope.launch(Dispatchers.IO) {
            val uniqueId = generateId(t, secretKey)
            val ref = FirebaseDatabase.getInstance().getReference("vaults").child(vaultId)

            // 1. Remove from 'transactions' node
            ref.child("transactions").child(uniqueId).removeValue()

            // 2. Add to 'deleted' node (The Tombstone)
            ref.child("deleted").child(uniqueId).setValue(System.currentTimeMillis())
        }
    }

    fun syncData(onComplete: (String) -> Unit) {
        val vaultId = prefs.getString("vault_id", null)
        val secretKey = prefs.getString("secret_key", null)

        if (vaultId == null || secretKey == null) {
            onComplete("Skipped: Device not linked")
            return
        }

        val ref = FirebaseDatabase.getInstance().getReference("vaults").child(vaultId)

        GlobalScope.launch(Dispatchers.IO) {
            try {
                // STEP 1: Download the "Graveyard" (Deleted IDs) first
                val deletedSnapshot = ref.child("deleted").get().await()
                val deletedIds = mutableSetOf<String>()
                for (child in deletedSnapshot.children) {
                    child.key?.let { deletedIds.add(it) }
                }

                // STEP 2: Process Local Data
                val localData = db.transactionDao().getAll()
                for (t in localData) {
                    val uniqueId = generateId(t, secretKey)

                    if (deletedIds.contains(uniqueId)) {
                        // "I have it, but the Cloud says it's dead." -> Kill it locally.
                        db.transactionDao().delete(t)
                    } else {
                        // It's alive. Upload it.
                        val json = "{\"o\":\"${t.originalText}\", \"a\":${t.amount}, \"d\":\"${t.description}\", \"t\":${t.timestamp}}"
                        val encryptedData = EncryptionHelper.encrypt(json, secretKey)
                        ref.child("transactions").child(uniqueId).setValue(encryptedData)
                    }
                }

                // STEP 3: Download New Data (The Pull)
                val serverSnapshot = ref.child("transactions").get().await()
                var newItemsCount = 0

                for (child in serverSnapshot.children) {
                    val encryptedJson = child.getValue(String::class.java) ?: continue

                    // Optimization: If we already have this ID locally, skip decryption
                    // (We can't easily check ID match in Room without iterating,
                    // but we can trust the 'deletedIds' check we just did).

                    val jsonStr = EncryptionHelper.decrypt(encryptedJson, secretKey)

                    if (jsonStr.isNotEmpty()) {
                        try {
                            val clean = jsonStr.replace("{", "").replace("}", "").replace("\"", "")
                            val parts = clean.split(",")

                            var originalText = ""
                            var amount = 0.0
                            var desc = ""
                            var timestamp = 0L

                            for (part in parts) {
                                val kv = part.split(":")
                                when(kv[0].trim()) {
                                    "o" -> originalText = kv[1]
                                    "a" -> amount = kv[1].toDouble()
                                    "d" -> desc = kv[1]
                                    "t" -> timestamp = kv[1].toLong()
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
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    if (newItemsCount > 0) {
                        onComplete("Synced: $newItemsCount new items")
                    } else {
                        onComplete("Sync Complete: Up to date")
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onComplete("Sync Error: ${e.message}")
                }
            }
        }
    }
}