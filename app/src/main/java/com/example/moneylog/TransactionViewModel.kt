package com.example.moneylog

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class TransactionViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: TransactionRepository


    private val _transactions = MutableLiveData<List<Transaction>>()
    val transactions: LiveData<List<Transaction>> = _transactions

    private val _totalBalance = MutableLiveData<Double>()
    val totalBalance: LiveData<Double> = _totalBalance

    // Using AndroidViewModel gives us access to 'application' context for DB init
    init {
        val db = Room.databaseBuilder(application, AppDatabase::class.java, "moneylog-db").build()
        val syncManager = SyncManager(application, db)
        repository = TransactionRepository(db, syncManager)

        // Initial Load
        refreshData()
    }

    fun refreshData() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = repository.getAllTransactions()
            val total = list.sumOf { it.amount }

            withContext(Dispatchers.Main) {
                _transactions.value = list
                _totalBalance.value = total
            }
        }
    }

    fun addTransaction(originalText: String, amount: Double, desc: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val t = Transaction(
                originalText = originalText,
                amount = amount,
                description = desc,
                timestamp = System.currentTimeMillis()
            )
            repository.insert(t)
            refreshData()
        }
    }

    fun updateTransaction(transaction: Transaction) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.update(transaction)
            refreshData()
        }
    }

    fun deleteTransaction(transaction: Transaction) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(transaction)
            refreshData()
        }
    }

    fun search(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val results = repository.search(query)
            withContext(Dispatchers.Main) {
                _transactions.value = results
                // Don't update balance during search, or do? Usually better to keep total balance visible.
            }
        }
    }

    fun importTransactionList(list: List<Transaction>) {
        viewModelScope.launch(Dispatchers.IO) {
            for(t in list) {
                // Check dupes within 60 seconds
                val count = repository.checkDuplicate(t.amount, t.description, t.timestamp, t.timestamp + 59999)
                if (count == 0) {
                    repository.insert(t)
                }
            }
            refreshData()
        }
    }

    // Returns the Work ID so the View can observe the Status
    fun scheduleSync(): UUID {
        return repository.scheduleSync()
    }
}