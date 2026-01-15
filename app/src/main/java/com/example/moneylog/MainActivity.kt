package com.example.moneylog

import android.animation.ValueAnimator
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.room.Room
import com.example.moneylog.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Calendar
import java.util.HashMap
import java.util.Locale
import android.content.Intent

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var db: AppDatabase
    private lateinit var adapter: TransactionAdapter
    private var editingTransaction: Transaction? = null
    private var currentDisplayedBalance = 0.0
    private lateinit var syncManager: SyncManager

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importData(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "moneylog-db").build()
        syncManager = SyncManager(this, db)

        adapter = TransactionAdapter(emptyList()) { transaction -> showActionDialog(transaction) }
        binding.rvTransactions.layoutManager = LinearLayoutManager(this)
        binding.rvTransactions.adapter = adapter

        if (!CurrencyHelper.isCurrencySet(this)) {
            showCurrencySelector(isFirstLaunch = true)
        } else {
            loadData()
            checkMonthlyCheckpoint()
        }

        binding.btnSend.setOnClickListener { handleInput() }
        binding.btnMenu.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        binding.cardBalance.setOnClickListener { showSummarySheet() }
        binding.btnCancelEdit.setOnClickListener { resetInput() } // Clicking X cancels edit

        binding.navView.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            when (menuItem.itemId) {
                R.id.nav_export -> showExportDialog()
                R.id.nav_import -> importLauncher.launch("text/*")
                R.id.nav_currency -> showCurrencySelector(isFirstLaunch = false)
                R.id.nav_privacy -> startActivity(android.content.Intent(this, PrivacyActivity::class.java))
                R.id.nav_about -> startActivity(android.content.Intent(this, AboutActivity::class.java))
                R.id.nav_link_device -> {
                    startActivity(Intent(this, LinkDeviceActivity::class.java))
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
            }
            true
        }

        setupSearch()
        setupInputPreview()
        setupSignToggles()
        setupKeyboardSwitching()
    }

    override fun onResume() {
        super.onResume()
        // 1. Fix: Auto-reset Edit Mode if needed
        if (editingTransaction != null) {
            resetInput()
        }

        // 2. New: Check for cloud updates immediately
        runSync()
    }


    private fun setupSignToggles() {
        binding.btnPlus.setOnClickListener { insertSign("+") }
        binding.btnMinus.setOnClickListener { insertSign("-") }

        // Ensure visibility updates on focus
        binding.etInput.setOnFocusChangeListener { _, _ -> updateSignToggleVisibility() }

        // Initial check
        updateSignToggleVisibility()
    }

    private fun insertSign(sign: String) {
        // 1. Force focus and show keyboard
        binding.etInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.etInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)

        // 2. Insert the sign at cursor position
        val start = binding.etInput.selectionStart.coerceAtLeast(0)
        binding.etInput.text.insert(start, sign)
        // Note: TextWatcher will trigger updateSignToggleVisibility automatically
    }

    private fun updateSignToggleVisibility() {
        val text = binding.etInput.text.toString()

        // UPDATED LOGIC:
        // Only show buttons if the field is completely empty.
        // As soon as the user inserts a sign or types a letter, the buttons will fade out.
        val shouldShow = text.isEmpty()

        if (shouldShow) {
            if (binding.layoutSignToggles.visibility != View.VISIBLE) {
                binding.layoutSignToggles.alpha = 0f
                binding.layoutSignToggles.visibility = View.VISIBLE
                binding.layoutSignToggles.animate().alpha(1f).setDuration(150).start()
            }
        } else {
            if (binding.layoutSignToggles.visibility == View.VISIBLE) {
                binding.layoutSignToggles.animate().alpha(0f).setDuration(150).withEndAction {
                    binding.layoutSignToggles.visibility = View.INVISIBLE
                }.start()
            }
        }
    }

    private fun setupKeyboardSwitching() {
        binding.etInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s.toString()

                val isTransactionStart = text.startsWith("+") || text.startsWith("-")
                val hasSpace = text.contains(" ")

                // --- 1. KEYBOARD LOGIC ---
                val typeText = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE

                // DateTime allows numbers, signs (+ - /), and SPACE.
                val typeNumeric = android.text.InputType.TYPE_CLASS_DATETIME

                // If in Phase 1, force Numeric/Math keyboard. Otherwise Text.
                val targetType = if (isTransactionStart && !hasSpace) {
                    typeNumeric
                } else {
                    typeText
                }

                if (binding.etInput.inputType != targetType) {
                    val selStart = binding.etInput.selectionStart
                    val selEnd = binding.etInput.selectionEnd
                    binding.etInput.inputType = targetType
                    if (selStart >= 0 && selEnd >= 0) {
                        binding.etInput.setSelection(selStart, selEnd)
                    }
                }

                // --- 2. UX HINT LOGIC ---
                if (text.isEmpty()) {
                    binding.etInput.hint = "Try '-50 coffee'"
                } else if (isTransactionStart && !hasSpace) {
                    // Phase 1 Hint
                    binding.etInput.hint = "Amount (supports + - * /)"
                } else {
                    // Phase 2 Hint
                    binding.etInput.hint = "Description"
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    // --- EXISTING FEATURES & HELPERS ---

    private fun checkMonthlyCheckpoint() {
        val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
        val lastSeen = prefs.getString("last_month_checkpoint", "")

        val cal = Calendar.getInstance()
        val currentMonthKey = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(cal.time)

        if (lastSeen != currentMonthKey) {
            cal.add(Calendar.MONTH, -1)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            val start = cal.timeInMillis

            cal.add(Calendar.MONTH, 1)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val end = cal.timeInMillis

            lifecycleScope.launch(Dispatchers.IO) {
                val list = db.transactionDao().getAll()
                val prevMonthList = list.filter { it.timestamp in start until end }

                if (prevMonthList.isNotEmpty()) {
                    val sum = prevMonthList.sumOf { it.amount }
                    val prevMonthName = SimpleDateFormat("MMMM", Locale.getDefault()).format(java.util.Date(start))

                    withContext(Dispatchers.Main) {
                        val symbol = CurrencyHelper.getSymbol(this@MainActivity)
                        val fmtSum = if (sum % 1.0 == 0.0) sum.toInt().toString() else String.format("%.1f", sum)

                        binding.tvMonthlySummary.text = "Last month ($prevMonthName): $symbol $fmtSum"
                        binding.tvMonthlySummary.visibility = View.VISIBLE
                        prefs.edit().putString("last_month_checkpoint", currentMonthKey).apply()
                    }
                }
            }
        }
    }

    private fun updateAutocomplete(list: List<Transaction>) {
        val frequencyMap = HashMap<String, HashMap<String, Int>>()
        for (t in list) {
            val amountStr = if (t.amount % 1.0 == 0.0) t.amount.toInt().toString() else t.amount.toString()
            val map = frequencyMap.getOrDefault(amountStr, HashMap())
            val count = map.getOrDefault(t.description, 0)
            map[t.description] = count + 1
            frequencyMap[amountStr] = map
        }

        val suggestions = ArrayList<String>()
        for ((amount, descMap) in frequencyMap) {
            val topDesc = descMap.maxByOrNull { it.value }
            if (topDesc != null && topDesc.value >= 2) {
                suggestions.add("$amount ${topDesc.key}")
            }
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions)
        binding.etInput.setAdapter(adapter)
    }

    private fun evaluateMath(expression: String): Double? {
        try {
            if (!expression.matches(Regex("[-+*/.0-9]+"))) return null

            val tokens = ArrayList<String>()
            var buffer = StringBuilder()
            for (char in expression) {
                if (char in listOf('+', '-', '*', '/')) {
                    if (buffer.isNotEmpty()) tokens.add(buffer.toString())
                    tokens.add(char.toString())
                    buffer.clear()
                } else {
                    buffer.append(char)
                }
            }
            if (buffer.isNotEmpty()) tokens.add(buffer.toString())

            var i = 0
            while (i < tokens.size) {
                val token = tokens[i]
                if (token == "+" || token == "-") {
                    val isUnary = (i == 0) || (tokens[i - 1] in listOf("+", "-", "*", "/"))
                    if (isUnary && i + 1 < tokens.size) {
                        tokens[i] = token + tokens[i + 1]
                        tokens.removeAt(i + 1)
                    }
                }
                i++
            }

            i = 0
            while (i < tokens.size) {
                if (tokens[i] == "*" || tokens[i] == "/") {
                    if (i == 0 || i + 1 >= tokens.size) return null
                    val op = tokens[i]
                    val prev = tokens[i - 1].toDouble()
                    val next = tokens[i + 1].toDouble()
                    val res = if (op == "*") prev * next else prev / next
                    tokens[i - 1] = res.toString()
                    tokens.removeAt(i)
                    tokens.removeAt(i)
                    i--
                }
                i++
            }

            if (tokens.isEmpty()) return null
            var result = tokens[0].toDouble()
            i = 1
            while (i < tokens.size) {
                val op = tokens[i]
                if (i + 1 >= tokens.size) return null
                val next = tokens[i + 1].toDouble()
                if (op == "+") result += next
                if (op == "-") result -= next
                i += 2
            }
            return result

        } catch (e: Exception) {
            return null
        }
    }

    private fun setupInputPreview() {
        binding.etInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                // Trigger visibility update for buttons here
                updateSignToggleVisibility()

                val text = s.toString().trim()
                if (text.isEmpty()) {
                    binding.tvInputPreview.visibility = View.GONE
                    return
                }

                val parts = text.split(" ", limit = 2)
                val mathPart = parts[0]
                // Added .replaceFirstChar { it.uppercase() } to capitalize the description
                val descPart = if (parts.size > 1) parts[1].replaceFirstChar { it.uppercase() } else ""

                val mathResult = evaluateMath(mathPart)
                val amount = mathResult ?: mathPart.toDoubleOrNull() ?: 0.0

                if (amount != 0.0 || mathResult != null) {
                    val type = if (amount >= 0) "Credit" else "Debit"
                    val absAmount = kotlin.math.abs(amount)
                    val cleanDesc = if(descPart.isBlank()) "..." else descPart
                    val symbol = CurrencyHelper.getSymbol(this@MainActivity)
                    val fmtAmount = if (absAmount % 1.0 == 0.0) absAmount.toInt().toString() else String.format("%.1f", absAmount)

                    binding.tvInputPreview.text = "$type $symbol$fmtAmount · $cleanDesc"
                    binding.tvInputPreview.visibility = View.VISIBLE
                } else {
                    binding.tvInputPreview.visibility = View.GONE
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.etInput.setOnClickListener { updateSignToggleVisibility() }
    }

    private fun handleInput() {
        val rawText = binding.etInput.text.toString().trim()
        if (rawText.isEmpty()) return

        val split = rawText.split(" ", limit = 2)
        val mathPart = split[0]
        // Added .replaceFirstChar { it.uppercase() } here too
        val desc = if (split.size > 1) split[1].replaceFirstChar { it.uppercase() } else "General"

        val evaluatedAmount = evaluateMath(mathPart)

        if (evaluatedAmount != null || mathPart.toDoubleOrNull() != null) {
            val finalAmount = evaluatedAmount ?: mathPart.toDouble()
            val finalRawText = if (finalAmount % 1.0 == 0.0) "${finalAmount.toInt()} $desc" else "$finalAmount $desc"

            binding.btnSend.isEnabled = false
            if (editingTransaction == null) saveTransaction(finalRawText, finalAmount, desc)
            else updateTransaction(finalRawText, finalAmount, desc)
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                val list = db.transactionDao().getAll()
                val processor = QueryProcessor(list)
                val answer = processor.process(rawText)
                withContext(Dispatchers.Main) {
                    showResultDialog(rawText, answer)
                    binding.etInput.text.clear()
                }
            }
        }
    }

    private fun loadData() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = db.transactionDao().getAll()
            val total = list.sumOf { it.amount }

            withContext(Dispatchers.Main) {
                adapter.updateData(list)
                updateAutocomplete(list)

                val symbol = CurrencyHelper.getSymbol(this@MainActivity)

                if (list.isEmpty()) {
                    binding.tvEmptyState.text = "Type +500 salary or -40 bus to begin"
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.rvTransactions.visibility = View.GONE
                } else {
                    binding.tvEmptyState.visibility = View.GONE
                    binding.rvTransactions.visibility = View.VISIBLE
                }

                if (currentDisplayedBalance != total) {
                    val animator = ValueAnimator.ofFloat(currentDisplayedBalance.toFloat(), total.toFloat())
                    animator.duration = 500
                    animator.addUpdateListener { animation ->
                        val animatedValue = animation.animatedValue as Float
                        val formattedValue = String.format("%.1f", animatedValue)
                        binding.tvTotalBalance.text = "$symbol $formattedValue"
                    }
                    animator.start()
                    currentDisplayedBalance = total
                } else {
                    val formattedValue = String.format("%.1f", total)
                    binding.tvTotalBalance.text = "$symbol $formattedValue"
                }
                binding.tvTotalBalance.setTextColor(android.graphics.Color.WHITE)
            }
        }
    }

    private fun showCurrencySelector(isFirstLaunch: Boolean) {
        val currencies = CurrencyHelper.CURRENCIES
        MaterialAlertDialogBuilder(this)
            .setTitle("Select Currency")
            .setCancelable(!isFirstLaunch)
            .setItems(currencies) { _, which ->
                CurrencyHelper.setCurrency(this, currencies[which])
                loadData()
                if(isFirstLaunch) checkMonthlyCheckpoint()
            }
            .show()
    }

    private fun setupSearch() {
        binding.btnSearch.setOnClickListener {
            if (binding.etSearch.visibility == View.VISIBLE) {
                binding.etSearch.visibility = View.GONE
                binding.etSearch.text.clear()
                loadData()
            } else {
                binding.etSearch.visibility = View.VISIBLE
                binding.etSearch.requestFocus()
            }
        }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                if (query.isNotEmpty()) performSearch(query) else loadData()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun performSearch(query: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val results = db.transactionDao().search(query)
            withContext(Dispatchers.Main) {
                adapter.updateData(results)
                if (results.isEmpty()) {
                    binding.tvEmptyState.text = "No matches for '$query'"
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.rvTransactions.visibility = View.GONE
                } else {
                    binding.tvEmptyState.visibility = View.GONE
                    binding.rvTransactions.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun importData(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val reader = BufferedReader(InputStreamReader(inputStream))
                reader.readLine()
                var line = reader.readLine()
                var count = 0
                val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                while (line != null) {
                    val parts = line.split(",")
                    if (parts.size >= 4) {
                        val amount = parts[2].toDoubleOrNull() ?: 0.0
                        val desc = parts.subList(3, parts.size).joinToString(",").replace("\"", "")
                        val timestamp = try { dateFormat.parse("${parts[0]} ${parts[1]}")?.time } catch (e:Exception) { System.currentTimeMillis() } ?: System.currentTimeMillis()

// FIX: Check for duplicates (allowing a 60-second window since export loses seconds)
                        val duplicateCount = db.transactionDao().checkDuplicate(amount, desc, timestamp, timestamp + 59999)

                        if (duplicateCount == 0) {
                            db.transactionDao().insert(Transaction(originalText = "$amount $desc", amount = amount, description = desc, timestamp = timestamp))
                            count++
                        }
                    }
                    line = reader.readLine()
                }
                withContext(Dispatchers.Main) { loadData(); showError("Restored $count items") }
            } catch (e: Exception) { withContext(Dispatchers.Main) { showError("Error: ${e.message}") } }
        }
    }

    private fun showSummarySheet() {
        lifecycleScope.launch(Dispatchers.IO) {
            val list = db.transactionDao().getAll()
            fun sumRange(start: Long): Double = list.filter { it.timestamp >= start }.sumOf { it.amount }
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
            val todaySum = sumRange(cal.timeInMillis)
            cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
            val weekSum = sumRange(cal.timeInMillis)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val monthSum = sumRange(cal.timeInMillis)
            val totalSum = list.sumOf { it.amount }
            withContext(Dispatchers.Main) {
                fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
                val symbol = CurrencyHelper.getSymbol(this@MainActivity)
                val summary = "Today:      $symbol ${fmt(todaySum)}\nThis Week:  $symbol ${fmt(weekSum)}\nThis Month: $symbol ${fmt(monthSum)}\nAll Time:   $symbol ${fmt(totalSum)}"
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Performance Summary")
                    .setMessage(summary)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun showExportDialog() {
        val options = arrayOf("CSV (Excel)", "Text File (Readable)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Export Data")
            .setItems(options) { _, which -> if (which == 0) exportData(true) else exportData(false) }
            .show()
    }

    private fun exportData(isCsv: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val transactions = db.transactionDao().getAll()
            if (transactions.isEmpty()) { withContext(Dispatchers.Main) { showError("No data") }; return@launch }
            val sb = StringBuilder()
            val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val symbol = CurrencyHelper.getSymbol(applicationContext)
            fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
            if (isCsv) {
                sb.append("Date,Time,Amount,Description\n")
                for (t in transactions) {
                    val date = java.util.Date(t.timestamp)
                    sb.append("${dateFormat.format(date)},${timeFormat.format(date)},${fmt(t.amount)},\"${t.description.replace("\"", "\"\"")}\"\n")
                }
            } else {
                sb.append("JotPay REPORT\nCurrency: $symbol\n=================\n")
                for (t in transactions) {
                    val date = java.util.Date(t.timestamp)
                    sb.append("[${dateFormat.format(date)}] ${fmt(t.amount)} ${t.description}\n")
                }
            }
            try {
                val ext = if(isCsv) "csv" else "txt"
                val file = File(cacheDir, "JotPay_Backup.$ext")
                file.writeText(sb.toString())
                val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.provider", file)
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                intent.type = if(isCsv) "text/csv" else "text/plain"
                intent.putExtra(android.content.Intent.EXTRA_STREAM, uri)
                intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                withContext(Dispatchers.Main) { startActivity(android.content.Intent.createChooser(intent, "Share Export")) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { showError("Failed: ${e.message}") } }
        }
    }

    private fun showResultDialog(q: String, a: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(q)
            // This 'Html.fromHtml' part is what makes the small text work!
            .setMessage(android.text.Html.fromHtml(a, android.text.Html.FROM_HTML_MODE_LEGACY))
            .setPositiveButton("OK", null)
            .show()
    }
    private fun showActionDialog(transaction: Transaction) {
        val options = arrayOf("Edit", "Delete")
        MaterialAlertDialogBuilder(this)
            .setTitle("Choose Action")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startEditing(transaction)
                    1 -> confirmDelete(transaction)
                }
            }
            .show()
    }
    private fun startEditing(transaction: Transaction) {
        editingTransaction = transaction
        binding.etInput.setText(transaction.originalText)
        binding.etInput.setSelection(transaction.originalText.length)
        binding.btnSend.background.mutate().setTint(android.graphics.Color.parseColor("#FF9800"))

        // NEW LINE: Show the cancel button
        binding.btnCancelEdit.visibility = View.VISIBLE

        binding.etInput.requestFocus()
    }
    private fun updateTransaction(original: String, amount: Double, desc: String) {
        val current = editingTransaction ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val updated = current.copy(originalText = original, amount = amount, description = desc)
            db.transactionDao().update(updated)
            withContext(Dispatchers.Main) {
                resetInput()
                loadData()
                showError("Updated")
                runSync() // <--- Added Sync Trigger
            }
        }
    }
    private fun saveTransaction(original: String, amount: Double, desc: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val transaction = Transaction(originalText = original, amount = amount, description = desc, timestamp = System.currentTimeMillis())
            db.transactionDao().insert(transaction)
            withContext(Dispatchers.Main) {
                resetInput()
                loadData()
                binding.rvTransactions.scrollToPosition(0)
                runSync() // <--- Added Sync Trigger
            }
        }
    }
    private fun resetInput() {
        binding.etInput.text.clear()
        binding.btnSend.isEnabled = true
        binding.btnSend.background.mutate().setTintList(null)
        editingTransaction = null

        // NEW LINE: Hide the cancel button
        binding.btnCancelEdit.visibility = View.GONE
    }
    private fun confirmDelete(transaction: Transaction) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete?")
            .setMessage(transaction.originalText)
            .setPositiveButton("Delete") { _, _ -> deleteTransaction(transaction) }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun runSync() {
        syncManager.syncData { message ->
            // Only show message if it's an Error (like 101 busy) or if actual data was synced.
            // We stay silent if it just says "Up to date" to not annoy the user.
            if (message.contains("Error") || message.contains("Synced")) {
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                if (message.contains("Synced")) {
                    loadData() // Refresh list if new data came in
                }
            }
        }
    }
    private fun deleteTransaction(transaction: Transaction) {
        lifecycleScope.launch(Dispatchers.IO) {
            syncManager.pushDelete(transaction)
            db.transactionDao().delete(transaction)
            withContext(Dispatchers.Main) {
                loadData()
                showError("Deleted")
                runSync() // <--- Added Sync Trigger
            }
        }
    }
    private fun showError(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}