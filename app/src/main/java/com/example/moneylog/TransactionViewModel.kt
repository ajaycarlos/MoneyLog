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

    private val _assets = MutableLiveData<List<Transaction>>()
    val assets: LiveData<List<Transaction>> = _assets

    private val _liabilities = MutableLiveData<List<Transaction>>()
    val liabilities: LiveData<List<Transaction>> = _liabilities

    private val _totalAssets = MutableLiveData<Double>()
    val totalAssets: LiveData<Double> = _totalAssets

    private val _totalLiabilities = MutableLiveData<Double>()
    val totalLiabilities: LiveData<Double> = _totalLiabilities

    init {
        val db = Room.databaseBuilder(application, AppDatabase::class.java, "moneylog-db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .build()
        val syncManager = SyncManager(application, db)
        repository = TransactionRepository(db, syncManager)

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

    fun addTransaction(originalText: String, amount: Double, desc: String, nature: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val obligation = calculateObligation(amount, nature)

            // FIX: Enforce uniqueness at creation to prevent Sync ID collisions
            var ts = System.currentTimeMillis()
            while (repository.getByTimestamp(ts) != null) {
                ts += 1
            }

            val t = Transaction(
                originalText = originalText,
                amount = amount,
                description = desc,
                timestamp = ts,
                nature = nature,
                obligationAmount = obligation
            )
            repository.insert(t)
            refreshData()
        }
    }

    // FIX: Updated to verify math consistency
    fun updateTransaction(transaction: Transaction) {
        viewModelScope.launch(Dispatchers.IO) {
            // Recalculate obligation in case the Amount OR Nature changed during the edit
            val newObligation = calculateObligation(transaction.amount, transaction.nature)

            val finalTransaction = transaction.copy(obligationAmount = newObligation)

            repository.update(finalTransaction)
            refreshData()
        }
    }

    // Helper logic to keep Add/Edit consistent

    // FIX: Pure Inversion Logic
    private fun calculateObligation(amount: Double, nature: String): Double {
        return when (nature) {
            "ASSET", "LIABILITY" -> {
                // Simply invert the sign.
                // If you Lend (-500), Obligation becomes +500 (Added to Asset).
                // If you get Repaid (+300), Obligation becomes -300 (Subtracted from Asset).
                -amount
            }
            else -> 0.0
        }
    }

    fun loadAssetsAndLiabilities() {
        viewModelScope.launch(Dispatchers.IO) {
            val assetList = repository.getAssets()
            val liabilityList = repository.getLiabilities()
            val assetTotal = repository.getTotalAssets()
            val liabilityTotal = repository.getTotalLiabilities()

            withContext(Dispatchers.Main) {
                _assets.value = assetList
                _liabilities.value = liabilityList
                _totalAssets.value = assetTotal ?: 0.0
                _totalLiabilities.value = liabilityTotal ?: 0.0
            }
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
                // FIX: Removed 60-second window. Now checks Exact Match (Timestamp + Amount + Desc)
                // This prevents legitimate simultaneous purchases from being discarded.
                val count = repository.checkDuplicate(t.amount, t.description, t.timestamp)
                if (count == 0) {
                    repository.insert(t)
                }
            }
            refreshData()
        }
    }

    fun scheduleSync(forcePush: Boolean = false): UUID {
        return repository.scheduleSync(forcePush)
    }
}