package com.example.moneylog

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.moneylog.databinding.ItemTransactionBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class TransactionAdapter(
    private var transactions: List<Transaction>,
    private val onDeleteClick: (Transaction) -> Unit
) : RecyclerView.Adapter<TransactionAdapter.TransactionViewHolder>() {

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

        // 1. Context Text (Line 1)
        holder.binding.tvMessage.text = item.description

        // 2. Amount Styling (Line 2)
        // Clean formatting: Remove trailing .0
        val amountStr = if (item.amount % 1.0 == 0.0) {
            item.amount.toLong().toString()
        } else {
            item.amount.toString()
        }

        val isIncome = item.amount >= 0
        // Visual sign for clarity
        val displayAmount = if (isIncome) "+ $amountStr" else amountStr.replace("-", "- ")

        holder.binding.tvAmount.text = displayAmount

        // 3. Color Logic (Muted Chat-Lite Colors)
        // Using context.getColorCompat style safely
        val colorRes = if (isIncome) R.color.income_muted else R.color.expense_muted
        holder.binding.tvAmount.setTextColor(context.resources.getColor(colorRes, null))

        // 4. Time
        holder.binding.tvTime.text = timeFormatter.format(Date(item.timestamp))

        // 5. Date Headers (Separators)
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