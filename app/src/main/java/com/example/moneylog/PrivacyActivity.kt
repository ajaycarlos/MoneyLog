package com.example.moneylog

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.moneylog.databinding.ActivityPrivacyBinding

class PrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup ViewBinding for this screen
        val binding = ActivityPrivacyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Close button just finishes this activity (goes back)
        binding.btnClose.setOnClickListener {
            finish()
        }
    }
}