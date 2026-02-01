package com.example.moneylog

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.moneylog.databinding.ActivityAssetsLiabilitiesBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs

class AssetsLiabilitiesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAssetsLiabilitiesBinding
    private val viewModel: TransactionViewModel by viewModels()
    private lateinit var adapter: TransactionAdapter
    private var isAssetsTab = true // Track current tab

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAssetsLiabilitiesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupObservers()

        // Trigger the specific data load
        viewModel.loadAssetsAndLiabilities()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }

        binding.rvList.layoutManager = LinearLayoutManager(this)

        adapter = TransactionAdapter(emptyList()) { transaction ->
            showActionDialog(transaction)
        }
        // FIX: Disable signs for this screen (UX Polish)
        // This ensures liabilities show as "500" (Red) instead of "- 500" (Red)
        adapter.showSigns = false

        binding.rvList.adapter = adapter

        // Tab Switching Logic
        binding.btnTabAssets.setOnClickListener { switchTab(true) }
        binding.btnTabLiabilities.setOnClickListener { switchTab(false) }
    }

    private fun switchTab(showAssets: Boolean) {
        isAssetsTab = showAssets

        // Update Button Styles with correct active colors
        if (showAssets) {
            // Assets Active (Green), Liabilities Inactive
            styleButton(binding.btnTabAssets, true, R.color.income_muted)
            styleButton(binding.btnTabLiabilities, false, R.color.expense_muted)
        } else {
            // Assets Inactive, Liabilities Active (Red)
            styleButton(binding.btnTabAssets, false, R.color.income_muted)
            styleButton(binding.btnTabLiabilities, true, R.color.expense_muted)
        }

        // Refresh Display
        updateDisplay()
    }

    private fun styleButton(btn: MaterialButton, isActive: Boolean, activeColorRes: Int) {
        if (isActive) {
            btn.backgroundTintList = ContextCompat.getColorStateList(this, activeColorRes)
            btn.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            btn.strokeWidth = 0
        } else {
            // Inactive State: Transparent with Outline
            btn.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            btn.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            btn.strokeColor = ContextCompat.getColorStateList(this, R.color.text_tertiary)
            btn.strokeWidth = 2 // 2px border
        }
    }

    private fun setupObservers() {
        // We observe all data but only render what matches the current tab
        viewModel.assets.observe(this) { updateDisplay() }
        viewModel.liabilities.observe(this) { updateDisplay() }
        viewModel.totalAssets.observe(this) { updateDisplay() }
        viewModel.totalLiabilities.observe(this) { updateDisplay() }

        // Observe main transactions to auto-refresh this screen after a settlement/delete
        viewModel.transactions.observe(this) {
            viewModel.loadAssetsAndLiabilities()
        }
    }

    private fun updateDisplay() {
        val symbol = CurrencyHelper.getSymbol(this)

        if (isAssetsTab) {
            val list = viewModel.assets.value ?: emptyList()
            val total = viewModel.totalAssets.value ?: 0.0

            // Render Assets
            binding.tvTotalLabel.text = "TOTAL TO RECEIVE"
            binding.tvTotalValue.text = "$symbol ${fmt(total)}"
            binding.tvTotalValue.setTextColor(ContextCompat.getColor(this, R.color.income_green))

            // Map obligationAmount to amount for the adapter to display correctly
            val displayList = list.map { it.copy(amount = it.obligationAmount) }
            adapter.updateData(displayList)

            binding.tvEmptyHint.visibility = if(list.isEmpty()) View.VISIBLE else View.GONE

        } else {
            val list = viewModel.liabilities.value ?: emptyList()
            val total = viewModel.totalLiabilities.value ?: 0.0

            // Render Liabilities
            binding.tvTotalLabel.text = "TOTAL TO PAY"
            binding.tvTotalValue.text = "$symbol ${fmt(abs(total))}"
            binding.tvTotalValue.setTextColor(ContextCompat.getColor(this, R.color.expense_red))

            // Pass negative obligationAmount so adapter colors it Red,
            // but adapter.showSigns=false will hide the "-" text.
            val displayList = list.map { it.copy(amount = it.obligationAmount) }
            adapter.updateData(displayList)

            binding.tvEmptyHint.visibility = if(list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showActionDialog(transaction: Transaction) {
        val options = arrayOf("Settle (Mark Paid)", "Delete (Correction)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Choose Action")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSettleDialog(transaction)
                    1 -> confirmDelete(transaction)
                }
            }
            .show()
    }

    private fun showSettleDialog(transaction: Transaction) {
        val settleAmount = transaction.obligationAmount
        val symbol = CurrencyHelper.getSymbol(this)

        val displayAmount = "$symbol ${fmt(abs(settleAmount))}"

        MaterialAlertDialogBuilder(this)
            .setTitle("Settle Transaction?")
            .setMessage("Create a settlement record for $displayAmount?\n\nThis will zero out the balance while keeping the history.")
            .setPositiveButton("Settle") { _, _ ->
                val desc = "Settlement: ${transaction.description}"
                val text = "${fmt(settleAmount)} $desc"
                viewModel.addTransaction(text, settleAmount, desc, transaction.nature)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(transaction: Transaction) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Permanently?")
            .setMessage("Remove '${transaction.description}' from records entirely?\n\nUse this only for mistakes.")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteTransaction(transaction)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fmt(d: Double): String {
        return if (d % 1.0 == 0.0) d.toLong().toString() else String.format("%.2f", d)
    }
}