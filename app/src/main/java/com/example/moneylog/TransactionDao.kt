package com.example.moneylog

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Update
import androidx.room.Query

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(transaction: Transaction)

    @Update
    suspend fun update(transaction: Transaction)

    @Delete
    suspend fun delete(transaction: Transaction)

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    suspend fun getAll(): List<Transaction>

    @Query("SELECT SUM(amount) FROM transactions")
    suspend fun getTotalBalance(): Double?

    // FIX 7: Added ORDER BY timestamp DESC
    // Ensures search results are chronologically sorted so Date Headers (Today/Yesterday)
    // in the adapter do not break or appear sporadically.
    @Query("SELECT * FROM transactions WHERE description LIKE '%' || :keyword || '%' OR CAST(amount AS TEXT) LIKE '%' || :keyword || '%' ORDER BY timestamp DESC")
    suspend fun search(keyword: String): List<Transaction>

    @Query("SELECT COUNT(*) FROM transactions WHERE timestamp = :timestamp")
    suspend fun countTimestamp(timestamp: Long): Int

    // FIX: Exact match only. Removed date range window which caused aggressive rejection.
    @Query("SELECT COUNT(*) FROM transactions WHERE amount = :amount AND description = :desc AND timestamp = :timestamp")
    suspend fun checkDuplicate(amount: Double, desc: String, timestamp: Long): Int

    @Query("SELECT * FROM transactions WHERE timestamp = :timestamp LIMIT 1")
    suspend fun getByTimestamp(timestamp: Long): Transaction?

    @Query("SELECT * FROM transactions WHERE nature = 'ASSET' ORDER BY timestamp DESC")
    suspend fun getAssets(): List<Transaction>

    @Query("SELECT * FROM transactions WHERE nature = 'LIABILITY' ORDER BY timestamp DESC")
    suspend fun getLiabilities(): List<Transaction>

    @Query("SELECT SUM(obligationAmount) FROM transactions WHERE nature = 'ASSET'")
    suspend fun getTotalAssets(): Double?

    @Query("SELECT SUM(obligationAmount) FROM transactions WHERE nature = 'LIABILITY'")
    suspend fun getTotalLiabilities(): Double?
}