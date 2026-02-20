
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
    import androidx.core.view.ViewCompat
    import androidx.core.view.WindowInsetsCompat
    import androidx.lifecycle.lifecycleScope
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.launch
    import kotlinx.coroutines.withContext
    import com.google.android.material.dialog.MaterialAlertDialogBuilder

    class LinkDeviceActivity : AppCompatActivity() {

        private lateinit var binding: ActivityLinkDeviceBinding
        private lateinit var syncManager: SyncManager
        private var deviceListener: ValueEventListener? = null
        private var currentVaultId: String? = null

        private lateinit var db: AppDatabase

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            binding = ActivityLinkDeviceBinding.inflate(layoutInflater)
            setContentView(binding.root)

            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

                // Convert 24dp to actual pixels for your specific tablet screen
                val density = resources.displayMetrics.density
                val sidePaddingPx = (24 * density).toInt()

                // Apply: Original side padding, System top padding, and Original bottom padding
                v.setPadding(sidePaddingPx, systemBars.top, sidePaddingPx, sidePaddingPx)

                insets
            }

            db = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "moneylog-db").build()
            syncManager = SyncManager(this, db) //SyncManager

            binding.btnBack.setOnClickListener {
                finish() //close and go back
            }

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
                // SECURITY UPDATE: Confirmation Dialog
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Unlink Device?")
                    .setMessage("This will disconnect this phone from the sync vault.\n\nWARNING: If this is your only device, you will lose access to your encrypted cloud data permanently.")
                    .setPositiveButton("Unlink") { _, _ ->
                        unlinkThisDevice()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
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
                    // FIX 10: Lifecycle Guard
                    // If activity is destroyed (e.g., user rotated screen or left), don't touch UI
                    if (isDestroyed || isFinishing) return@addOnCompleteListener

                    // 2. Clear Local Data & Generate New ID
                    syncManager.unlinkDevice()
                    Toast.makeText(this, "Unlinked. You are now on a fresh account.", Toast.LENGTH_LONG).show()

                    // 3. Restart Activity Cleanly
                    // FIX 10: Use flags to clear the entire back stack and start fresh.
                    // This prevents memory leaks and ensures no "ghost" activities remain.
                    val intent = Intent(this, LinkDeviceActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    finish()
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



                        // 1. Validate the key by trying to encrypt a dummy word
                        try {
                            val test = EncryptionHelper.encrypt("test", scannedKey)
                            if (test.isEmpty()) throw Exception("Encryption failed")
                        } catch (e: Exception) {
                            Toast.makeText(this, "Error: Invalid or corrupted Key in QR Code", Toast.LENGTH_LONG).show()
                            return
                        }


                        lifecycleScope.launch(Dispatchers.IO) {
                            val existingTransactions = db.transactionDao().getAll()

                            withContext(Dispatchers.Main) {
                                if (existingTransactions.isEmpty()) {
                                    // Log is empty, link immediately
                                    applyNewVaultAndFinish(scannedVault, scannedKey)
                                } else {
                                    // Log is not empty, show Merge/Overwrite dialog
                                    MaterialAlertDialogBuilder(this@LinkDeviceActivity)
                                        .setTitle("Link Device")
                                        .setMessage("You already have logs on this device.\nDo you want to merge them with the new linked device or replace them entirely?")
                                        .setPositiveButton("Merge") { _, _ ->
                                            applyNewVaultAndFinish(scannedVault, scannedKey)
                                        }
                                        .setNeutralButton("Overwrite") { _, _ ->
                                            lifecycleScope.launch(Dispatchers.IO) {
                                                db.transactionDao().deleteAll() // Clear local DB first
                                                withContext(Dispatchers.Main) {
                                                    applyNewVaultAndFinish(scannedVault, scannedKey)
                                                }
                                            }
                                        }
                                        .setNegativeButton("Cancel", null)
                                        .show()
                                }
                            }
                        }

                    } catch (e: Exception) {
                        Toast.makeText(this, "Invalid QR Code", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                super.onActivityResult(requestCode, resultCode, data)
            }
        }

        private fun applyNewVaultAndFinish(scannedVault: String, scannedKey: String) {
            val prefs = getSharedPreferences("jotpay_sync", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("vault_id", scannedVault)
                .putString("secret_key", scannedKey)
                .apply()

            currentVaultId = scannedVault

            if (isOnline()) {
                Toast.makeText(this, "Device Linked Successfully", Toast.LENGTH_LONG).show()
                registerDeviceOnServer(scannedVault)

                syncManager.scheduleSync(forcePush = true)

            } else {
                Toast.makeText(this, "Linked (Offline). Sync pending.", Toast.LENGTH_LONG).show()
            }

            finish()
        }

    }
