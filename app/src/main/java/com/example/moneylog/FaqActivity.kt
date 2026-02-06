package com.example.moneylog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class FaqActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_faq)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val rv = findViewById<RecyclerView>(R.id.rvFaq)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = FaqAdapter(getFaqs())
    }

    private fun getFaqs() = listOf(
        Faq("How do I add a simple transaction?", "Just type the amount and description in the bottom bar (e.g., '-50 Coffee' or '+20000 Salary') and tap the Send button."),
        Faq("How do I mark something as an Asset or Liability?", "To access these options, long-press the Send button after typing your transaction. A menu will appear allowing you to choose 'Asset' or 'Liability.'"),
        Faq("When should I choose 'Asset'?", "Choose Asset when you are lending money or when someone owes you. For example, if you give a friend ₹500, type '-500' and mark it as an Asset. The app will track that $500 is owed back to you."),
        Faq("When should I choose 'Liability'?", "Choose Liability when you are borrowing money or have a pending debt. For example, if you borrow $1000 from a friend, type '+1000' and mark it as a Liability. The app will track that you owe $1000."),
        Faq("How do I record someone paying me back?", "Go to the Assets & Liabilities screen (Arrows icon ⇄), find the record under the Assets tab, long-press it, and select 'Settle (Mark Paid)'."),
        Faq("How do I record paying back my own debt?", "Go to the Assets & Liabilities screen, find the debt under the Liabilities tab, long-press it, and select 'Settle (Mark Paid)'."),
        Faq("What does 'Settle' actually do?", "It automatically creates a 'counter-transaction' (e.g., a +500 entry to balance a -500 loan). This zeros out the debt in your ledger while keeping a full history of the transaction in your main log."),
        Faq("Where can I see my total debts and credits?", "Tap the Arrows Icon (⇄) at the top right of the home screen. This opens a dedicated page showing your total 'To Receive' (Assets) and 'To Pay' (Liabilities)."),
        Faq("How do I edit a mistake?", "Long-press any transaction bubble on the home screen. You can change the amount, the description, or even change its status between Normal, Asset, and Liability."),
        Faq("What is the difference between 'Delete' and 'Unmark'?", "Delete removes the record from the app entirely. Unmark removes it from your Assets/Liabilities ledger but keeps the transaction in your main history as a normal expense or income."),
        Faq("Can I use math in the input bar?", "Yes! You can type full expressions like '100/2+50 Lunch'. JotPay will calculate the result and save it as a single transaction."),
        Faq("Is my data private and synced?", "Your data is stored locally. If you enable Cloud Sync, your data is protected with End-to-End Encryption. Only you (using your secret key) can read your synced data.")
    )

    data class Faq(val q: String, val a: String)

    class FaqAdapter(private val list: List<Faq>) : RecyclerView.Adapter<FaqAdapter.Holder>() {
        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val q: TextView = v.findViewById(R.id.tvQuestion)
            val a: TextView = v.findViewById(R.id.tvAnswer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_faq, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = list[position]
            holder.q.text = item.q
            holder.a.text = item.a

            // Click to toggle expansion
            holder.itemView.setOnClickListener {
                if (holder.a.visibility == View.VISIBLE) {
                    holder.a.visibility = View.GONE
                } else {
                    holder.a.visibility = View.VISIBLE
                }
            }
        }

        override fun getItemCount() = list.size
    }
}