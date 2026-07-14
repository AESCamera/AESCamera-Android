package com.devil7softwares.aescamera.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Suppress("SpellCheckingInspection")
internal class EncryptionUtils {
    companion object {
        private const val PBKDF2_ITERATIONS = 65536
        private const val KEY_BITS = 256
        private const val KEY_CACHE_MAX_SIZE = 8

        // PBKDF2 at 65536 iterations costs ~50-200ms per derivation. Video playback
        // re-opens the file on seeks, so derived keys are cached per (password, salt).
        // Must be cleared when the app locks (see AESCameraApplication).
        private val keyCache =
            object : LinkedHashMap<String, SecretKeySpec>(KEY_CACHE_MAX_SIZE, 0.75f, true) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<String, SecretKeySpec>
                ): Boolean {
                    return size > KEY_CACHE_MAX_SIZE
                }
            }

        @Synchronized
        fun clearKeyCache() {
            keyCache.clear()
        }

        @Synchronized
        private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
            val cacheKey = salt.joinToString("") { "%02x".format(it) } + ":" + password
            keyCache[cacheKey]?.let { return it }

            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
            val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

            keyCache[cacheKey] = secretKey
            return secretKey
        }

        // ---------------------------------------------------------------------------------
        // AES/CBC — photos & thumbnails (.enc): [salt(16)][iv(16)][ciphertext]
        // ---------------------------------------------------------------------------------

        // Initialize the cipher with the given mode, password, salt, and IV
        fun initCipher(mode: Int, password: String, salt: ByteArray? = null, iv: ByteArray? = null): Cipher {
            val actualSalt = salt ?: ByteArray(16).apply { SecureRandom().nextBytes(this) }
            val actualIv = iv ?: ByteArray(16).apply { SecureRandom().nextBytes(this) }

            val secretKey = deriveKey(password, actualSalt)

            // Initialize the cipher
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(mode, secretKey, IvParameterSpec(actualIv))

            return cipher
        }

        fun encrypt(stream: ByteArrayOutputStream, password: String): ByteArray {
            return encrypt(stream.toByteArray(), password)
        }

        fun encrypt(byteArray: ByteArray, password: String): ByteArray {
            // Generate a random salt
            val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
            val cipher = initCipher(Cipher.ENCRYPT_MODE, password, salt)
            val encrypted = cipher.doFinal(byteArray)
            val iv = cipher.iv

            // Combine salt + iv + encrypted data
            return salt + iv + encrypted
        }

        fun decrypt(encryptedData: ByteArray, password: String): ByteArray {
            // Extract salt, iv, and encrypted content
            val salt = encryptedData.slice(0 until 16).toByteArray()
            val iv = encryptedData.slice(16 until 32).toByteArray()
            val encrypted = encryptedData.slice(32 until encryptedData.size).toByteArray()

            val cipher = initCipher(Cipher.DECRYPT_MODE, password, salt, iv)
            return cipher.doFinal(encrypted)
        }

        // ---------------------------------------------------------------------------------
        // AES/CTR — videos (.encv v2): [header(16)][salt(16)][iv(16)][ciphertext]
        //
        // CTR keystream block n = AES(key, iv + n), so any plaintext offset can be
        // decrypted independently: jump the counter by offset/16 blocks and discard
        // offset%16 bytes of keystream. This is what makes seekable playback and
        // sequential (pipe-fed) encryption possible with the same format.
        // ---------------------------------------------------------------------------------

        /**
         * Returns [counter] + [blocks], treating the 16-byte counter as a big-endian
         * 128-bit integer (the standard AES-CTR increment).
         */
        fun addToCounter(counter: ByteArray, blocks: Long): ByteArray {
            require(counter.size == 16) { "Counter must be 16 bytes" }
            val result = counter.copyOf()
            var carry = blocks
            for (i in 15 downTo 0) {
                if (carry == 0L) break
                val sum = (result[i].toLong() and 0xFF) + (carry and 0xFF)
                result[i] = (sum and 0xFF).toByte()
                carry = (carry ushr 8) + (sum ushr 8)
            }
            return result
        }

        /**
         * Creates an AES/CTR/NoPadding cipher positioned at [plaintextOffset] of the
         * stream that was encrypted with [salt]/[iv]. Works for both encryption and
         * decryption (CTR is symmetric), [mode] only affects the Cipher API contract.
         */
        fun ctrCipherAt(
            mode: Int,
            password: String,
            salt: ByteArray,
            iv: ByteArray,
            plaintextOffset: Long = 0
        ): Cipher {
            val secretKey = deriveKey(password, salt)
            val counter = addToCounter(iv, plaintextOffset / 16)

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(mode, secretKey, IvParameterSpec(counter))

            // Discard the already-consumed part of the first block's keystream.
            val skip = (plaintextOffset % 16).toInt()
            if (skip > 0) {
                cipher.update(ByteArray(skip))
            }

            return cipher
        }

        /**
         * Opens a write-only stream that encrypts everything written to it into
         * [outputFile] using the .encv v2 layout: the plaintext [header], then random
         * salt + iv, then AES/CTR ciphertext. Close the returned stream to finalize.
         *
         * Used as the sink of the video recording pipe drain — plaintext never
         * touches disk.
         */
        fun createEncryptedVideoOutputStream(
            outputFile: File,
            password: String,
            header: EncvHeader
        ): OutputStream {
            val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
            val iv = ByteArray(16).apply { SecureRandom().nextBytes(this) }

            val cipher = ctrCipherAt(Cipher.ENCRYPT_MODE, password, salt, iv)

            val fileStream = FileOutputStream(outputFile)
            try {
                fileStream.write(header.toBytes())
                fileStream.write(salt)
                fileStream.write(iv)
            } catch (e: Exception) {
                runCatching { fileStream.close() }
                throw e
            }

            return CipherOutputStream(fileStream, cipher)
        }
    }
}
