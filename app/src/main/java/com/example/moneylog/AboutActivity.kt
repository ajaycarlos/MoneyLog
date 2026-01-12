package com.example.moneylog

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // Close button logic
        findViewById<Button>(R.id.btnClose).setOnClickListener {
            finish()
        }
    }
}