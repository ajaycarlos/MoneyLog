package com.example.moneylog

import androidx.room.Database
import androidx.room.RoomDatabase

// List all tables (entities) here
@Database(entities = [Transaction::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
}