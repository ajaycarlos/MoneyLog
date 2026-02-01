package com.example.moneylog

import android.content.Context
import android.content.res.Configuration
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.moneylog.databinding.ItemTransactionBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class TransactionAdapter(
    private var transactions: List<Transaction>,
    private val onDeleteClick: (Transaction) -> Unit
) : RecyclerView.Adapter<TransactionAdapter.TransactionViewHolder>() {

    // Flag to toggle sign display (+/-) while keeping color logic
    var showSigns: Boolean = true

    private val timeFormatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFullFormatter = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())

    inner class TransactionViewHolder(val binding: ItemTransactionBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val binding = ItemTransactionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return TransactionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val item = transactions[position]
        val context = holder.itemView.context

        // 1. Context Text
        holder.binding.tvMessage.text = item.description

        // 2. Amount Styling
        val isIncome = item.amount >= 0
        val rawAmt = if (item.amount % 1.0 == 0.0) {
            abs(item.amount).toLong().toString()
        } else {
            abs(item.amount).toString()
        }

        // Sign Logic
        val displayAmount = if (showSigns) {
            if (isIncome) "+ $rawAmt" else "- $rawAmt"
        } else {
            rawAmt // Just the number (e.g. "500")
        }
        holder.binding.tvAmount.text = displayAmount

        // 3. Color Logic
        // FIX: Removed "Day Mode" check since we are strictly enforcing Dark Mode.
        // We now always use the Muted colors which exist in your colors.xml.
        val colorRes = if (isIncome) R.color.income_muted else R.color.expense_muted

        holder.binding.tvAmount.setTextColor(ContextCompat.getColor(context, colorRes))

        // 4. Time
        holder.binding.tvTime.text = timeFormatter.format(Date(item.timestamp))

        // 5. Date Headers
        val headerText = getDateHeader(item.timestamp)
        var showHeader = position == 0
        if (position > 0) {
            val prevHeader = getDateHeader(transactions[position - 1].timestamp)
            if (prevHeader != headerText) showHeader = true
        }

        if (showHeader) {
            holder.binding.tvDateHeader.visibility = View.VISIBLE
            holder.binding.tvDateHeader.text = headerText
        } else {
            holder.binding.tvDateHeader.visibility = View.GONE
        }

        // 6. Delete Action
        holder.binding.layoutBubble.setOnLongClickListener {
            onDeleteClick(item)
            true
        }
    }

    private fun getDateHeader(timestamp: Long): String {
        val date = Date(timestamp)
        val cal = Calendar.getInstance()
        cal.time = date
        val now = Calendar.getInstance()

        if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            if (cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) return "Today"
            now.add(Calendar.DAY_OF_YEAR, -1)
            if (cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) return "Yesterday"
        }
        return dateFullFormatter.format(date)
    }

    override fun getItemCount() = transactions.size

    fun updateData(newTransactions: List<Transaction>) {
        transactions = newTransactions
        notifyDataSetChanged()
    }
}