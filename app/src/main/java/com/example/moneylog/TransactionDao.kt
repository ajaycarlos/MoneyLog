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

    // FIX: Now searches both Description AND Amount
    // We cast amount to TEXT so '500' matches '500.0' or '-500'
    @Query("SELECT * FROM transactions WHERE description LIKE '%' || :keyword || '%' OR CAST(amount AS TEXT) LIKE '%' || :keyword || '%'")
    suspend fun search(keyword: String): List<Transaction>

    @Query("SELECT COUNT(*) FROM transactions WHERE timestamp = :timestamp")
    suspend fun countTimestamp(timestamp: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE amount = :amount AND description = :desc AND timestamp BETWEEN :start AND :end")
    suspend fun checkDuplicate(amount: Double, desc: String, start: Long, end: Long): Int

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