package com.example.moneylog

import androidx.room.Entity
import androidx.room.PrimaryKey

// @Entity tells Room this is a table in the database
@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Int = 0, // Unique ID for every row
    val originalText: String, // The full raw text (e.g., "-50 coffee")
    val amount: Double,       // Parsed amount (-50.0)
    val description: String,  // Parsed text ("coffee")
    val timestamp: Long       // Time created (for sorting)
)