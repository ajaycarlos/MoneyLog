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

    // NEW: Capability to edit an existing row
    @Update
    suspend fun update(transaction: Transaction)

    @Delete
    suspend fun delete(transaction: Transaction)

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    suspend fun getAll(): List<Transaction>

    @Query("SELECT SUM(amount) FROM transactions")
    suspend fun getTotalBalance(): Double?

    @Query("SELECT * FROM transactions WHERE description LIKE '%' || :keyword || '%'")
    suspend fun search(keyword: String): List<Transaction>

    @Query("SELECT COUNT(*) FROM transactions WHERE timestamp = :timestamp")
    suspend fun countTimestamp(timestamp: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE amount = :amount AND description = :desc AND timestamp BETWEEN :start AND :end")
    suspend fun checkDuplicate(amount: Double, desc: String, start: Long, end: Long): Int

    @Query("SELECT * FROM transactions WHERE timestamp = :timestamp LIMIT 1")
    suspend fun getByTimestamp(timestamp: Long): Transaction?


}