package com.example.moneylog

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.room.Room
import com.example.moneylog.databinding.ActivityLinkDeviceBinding
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.integration.android.IntentIntegrator
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.json.JSONObject
import java.util.UUID

class LinkDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLinkDeviceBinding
    private lateinit var syncManager: SyncManager
    private var deviceListener: ValueEventListener? = null
    private var currentVaultId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLinkDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "moneylog-db").build()
        syncManager = SyncManager(this, db) // Init SyncManager

        val prefs = getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)
        currentVaultId = prefs.getString("vault_id", null)
        var secretKey = prefs.getString("secret_key", null)

        // Ensure we always have a vault (auto-generate if missing)
        if (currentVaultId == null) {
            currentVaultId = UUID.randomUUID().toString()
            secretKey = UUID.randomUUID().toString().substring(0, 16)
            prefs.edit()
                .putString("vault_id", currentVaultId)
                .putString("secret_key", secretKey)
                .apply()
        }

        // Show QR
        displayQrCode(currentVaultId!!, secretKey!!)

        // Register this device on the server so others can count it
        registerDeviceOnServer(currentVaultId!!)

        // Listen for device count
        listenForDeviceCount(currentVaultId!!)

        // Handle Scan Button
        binding.btnScan.setOnClickListener {
            val integrator = IntentIntegrator(this)
            integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            integrator.setPrompt("Scan QR code from the other device")
            integrator.setCameraId(0)
            integrator.setBeepEnabled(true)
            integrator.setOrientationLocked(true)
            integrator.initiateScan()
        }

        // Handle Unlink Button
        binding.btnUnlink.setOnClickListener {
            unlinkThisDevice()
        }
    }

    private fun registerDeviceOnServer(vaultId: String) {
        if (!isOnline()) return
        val installId = syncManager.getInstallationId()
        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

        // Write: vaults/{id}/devices/{installId} = "Pixel 7"
        FirebaseDatabase.getInstance().getReference("vaults")
            .child(vaultId)
            .child("devices")
            .child(installId)
            .setValue(deviceName)
    }

    private fun listenForDeviceCount(vaultId: String) {
        val ref = FirebaseDatabase.getInstance().getReference("vaults")
            .child(vaultId)
            .child("devices")

        deviceListener = ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val count = snapshot.childrenCount

                // Update UI based on count
                if (count > 1) {
                    binding.tvLinkedCount.text = "$count Devices Linked"
                    binding.btnUnlink.visibility = View.VISIBLE
                    binding.btnUnlink.text = "Unlink This Device"
                } else {
                    binding.tvLinkedCount.text = "1 Device (This Phone)"
                    // If we are the only one, 'Unlink' effectively just resets us.
                    // We can show it or hide it. Let's show it to allow "Reset".
                    binding.btnUnlink.visibility = View.VISIBLE
                    binding.btnUnlink.text = "Reset Sync ID"
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }

    private fun unlinkThisDevice() {
        if (!isOnline()) {
            Toast.makeText(this, "Connect to internet to unlink.", Toast.LENGTH_SHORT).show()
            return
        }

        val installId = syncManager.getInstallationId()
        val oldVaultId = currentVaultId ?: return

        // 1. Remove myself from Server List
        FirebaseDatabase.getInstance().getReference("vaults")
            .child(oldVaultId)
            .child("devices")
            .child(installId)
            .removeValue()
            .addOnCompleteListener {
                // 2. Clear Local Data & Generate New ID
                syncManager.unlinkDevice()
                Toast.makeText(this, "Unlinked. You are now on a fresh account.", Toast.LENGTH_LONG).show()

                // 3. Restart Activity to show new QR
                finish()
                startActivity(intent)
            }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop listening to avoid leaks
        currentVaultId?.let { vid ->
            FirebaseDatabase.getInstance().getReference("vaults")
                .child(vid).child("devices").removeEventListener(deviceListener ?: return)
        }
    }

    private fun displayQrCode(vaultId: String, key: String) {
        try {
            val json = JSONObject()
            json.put("v", vaultId)
            json.put("k", key)
            val writer = MultiFormatWriter()
            val matrix = writer.encode(json.toString(), BarcodeFormat.QR_CODE, 600, 600)
            val encoder = BarcodeEncoder()
            val bitmap = encoder.createBitmap(matrix)
            binding.ivQrCode.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error generating QR", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result != null) {
            if (result.contents != null) {
                try {
                    val json = JSONObject(result.contents)
                    val scannedVault = json.getString("v")
                    val scannedKey = json.getString("k")

                    val prefs = getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("vault_id", scannedVault)
                        .putString("secret_key", scannedKey)
                        .apply()

                    // Important: Reset the currentVaultId variable so registerDevice uses the NEW one
                    currentVaultId = scannedVault

                    if (isOnline()) {
                        Toast.makeText(this, "Device Linked Successfully", Toast.LENGTH_LONG).show()
                        // Register this device to the NEW vault
                        registerDeviceOnServer(scannedVault)
                    } else {
                        Toast.makeText(this, "Linked (Offline). Sync pending.", Toast.LENGTH_LONG).show()
                    }

                    finish()

                } catch (e: Exception) {
                    Toast.makeText(this, "Invalid QR Code", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}