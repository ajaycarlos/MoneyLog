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

            // BUG 6 FIX: Calculate the balance of the SEARCH RESULTS
            // Previously, the balance remained at the global total, which was confusing.
            val searchTotal = results.sumOf { it.amount }

            withContext(Dispatchers.Main) {
                _transactions.value = results
                _totalBalance.value = searchTotal
            }
        }
    }

    fun importTransactionList(list: List<Transaction>) {
        viewModelScope.launch(Dispatchers.IO) {
            for(t in list) {
                // BUG 5 FIX: Check both BEFORE and AFTER the timestamp (+/- 60 seconds)
                // Previous logic only checked forward (t.timestamp + 59999), causing duplicates
                // if the existing item was even 1 millisecond older.
                val start = t.timestamp - 60000
                val end = t.timestamp + 60000

                val count = repository.checkDuplicate(t.amount, t.description, start, end)
                if (count == 0) {
                    repository.insert(t)
                }
            }
            refreshData()
        }
    }

    // Returns the Work ID so the View can observe the Status
    fun scheduleSync(forcePush: Boolean = false): UUID {
        return repository.scheduleSync(forcePush)
    }
}