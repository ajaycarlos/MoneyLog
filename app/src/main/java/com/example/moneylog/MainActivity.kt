package com.example.moneylog

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.room.Room
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.moneylog.databinding.ActivityMainBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // MVVM: The ViewModel now handles DB and Sync logic
    private val viewModel: TransactionViewModel by viewModels()

    private lateinit var adapter: TransactionAdapter
    private var editingTransaction: Transaction? = null
    private var currentDisplayedBalance = 0.0

    // Launcher for Importing CSV
    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { parseAndImportCsv(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 1. Setup UI Components
        setupRecyclerView()
        setupListeners()
        setupInputLogic()

        // 2. Check if user has finished Setup (Privacy + Currency)
        val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
        val isSetupDone = prefs.getBoolean("policy_accepted", false) && CurrencyHelper.isCurrencySet(this)

        // 3. Start observing data from ViewModel
        observeViewModel()

        // 4. If setup is done, run the monthly check logic
        if (isSetupDone) {
            checkMonthlyCheckpoint()
        }

        // FIX: Handle Back Press to close Search
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.etSearch.visibility == View.VISIBLE) {
                    closeSearchBar()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // Helper to close search and hide keyboard
    private fun closeSearchBar() {
        binding.etSearch.text.clear()
        binding.etSearch.visibility = View.GONE
        binding.btnCloseSearch.visibility = View.GONE // Hide the X button

        // Hide Keyboard
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)

        viewModel.refreshData() // Reset list to show all items
    }

    override fun onResume() {
        super.onResume()
        // Reset edit mode if returning from background
        if (editingTransaction != null) {
            resetInput()
        }

        val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
        // If policy not accepted, show dialog. Otherwise, Sync.
        if (!prefs.getBoolean("policy_accepted", false)) {
            checkFirstLaunchFlow()
        } else {
            // Refresh local data and trigger background sync
            viewModel.refreshData()
            runSync()
        }
    }

    // --- MVVM OBSERVATION ---

    private fun observeViewModel() {
        // A. Watch for Transaction List changes
        viewModel.transactions.observe(this) { list ->
            adapter.updateData(list)
            updateAutocomplete(list)

            if (list.isEmpty()) {
                binding.tvEmptyState.text = "Type +500 salary or -40 bus to begin"
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.rvTransactions.visibility = View.GONE
            } else {
                binding.tvEmptyState.visibility = View.GONE
                binding.rvTransactions.visibility = View.VISIBLE
            }
        }

        // B. Watch for Balance changes
        viewModel.totalBalance.observe(this) { total ->
            val symbol = CurrencyHelper.getSymbol(this)

            // Animate the number change
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

    // --- UI SETUP ---

    private fun setupRecyclerView() {
        adapter = TransactionAdapter(emptyList()) { transaction ->
            showActionDialog(transaction)
        }
        binding.rvTransactions.layoutManager = LinearLayoutManager(this)
        binding.rvTransactions.adapter = adapter
    }

    private fun setupListeners() {
        binding.swipeRefresh.setProgressBackgroundColorSchemeColor(android.graphics.Color.TRANSPARENT)
        binding.swipeRefresh.setColorSchemeColors(android.graphics.Color.parseColor("#81C784"))
        binding.swipeRefresh.setOnRefreshListener {
            runSync()
        }

        // Send Button
        binding.btnSend.setOnClickListener {
            handleInput()
        }

        // Drawer Menu
        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        // Click Balance -> Show Summary
        binding.cardBalance.setOnClickListener {
            showSummarySheet()
        }

        // Cancel Edit
        binding.btnCancelEdit.setOnClickListener {
            resetInput()
        }

        // Navigation Items - FIX APPLIED HERE
        binding.navView.setNavigationItemSelectedListener { menuItem ->
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            when (menuItem.itemId) {
                R.id.nav_export -> showExportDialog()
                R.id.nav_import -> importLauncher.launch("text/*")
                R.id.nav_currency -> showCurrencySelector(isFirstLaunch = false)
                R.id.nav_privacy -> startActivity(Intent(this, PrivacyActivity::class.java))
                R.id.nav_about -> startActivity(Intent(this, AboutActivity::class.java))
                R.id.nav_link_device -> startActivity(Intent(this, LinkDeviceActivity::class.java))
                else -> return@setNavigationItemSelectedListener false
            }
            true
        }
    }

    // --- INPUT HANDLING ---

    private fun handleInput() {
        val rawText = binding.etInput.text.toString().trim()
        if (rawText.isEmpty()) return

        val split = rawText.split(" ", limit = 2)
        val mathPart = split[0]
        val desc = if (split.size > 1) split[1].replaceFirstChar { it.uppercase() } else "General"

        // Try to evaluate math (e.g., "50+20")
        val evaluatedAmount = evaluateMath(mathPart)

        // It is a transaction if it's a number OR a valid math expression
        if (evaluatedAmount != null || mathPart.toDoubleOrNull() != null) {
            val finalAmount = evaluatedAmount ?: mathPart.toDouble()

            // Format text for storage (e.g. "50 Lunch")
            // CHANGE: Use .toLong() here too
            val finalRawText = if (finalAmount % 1.0 == 0.0) {
                "${finalAmount.toLong()} $desc"
            } else {
                "$finalAmount $desc"
            }

            binding.btnSend.isEnabled = false

            if (editingTransaction == null) {
                // ADD NEW (Via ViewModel)
                viewModel.addTransaction(finalRawText, finalAmount, desc)
                binding.rvTransactions.scrollToPosition(0)
            } else {
                // UPDATE EXISTING (Via ViewModel)
                val current = editingTransaction!!
                val updated = current.copy(
                    originalText = finalRawText,
                    amount = finalAmount,
                    description = desc
                )
                viewModel.updateTransaction(updated)
                showError("Updated")
            }

            resetInput()
            runSync() // Trigger sync after adding/editing

        } else {
            // SEARCH MODE
            // 1. Filter the list visually
            viewModel.search(rawText)

            // 2. Also check for "Natural Language Query" (e.g. "How much spent on food?")
            processNaturalLanguageQuery(rawText)
        }
    }

    // --- SYNC LOGIC (WORKMANAGER) ---

    private fun runSync() {
        // 1. Ask ViewModel to schedule the job
        val workId = viewModel.scheduleSync()

        // 2. Observe the specific job status
        WorkManager.getInstance(this)
            .getWorkInfoByIdLiveData(workId)
            .observe(this) { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val msg = workInfo.outputData.getString("MSG") ?: "Sync Complete"

                            // Show toast only if something happened or it was an error
                            if (msg.contains("Synced") || msg.contains("Error")) {
                                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                            }

                            // If sync brought new data, refresh ViewModel
                            if (msg.contains("Synced")) {
                                viewModel.refreshData()
                            }
                            binding.swipeRefresh.isRefreshing = false
                        }
                        WorkInfo.State.FAILED -> {
                            val msg = workInfo.outputData.getString("MSG") ?: "Sync Failed"
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                            binding.swipeRefresh.isRefreshing = false
                        }
                        WorkInfo.State.RUNNING -> {
                            binding.swipeRefresh.isRefreshing = true
                        }
                        else -> {
                            // Enqueued or blocked
                        }
                    }
                }
            }
    }

    // --- LOGIC HELPERS ---

    private fun processNaturalLanguageQuery(rawText: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            // Grab current list from ViewModel
            val list = viewModel.transactions.value ?: emptyList()
            if (list.isNotEmpty()) {
                val processor = QueryProcessor(list)
                val answer = processor.process(rawText)

                // If the processor found a valid answer (starts with "Total"), show dialog
                if (answer.startsWith("Total") || answer.startsWith("Net")) {
                    withContext(Dispatchers.Main) {
                        showResultDialog(rawText, answer)
                        binding.etInput.text.clear()
                    }
                }
            }
        }
    }

    private fun deleteTransaction(transaction: Transaction) {
        viewModel.deleteTransaction(transaction)
        showError("Deleted")
        runSync()
    }

    private fun parseAndImportCsv(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val reader = BufferedReader(InputStreamReader(inputStream))
                val importList = ArrayList<Transaction>()

                // Headers: Date,Time,Amount,Description
                reader.readLine() // Skip Header

                var line = reader.readLine()
                val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

                while (line != null) {
                    val parts = line.split(",")
                    if (parts.size >= 4) {
                        val amount = parts[2].toDoubleOrNull() ?: 0.0
                        // Reassemble description if it contained commas
                        val desc = parts.subList(3, parts.size).joinToString(",").replace("\"", "")

                        val dateStr = "${parts[0]} ${parts[1]}"
                        val timestamp = try {
                            dateFormat.parse(dateStr)?.time
                        } catch (e: Exception) {
                            System.currentTimeMillis()
                        } ?: System.currentTimeMillis()

                        importList.add(Transaction(
                            originalText = "$amount $desc",
                            amount = amount,
                            description = desc,
                            timestamp = timestamp
                        ))
                    }
                    line = reader.readLine()
                }

                // Pass list to ViewModel to handle database insertion
                viewModel.importTransactionList(importList)

                withContext(Dispatchers.Main) {
                    showError("Importing ${importList.size} items...")
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Import Failed: ${e.message}")
                }
            }
        }
    }

    // --- UI HELPERS (Unchanged Logic) ---

    private fun setupInputLogic() {
        setupSearch()
        setupInputPreview()
        setupSignToggles()
        setupKeyboardSwitching()
    }

    private fun setupSearch() {
        // Search Icon Click: NOW ONLY OPENS
        binding.btnSearch.setOnClickListener {
            if (binding.etSearch.visibility != View.VISIBLE) {
                // Open Search
                binding.etSearch.visibility = View.VISIBLE
                binding.btnCloseSearch.visibility = View.VISIBLE // Show X button
                binding.etSearch.requestFocus()

                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        // "X" Button Click: CLOSES Search
        binding.btnCloseSearch.setOnClickListener {
            closeSearchBar()
        }

        // Text Watcher
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                if (query.isNotEmpty()) {
                    viewModel.search(query)
                } else {
                    viewModel.refreshData()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupSignToggles() {
        binding.btnPlus.setOnClickListener { insertSign("+") }
        binding.btnMinus.setOnClickListener { insertSign("-") }

        binding.etInput.setOnFocusChangeListener { _, _ -> updateSignToggleVisibility() }
        updateSignToggleVisibility()
    }

    private fun insertSign(sign: String) {
        binding.etInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etInput, InputMethodManager.SHOW_IMPLICIT)

        val start = binding.etInput.selectionStart.coerceAtLeast(0)
        binding.etInput.text.insert(start, sign)
    }

    private fun updateSignToggleVisibility() {
        val text = binding.etInput.text.toString()
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

                val typeText = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                val typeNumeric = InputType.TYPE_CLASS_DATETIME // Shows numbers + operators

                val targetType = if (isTransactionStart && !hasSpace) typeNumeric else typeText

                if (binding.etInput.inputType != targetType) {
                    val selStart = binding.etInput.selectionStart
                    val selEnd = binding.etInput.selectionEnd
                    binding.etInput.inputType = targetType
                    // Restore cursor
                    if (selStart >= 0 && selEnd >= 0) {
                        binding.etInput.setSelection(selStart, selEnd)
                    }
                }

                // Hints
                if (text.isEmpty()) {
                    binding.etInput.hint = "Try '-50 coffee'"
                } else if (isTransactionStart && !hasSpace) {
                    binding.etInput.hint = "Amount (supports + - * /)"
                } else {
                    binding.etInput.hint = "Description"
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun updateAutocomplete(list: List<Transaction>) {
        val frequencyMap = HashMap<String, HashMap<String, Int>>()

        for (t in list) {
            val amountStr = if (t.amount % 1.0 == 0.0) t.amount.toLong().toString() else t.amount.toString()
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

            // Custom parser for "50+20-10"
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

            // Handle Unary (e.g., "-50")
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

            // Multiplication / Division
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

            // Addition / Subtraction
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
                updateSignToggleVisibility()

                val text = s.toString().trim()
                if (text.isEmpty()) {
                    binding.tvInputPreview.visibility = View.GONE
                    return
                }

                val parts = text.split(" ", limit = 2)
                val mathPart = parts[0]
                val descPart = if (parts.size > 1) parts[1].replaceFirstChar { it.uppercase() } else ""

                val mathResult = evaluateMath(mathPart)
                val amount = mathResult ?: mathPart.toDoubleOrNull() ?: 0.0

                if (amount != 0.0 || mathResult != null) {
                    val type = if (amount >= 0) "Credit" else "Debit"
                    val absAmount = abs(amount)
                    val cleanDesc = if(descPart.isBlank()) "..." else descPart
                    val symbol = CurrencyHelper.getSymbol(this@MainActivity)

                    val fmtAmount = if (absAmount % 1.0 == 0.0) absAmount.toLong().toString() else String.format("%.1f", absAmount)

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

    // --- DIALOGS ---

    private fun showResultDialog(q: String, a: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(q)
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
        binding.btnCancelEdit.visibility = View.VISIBLE
        binding.etInput.requestFocus()

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.etInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun resetInput() {
        binding.etInput.text.clear()
        binding.btnSend.isEnabled = true
        binding.btnSend.background.mutate().setTintList(null)
        editingTransaction = null
        binding.btnCancelEdit.visibility = View.GONE

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
    }

    private fun confirmDelete(transaction: Transaction) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete?")
            .setMessage(transaction.originalText)
            .setPositiveButton("Delete") { _, _ ->
                deleteTransaction(transaction)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showSummarySheet() {
        // Use ViewModel's data instead of DB
        val list = viewModel.transactions.value ?: emptyList()
        if (list.isEmpty()) return

        fun sumRange(start: Long): Double = list.filter { it.timestamp >= start }.sumOf { it.amount }

        val cal = Calendar.getInstance()

        // Today
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        val todaySum = sumRange(cal.timeInMillis)

        // This Week
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        val weekSum = sumRange(cal.timeInMillis)

        // This Month
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val monthSum = sumRange(cal.timeInMillis)

        // All Time
        val totalSum = list.sumOf { it.amount }

        fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
        val symbol = CurrencyHelper.getSymbol(this)

        val summary = "Today:      $symbol ${fmt(todaySum)}\n" +
                "This Week:  $symbol ${fmt(weekSum)}\n" +
                "This Month: $symbol ${fmt(monthSum)}\n" +
                "All Time:   $symbol ${fmt(totalSum)}"

        MaterialAlertDialogBuilder(this)
            .setTitle("Performance Summary")
            .setMessage(summary)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showExportDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Export Data")
            .setItems(arrayOf("CSV (Excel)", "Text File")) { _, which ->
                exportData(which == 0)
            }
            .show()
    }

    private fun exportData(isCsv: Boolean) {
        val transactions = viewModel.transactions.value ?: emptyList()
        if (transactions.isEmpty()) {
            showError("No data to export")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val sb = StringBuilder()
            val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val symbol = CurrencyHelper.getSymbol(applicationContext)

            fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

            if (isCsv) {
                sb.append("Date,Time,Amount,Description\n")
                for (t in transactions) {
                    val date = Date(t.timestamp)
                    sb.append("${dateFormat.format(date)},${timeFormat.format(date)},${fmt(t.amount)},\"${t.description.replace("\"", "\"\"")}\"\n")
                }
            } else {
                sb.append("JotPay REPORT\n")
                sb.append("Currency: $symbol\n")
                sb.append("=================\n")
                for (t in transactions) {
                    val date = Date(t.timestamp)
                    sb.append("[${dateFormat.format(date)}] ${fmt(t.amount)} ${t.description}\n")
                }
            }

            try {
                val filename = if (isCsv) "JotPay_Backup.csv" else "JotPay_Backup.txt"
                val file = File(cacheDir, filename)
                file.writeText(sb.toString())

                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.provider", file)
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = if (isCsv) "text/csv" else "text/plain"
                intent.putExtra(Intent.EXTRA_STREAM, uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

                withContext(Dispatchers.Main) {
                    startActivity(Intent.createChooser(intent, "Share Export"))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Export Failed: ${e.message}")
                }
            }
        }
    }

    // --- FIRST LAUNCH / MONTHLY CHECK ---

    private fun checkFirstLaunchFlow() {
        val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
        val isPolicyAccepted = prefs.getBoolean("policy_accepted", false)

        if (!isPolicyAccepted) {
            showPrivacyWelcomeDialog()
        } else {
            checkCurrencySetup()
        }
    }

    private fun showPrivacyWelcomeDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Welcome to JotPay")
            .setMessage("Before you start tracking your finances, please accept our terms.\n\n" +
                    "• Your data is encrypted and stored locally.\n" +
                    "• Cloud Sync is optional and end-to-end encrypted.\n" +
                    "• We do not track you or sell your data.")
            .setCancelable(false)
            .setPositiveButton("Accept & Continue") { _, _ ->
                getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("policy_accepted", true)
                    .apply()
                checkCurrencySetup()
            }
            .setNegativeButton("Read Full Policy") { _, _ ->
                startActivity(Intent(this, PrivacyActivity::class.java))
            }
            .show()
    }

    private fun checkCurrencySetup() {
        if (!CurrencyHelper.isCurrencySet(this)) {
            showCurrencySelector(isFirstLaunch = true)
        } else {
            viewModel.refreshData()
            checkMonthlyCheckpoint()
        }
    }

    private fun checkMonthlyCheckpoint() {
        val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
        val lastSeen = prefs.getString("last_month_checkpoint", "")

        val cal = Calendar.getInstance()
        val currentMonthKey = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(cal.time)

        if (lastSeen != currentMonthKey) {
            // It's a new month! Show summary of previous month

            // Calculate previous month range
            cal.add(Calendar.MONTH, -1)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            val start = cal.timeInMillis

            cal.add(Calendar.MONTH, 1)
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val end = cal.timeInMillis

            // Run DB query in background
            lifecycleScope.launch(Dispatchers.IO) {
                // Creates a temporary DB instance just for this check to avoid complexity
                val tempDb = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "moneylog-db").build()
                val list = tempDb.transactionDao().getAll()
                val prevMonthList = list.filter { it.timestamp in start until end }

                if (prevMonthList.isNotEmpty()) {
                    val sum = prevMonthList.sumOf { it.amount }
                    val prevMonthName = SimpleDateFormat("MMMM", Locale.getDefault()).format(Date(start))

                    withContext(Dispatchers.Main) {
                        val symbol = CurrencyHelper.getSymbol(this@MainActivity)
                        val fmtSum = if (sum % 1.0 == 0.0) sum.toLong().toString() else String.format("%.1f", sum)

                        binding.tvMonthlySummary.text = "Last month ($prevMonthName): $symbol $fmtSum"
                        binding.tvMonthlySummary.visibility = View.VISIBLE

                        // Save checkpoint
                        prefs.edit().putString("last_month_checkpoint", currentMonthKey).apply()
                    }
                }
                tempDb.close()
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
                viewModel.refreshData()
                if(isFirstLaunch) checkMonthlyCheckpoint()
            }
            .show()
    }
}