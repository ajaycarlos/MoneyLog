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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.room.Room
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.appcompat.app.AppCompatDelegate
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
    private val viewModel: TransactionViewModel by viewModels()
    private lateinit var adapter: TransactionAdapter
    private var editingTransaction: Transaction? = null
    private var pendingEditId: Long = 0L
    private var balanceAnimator: ValueAnimator? = null
    private var currentDisplayedBalance = 0.0

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { parseAndImportCsv(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState != null) {
            pendingEditId = savedInstanceState.getLong("editing_id", 0L)
        }

        setupRecyclerView()
        setupListeners()
        setupInputLogic()

        val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
        val isSetupDone = prefs.getBoolean("policy_accepted", false) && CurrencyHelper.isCurrencySet(this)

        observeViewModel()

        if (isSetupDone) {
            checkMonthlyCheckpoint()
            checkBackupReminder()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
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

    private fun closeSearchBar() {
        binding.etSearch.text.clear()
        binding.etSearch.visibility = View.GONE
        binding.btnCloseSearch.visibility = View.GONE
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        viewModel.refreshData()
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("policy_accepted", false)) {
            checkFirstLaunchFlow()
        } else {
            viewModel.refreshData()
            runSync()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        editingTransaction?.let {
            outState.putLong("editing_id", it.timestamp)
        }
    }

    private fun observeViewModel() {
        viewModel.transactions.observe(this) { list ->
            adapter.updateData(list)
            updateAutocomplete(list)

            if (pendingEditId != 0L) {
                val target = list.find { it.timestamp == pendingEditId }
                if (target != null) {
                    startEditing(target)
                    pendingEditId = 0L
                }
            }

            if (list.isEmpty()) {
                binding.tvEmptyState.text = "Type +500 salary or -40 bus to begin"
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.rvTransactions.visibility = View.GONE
            } else {
                binding.tvEmptyState.visibility = View.GONE
                binding.rvTransactions.visibility = View.VISIBLE
            }
        }

        viewModel.totalBalance.observe(this) { total ->
            val finalTotal = total ?: 0.0
            val symbol = CurrencyHelper.getSymbol(this)

            balanceAnimator?.cancel()
            balanceAnimator = null

            if (currentDisplayedBalance != finalTotal) {
                val animator = ValueAnimator.ofObject(DoubleEvaluator(), currentDisplayedBalance, finalTotal)
                animator.duration = 500
                animator.addUpdateListener { animation ->
                    val animatedValue = animation.animatedValue as Double
                    val formattedValue = if (animatedValue % 1.0 == 0.0) animatedValue.toLong().toString() else String.format("%.1f", animatedValue)
                    binding.tvTotalBalance.text = "$symbol $formattedValue"
                }
                animator.addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        val formattedValue = if (finalTotal % 1.0 == 0.0) finalTotal.toLong().toString() else String.format("%.1f", finalTotal)
                        binding.tvTotalBalance.text = "$symbol $formattedValue"
                    }
                })
                animator.start()
                balanceAnimator = animator
                currentDisplayedBalance = finalTotal
            } else {
                val formattedValue = if (finalTotal % 1.0 == 0.0) finalTotal.toLong().toString() else String.format("%.1f", finalTotal)
                binding.tvTotalBalance.text = "$symbol $formattedValue"
            }
            binding.tvTotalBalance.setTextColor(android.graphics.Color.WHITE)
        }
    }

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
        binding.swipeRefresh.setOnRefreshListener { runSync() }

        // 1. STANDARD CLICK -> Normal Transaction
        binding.btnSend.setOnClickListener {
            handleInput(nature = "NORMAL")
        }

        // 2. LONG CLICK -> Custom Tiny Popup (Bubble)
        binding.btnSend.setOnLongClickListener { anchor ->
            // A. Build the layout programmatically
            val container = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_message_card)
                setPadding(0, 12, 0, 12)
                elevation = 24f
            }

            // B. Create the Popup Window
            val popup = android.widget.PopupWindow(
                container,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            )
            popup.elevation = 24f

            // C. Helper to create the text rows
            fun createRow(text: String, colorRes: Int, nature: String) {
                val tv = android.widget.TextView(this).apply {
                    this.text = text
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(context, colorRes))
                    setPadding(48, 20, 48, 20)
                    setOnClickListener {
                        handleInput(nature)
                        popup.dismiss()
                    }
                }
                container.addView(tv)
            }

            // D. Add Options
            createRow("Asset", R.color.income_green, "ASSET")
            createRow("Liability", R.color.expense_red, "LIABILITY")

            // E. Calculate Position (Force it ABOVE the button)
            container.measure(
                android.view.View.MeasureSpec.UNSPECIFIED,
                android.view.View.MeasureSpec.UNSPECIFIED
            )
            val popupHeight = container.measuredHeight
            val popupWidth = container.measuredWidth
            val xOff = -(popupWidth - anchor.width) / 2
            val yOff = -(anchor.height + popupHeight + 16)

            popup.showAsDropDown(anchor, xOff, yOff)

            true
        }

        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.cardBalance.setOnClickListener {
            showSummarySheet()
        }

        binding.btnCancelEdit.setOnClickListener {
            resetInput()
        }

        // FIX: Open Assets & Liabilities Screen
        binding.btnAssetsLiabilities.setOnClickListener {
            startActivity(Intent(this, AssetsLiabilitiesActivity::class.java))
        }

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

    private fun handleInput(nature: String) {
        val rawText = binding.etInput.text.toString().trim()
        val parsed = parseTransactionInput(rawText)

        if (parsed != null) {
            val (amount, desc) = parsed

            val finalRawText = if (amount % 1.0 == 0.0) {
                "${amount.toLong()} $desc"
            } else {
                "$amount $desc"
            }

            binding.btnSend.isEnabled = false

            if (editingTransaction == null) {
                // ADD NEW
                viewModel.addTransaction(finalRawText, amount, desc, nature)
                binding.rvTransactions.scrollToPosition(0)
            } else {
                // UPDATE EXISTING
                val current = editingTransaction!!
                val updated = current.copy(
                    originalText = finalRawText,
                    amount = amount,
                    description = desc
                )
                // Note: Nature update on edit is not implemented in this simplified flow
                // to avoid complex reconciliation logic. Use Delete & Re-add if nature is wrong.
                viewModel.updateTransaction(updated)
                showError("Updated")
            }

            resetInput()
            runSync(force = true)

        } else {
            showError("Please enter an amount (e.g., '50 Coffee')")
        }
    }

    private fun parseTransactionInput(text: String): Pair<Double, String>? {
        if (text.isBlank()) return null

        val splitFirst = text.split(" ", limit = 2)
        val firstPart = splitFirst[0]
        val firstEval = evaluateMath(firstPart) ?: firstPart.toDoubleOrNull()

        if (firstEval != null) {
            val desc = if (splitFirst.size > 1) splitFirst[1].replaceFirstChar { it.uppercase() } else "General"
            return Pair(firstEval, desc)
        }

        val lastSpace = text.lastIndexOf(' ')
        if (lastSpace != -1) {
            val lastPart = text.substring(lastSpace + 1)
            val lastEval = evaluateMath(lastPart) ?: lastPart.toDoubleOrNull()

            if (lastEval != null) {
                val descPart = text.substring(0, lastSpace).trim()
                val desc = if (descPart.isNotEmpty()) descPart.replaceFirstChar { it.uppercase() } else "General"
                return Pair(lastEval, desc)
            }
        }
        return null
    }

    private fun runSync(force: Boolean = false) {
        val workId = viewModel.scheduleSync(force)
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(workId).observe(this) { workInfo ->
            if (workInfo != null) {
                when (workInfo.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        val msg = workInfo.outputData.getString("MSG") ?: "Sync Complete"
                        if (msg.contains("Synced") || msg.contains("Error")) Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        if (msg.contains("Synced")) viewModel.refreshData()
                        binding.swipeRefresh.isRefreshing = false
                    }
                    WorkInfo.State.FAILED -> {
                        val msg = workInfo.outputData.getString("MSG") ?: "Sync Failed"
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        binding.swipeRefresh.isRefreshing = false
                    }
                    WorkInfo.State.RUNNING -> binding.swipeRefresh.isRefreshing = true
                    else -> { }
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
                reader.readLine() // Header

                var line = reader.readLine()
                val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                val usedTimestamps = HashSet<Long>()

                while (line != null) {
                    val tokens = line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex())
                        .map { it.trim().removeSurrounding("\"").replace("\"\"", "\"") }

                    if (tokens.size >= 4) {
                        val dateStr = "${tokens[0]} ${tokens[1]}"
                        val amount = tokens[2].toDoubleOrNull() ?: 0.0
                        val desc = tokens[3]
                        var timestamp: Long = 0L

                        if (tokens.size >= 7) { // Support new format with 7 columns
                            if (tokens[6].toLongOrNull() != null) timestamp = tokens[6].toLong()
                        } else if (tokens.size >= 5 && tokens[4].toLongOrNull() != null) {
                            timestamp = tokens[4].toLong()
                        }

                        if (timestamp == 0L) {
                            timestamp = try {
                                dateFormat.parse(dateStr)?.time
                            } catch (e: Exception) {
                                System.currentTimeMillis()
                            } ?: System.currentTimeMillis()
                        }
                        while (usedTimestamps.contains(timestamp)) timestamp += 1
                        usedTimestamps.add(timestamp)

                        val nature = if (tokens.size >= 5) tokens[4] else "NORMAL"
                        val obligation = if (tokens.size >= 6) tokens[5].toDoubleOrNull() ?: 0.0 else 0.0

                        val fmtAmount = if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString()

                        importList.add(Transaction(
                            originalText = "$fmtAmount $desc",
                            amount = amount,
                            description = desc,
                            timestamp = timestamp,
                            nature = nature,
                            obligationAmount = obligation
                        ))
                    }
                    line = reader.readLine()
                }
                viewModel.importTransactionList(importList)
                withContext(Dispatchers.Main) { showError("Importing ${importList.size} items...") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showError("Import Failed: ${e.message}") }
            }
        }
    }

    private fun setupInputLogic() {
        setupSearch()
        setupInputPreview()
        setupSignToggles()
        setupKeyboardSwitching()
    }

    private fun setupSearch() {
        binding.btnSearch.setOnClickListener {
            if (binding.etSearch.visibility != View.VISIBLE) {
                binding.etSearch.visibility = View.VISIBLE
                binding.btnCloseSearch.visibility = View.VISIBLE
                binding.etSearch.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(binding.etSearch, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        binding.btnCloseSearch.setOnClickListener { closeSearchBar() }
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                if (query.isNotEmpty()) viewModel.search(query) else viewModel.refreshData()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun setupSignToggles() {
        binding.btnPlus.setOnClickListener { insertSign("+") }
        binding.btnMinus.setOnClickListener { insertSign("-") }
        binding.btnSpace.setOnClickListener { insertSign(" ") }
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
        val isNumericMode = (text.startsWith("+") || text.startsWith("-") || text.firstOrNull()?.isDigit() == true) && !text.contains(" ")
        val shouldShowBar = text.isEmpty() || isNumericMode

        if (shouldShowBar) {
            if (binding.layoutSignToggles.visibility != View.VISIBLE) {
                binding.layoutSignToggles.alpha = 0f
                binding.layoutSignToggles.visibility = View.VISIBLE
                binding.layoutSignToggles.animate().alpha(1f).setDuration(150).withEndAction(null).start()
            } else {
                binding.layoutSignToggles.alpha = 1f
            }

            if (text.isEmpty()) {
                binding.btnPlus.visibility = View.VISIBLE
                binding.btnMinus.visibility = View.VISIBLE
                binding.btnSpace.visibility = View.GONE
            } else {
                binding.btnPlus.visibility = View.VISIBLE
                binding.btnMinus.visibility = View.VISIBLE
                binding.btnSpace.visibility = View.VISIBLE
            }
        } else {
            if (binding.layoutSignToggles.visibility == View.VISIBLE && binding.layoutSignToggles.alpha == 1f) {
                binding.layoutSignToggles.animate().alpha(0f).setDuration(150).withEndAction {
                    binding.layoutSignToggles.visibility = View.GONE
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
                val typeNumeric = InputType.TYPE_CLASS_DATETIME
                val targetType = if (isTransactionStart && !hasSpace) typeNumeric else typeText

                if (binding.etInput.inputType != targetType) {
                    val selStart = binding.etInput.selectionStart
                    val selEnd = binding.etInput.selectionEnd
                    binding.etInput.inputType = targetType
                    if (selStart >= 0 && selEnd >= 0) binding.etInput.setSelection(selStart, selEnd)
                }

                if (text.isEmpty()) binding.etInput.hint = "Try '-50 coffee'"
                else if (isTransactionStart && !hasSpace) binding.etInput.hint = "Amount (supports + - * /)"
                else binding.etInput.hint = "Description"
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
            if (topDesc != null && topDesc.value >= 2) suggestions.add("$amount ${topDesc.key}")
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
                } else buffer.append(char)
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

                    // --- FIX APPLIED HERE: DIV BY ZERO CHECK ---
                    if (op == "/" && next == 0.0) return null
                    // -------------------------------------------

                    val res = if (op == "*") prev * next else prev / next
                    tokens[i - 1] = res.toString()
                    tokens.removeAt(i); tokens.removeAt(i)
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
        } catch (e: Exception) { return null }
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
                } else binding.tvInputPreview.visibility = View.GONE
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        binding.etInput.setOnClickListener { updateSignToggleVisibility() }
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
            .setPositiveButton("Delete") { _, _ -> deleteTransaction(transaction) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showSummarySheet() {
        val list = viewModel.transactions.value ?: emptyList()
        if (list.isEmpty()) return
        fun sumRange(start: Long): Double = list.filter { it.timestamp >= start }.sumOf { it.amount }
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0)
        val todaySum = sumRange(cal.timeInMillis)
        cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
        val weekSum = sumRange(cal.timeInMillis)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val monthSum = sumRange(cal.timeInMillis)
        val totalSum = list.sumOf { it.amount }
        fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()
        val symbol = CurrencyHelper.getSymbol(this)
        val summary = "Today:      $symbol ${fmt(todaySum)}\nThis Week:  $symbol ${fmt(weekSum)}\nThis Month: $symbol ${fmt(monthSum)}\nAll Time:   $symbol ${fmt(totalSum)}"
        MaterialAlertDialogBuilder(this).setTitle("Performance Summary").setMessage(summary).setPositiveButton("OK", null).show()
    }

    private fun showExportDialog() {
        MaterialAlertDialogBuilder(this).setTitle("Export Data").setItems(arrayOf("CSV (Excel)", "Text File")) { _, which -> exportData(which == 0) }.show()
    }

    private fun exportData(isCsv: Boolean) {
        val transactions = viewModel.transactions.value ?: emptyList()
        if (transactions.isEmpty()) { showError("No data to export"); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val sb = StringBuilder()
            val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            val symbol = CurrencyHelper.getSymbol(applicationContext)
            fun fmt(d: Double): String = if (d % 1.0 == 0.0) d.toLong().toString() else d.toString()

            if (isCsv) {
                // FIXED: Now includes Nature and Obligation
                sb.append("Date,Time,Amount,Description,Nature,Obligation,Timestamp\n")
                for (t in transactions) {
                    val date = Date(t.timestamp)
                    val safeDesc = t.description.replace("\"", "\"\"")
                    sb.append("${dateFormat.format(date)},${timeFormat.format(date)},${fmt(t.amount)},\"$safeDesc\",${t.nature},${fmt(t.obligationAmount)},${t.timestamp}\n")
                }
            } else {
                sb.append("JotPay REPORT\nCurrency: $symbol\n=================\n")
                for (t in transactions) {
                    val date = Date(t.timestamp)
                    val natureTag = if (t.nature != "NORMAL") " [${t.nature}]" else ""
                    sb.append("[${dateFormat.format(date)}] ${fmt(t.amount)} ${t.description}$natureTag\n")
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
                withContext(Dispatchers.Main) { startActivity(Intent.createChooser(intent, "Share Export")) }
                getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE).edit().putLong("last_backup_timestamp", System.currentTimeMillis()).apply()
            } catch (e: Exception) { withContext(Dispatchers.Main) { showError("Export Failed: ${e.message}") } }
        }
    }

    private fun checkFirstLaunchFlow() {
        val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("policy_accepted", false)) showPrivacyWelcomeDialog() else checkCurrencySetup()
    }

    private fun showPrivacyWelcomeDialog() {
        MaterialAlertDialogBuilder(this).setTitle("Welcome to JotPay").setMessage("Before you start tracking your finances, please accept our terms.\n\n• Your data is encrypted and stored locally.\n• Cloud Sync is optional and end-to-end encrypted.\n• We do not track you or sell your data.").setCancelable(false)
            .setPositiveButton("Accept & Continue") { _, _ ->
                getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE).edit().putBoolean("policy_accepted", true).apply()
                checkCurrencySetup()
            }
            .setNegativeButton("Read Full Policy") { _, _ -> startActivity(Intent(this, PrivacyActivity::class.java)) }.show()
    }

    private fun checkCurrencySetup() {
        if (!CurrencyHelper.isCurrencySet(this)) showCurrencySelector(isFirstLaunch = true) else { viewModel.refreshData(); checkMonthlyCheckpoint() }
    }

    private fun checkMonthlyCheckpoint() {
        val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
        val lastSeen = prefs.getString("last_month_checkpoint", "")
        val cal = Calendar.getInstance()
        val currentMonthKey = SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(cal.time)

        if (lastSeen != currentMonthKey) {
            cal.add(Calendar.MONTH, -1); cal.set(Calendar.DAY_OF_MONTH, 1); cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.MONTH, 1); cal.set(Calendar.DAY_OF_MONTH, 1)
            val end = cal.timeInMillis

            lifecycleScope.launch(Dispatchers.IO) {
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
                        prefs.edit().putString("last_month_checkpoint", currentMonthKey).apply()
                    }
                }
                tempDb.close()
            }
        }
    }

    private fun showCurrencySelector(isFirstLaunch: Boolean) {
        val currencies = CurrencyHelper.CURRENCIES
        MaterialAlertDialogBuilder(this).setTitle("Select Currency").setCancelable(!isFirstLaunch)
            .setItems(currencies) { _, which ->
                CurrencyHelper.setCurrency(this, currencies[which])
                viewModel.refreshData()
                if(isFirstLaunch) checkMonthlyCheckpoint()
            }.show()
    }

    class DoubleEvaluator : android.animation.TypeEvaluator<Double> {
        override fun evaluate(fraction: Float, startValue: Double, endValue: Double): Double {
            return startValue + (endValue - startValue) * fraction.toDouble()
        }
    }

    private fun checkBackupReminder() {
        val prefs = getSharedPreferences("moneylog_prefs", Context.MODE_PRIVATE)
        val lastReminded = prefs.getLong("last_backup_timestamp", 0L)
        val now = System.currentTimeMillis()
        if (now - lastReminded > 604800000L) {
            MaterialAlertDialogBuilder(this).setTitle("Backup Reminder").setMessage("It's been a while since your last backup.\n\nTo prevent data loss, we recommend exporting your data to Google Drive or keeping a CSV copy safe.")
                .setPositiveButton("Export Now") { _, _ -> showExportDialog() }
                .setNegativeButton("Remind Later") { _, _ -> prefs.edit().putLong("last_backup_timestamp", now).apply() }.show()
        }
    }
}