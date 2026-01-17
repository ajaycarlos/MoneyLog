package com.example.moneylog

import java.util.UUID

class TransactionRepository(private val db: AppDatabase, private val syncManager: SyncManager) {

    // Passthroug to DAO
    suspend fun getAllTransactions() = db.transactionDao().getAll()

    suspend fun search(query: String) = db.transactionDao().search(query)

    suspend fun insert(transaction: Transaction) {
        db.transactionDao().insert(transaction)
    }

    suspend fun update(transaction: Transaction) {
        db.transactionDao().update(transaction)
    }

    suspend fun delete(transaction: Transaction) {
        // The Tombstone logic
        syncManager.pushDelete(transaction)
        // 2. Delete Locally
        db.transactionDao().delete(transaction)
    }

    suspend fun checkDuplicate(amount: Double, desc: String, start: Long, end: Long): Int {
        return db.transactionDao().checkDuplicate(amount, desc, start, end)
    }

    // Trigger the Syncworker
    fun scheduleSync(): UUID {
        return syncManager.scheduleSync()
    }
}