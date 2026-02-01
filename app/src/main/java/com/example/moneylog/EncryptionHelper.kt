package com.example.moneylog

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object EncryptionHelper {

    // AES-CBC is secure, provided we use a random IV (implemented below)
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"
    private const val IV_SIZE = 16

    // FIX 3: Robust Key Formatting
    // Handles multi-byte characters safely while maintaining backward compatibility for ASCII keys.
    private fun formatKey(key: String): SecretKeySpec {
        // Step 1: Mimic the old padding logic (Chars)
        // This ensures "123" becomes "123000..." (ASCII '0', not null byte)
        // maintaining access for existing users with short passwords.
        val paddedChars = key.padEnd(16, '0')

        // Step 2: Convert to bytes (UTF-8)
        // If 'key' contained special chars (e.g. "Möney"), this array might now be 17+ bytes.
        val rawBytes = paddedChars.toByteArray(Charsets.UTF_8)

        // Step 3: Enforce strict 16-byte length
        // We truncate the byte array to exactly 16 bytes to satisfy AES-128 requirements.
        // We do not need to pad here because Step 1 ensured we have at least 16 chars (>= 16 bytes).
        val finalKeyBytes = if (rawBytes.size == 16) {
            rawBytes
        } else {
            rawBytes.copyOf(16)
        }

        return SecretKeySpec(finalKeyBytes, "AES")
    }

    fun encrypt(text: String, secretKey: String): String {
        try {
            val keySpec = formatKey(secretKey)
            val cipher = Cipher.getInstance(ALGORITHM)

            // SECURITY UPGRADE: Generate a Random IV for every encryption
            val ivBytes = ByteArray(IV_SIZE)
            SecureRandom().nextBytes(ivBytes)
            val iv = IvParameterSpec(ivBytes)

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, iv)
            val encryptedBytes = cipher.doFinal(text.toByteArray())

            // Format: [IV (16 bytes)] + [Ciphertext]
            val combined = ByteArray(ivBytes.size + encryptedBytes.size)
            System.arraycopy(ivBytes, 0, combined, 0, ivBytes.size)
            System.arraycopy(encryptedBytes, 0, combined, ivBytes.size, encryptedBytes.size)

            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    fun decrypt(encryptedText: String, secretKey: String): String {
        try {
            val keySpec = formatKey(secretKey)
            val decoded = Base64.decode(encryptedText, Base64.NO_WRAP)

            // COMPATIBILITY LOGIC:
            // 1. Try to decrypt assuming the new format (Random IV at the front)
            if (decoded.size > IV_SIZE) {
                try {
                    val ivBytes = ByteArray(IV_SIZE)
                    val bodySize = decoded.size - IV_SIZE
                    val bodyBytes = ByteArray(bodySize)

                    System.arraycopy(decoded, 0, ivBytes, 0, IV_SIZE)
                    System.arraycopy(decoded, IV_SIZE, bodyBytes, 0, bodySize)

                    val cipher = Cipher.getInstance(ALGORITHM)
                    val iv = IvParameterSpec(ivBytes)
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, iv)
                    return String(cipher.doFinal(bodyBytes))
                } catch (e: Exception) {
                    // If padding fails, it might be old data (Zero IV). Fallback.
                    return decryptLegacy(decoded, keySpec)
                }
            } else {
                // Too short to have an IV, likely legacy data
                return decryptLegacy(decoded, keySpec)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }

    private fun decryptLegacy(decoded: ByteArray, keySpec: SecretKeySpec): String {
        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            // Old format used a static Zero IV
            val iv = IvParameterSpec(ByteArray(16))
            cipher.init(Cipher.DECRYPT_MODE, keySpec, iv)
            return String(cipher.doFinal(decoded))
        } catch (e: Exception) {
            return "" // Data is corrupt or key is wrong
        }
    }
}