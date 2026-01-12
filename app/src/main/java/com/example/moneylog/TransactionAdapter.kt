package com.example.moneylog

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Spannable
import android.text.SpannableString
import android.text.style.StyleSpan
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

    private val timeFormatter = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val dateFullFormatter = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())

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

        // --- 3) TRANSACTION AMOUNT FORMATTING ---
        // Logic: Remove trailing .0 if present
        val amountStr = if (item.amount % 1.0 == 0.0) {
            item.amount.toInt().toString()
        } else {
            item.amount.toString()
        }

        // Format: "500  salary"
        val fullText = "$amountStr  ${item.description}"
        val spannable = SpannableString(fullText)

        // Bold the amount
        val firstSpace = fullText.indexOf(' ')
        if (firstSpace != -1) {
            spannable.setSpan(StyleSpan(Typeface.BOLD), 0, firstSpace, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        holder.binding.tvMessage.text = spannable

        // Time
        holder.binding.tvTime.text = timeFormatter.format(Date(item.timestamp))

        // Colors (Existing Consistency)
        val bubbleColor = if (item.amount >= 0) "#C8E6C9" else "#FFCDD2"
        val drawable = GradientDrawable()
        drawable.setColor(Color.parseColor(bubbleColor))
        drawable.cornerRadius = 24f
        holder.binding.layoutBubble.background = drawable

        // Date Headers (Existing)
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