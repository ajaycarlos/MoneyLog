package com.example.moneylog

import android.os.Bundle
import android.widget.Button
import android.widget.TextView  // Import added
import androidx.appcompat.app.AppCompatActivity

class AboutActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // --- NEW CODE START ---
        // 1. Find the text label we named 'tvVersion' in the XML
        val tvVersion = findViewById<TextView>(R.id.tvVersion)

        // 2. Get the real version name from build.gradle
        val versionName = BuildConfig.VERSION_NAME

        // 3. Set the text (e.g., "v1.1")
        tvVersion.text = "v$versionName"
        // --- NEW CODE END ---

        // Close button logic (kept from your original code)
        findViewById<Button>(R.id.btnClose).setOnClickListener {
            finish()
        }
    }
}