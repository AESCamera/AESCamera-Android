package dev.dineshkumar.aescamera.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Suppress("SpellCheckingInspection")
internal class EncryptionUtils {
    companion object {
        private const val PBKDF2_ITERATIONS = 65536
        private const val KEY_BITS = 256
        private const val KEY_CACHE_MAX_SIZE = 8

        // GCM authenticated encryption for photos/thumbnails and the vault verifier.
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128

        // Image container v1: [magic "AESP"][version:1][iterations:4 BE][salt:16][nonce:12][ct+tag].
        // Self-contained on purpose: the key is derived from password + header fields
        // only, so a shared file can be decrypted by any recipient who knows the
        // password (the share → import flow). A file with no magic is a legacy
        // password-derived AES/CBC photo (read via the legacy path).
        private val IMAGE_MAGIC = byteArrayOf(0x41, 0x45, 0x53, 0x50) // "AESP"
        private const val IMAGE_VERSION = 1
        private const val IMAGE_SALT_BYTES = 16

        // PBKDF2-SHA256 costs, stored per file so they stay tunable. Photos afford a
        // high cost (capture already encrypts on a background thread); thumbnails pay
        // one derivation each while scrolling the gallery grid, so theirs stays at the
        // legacy cost. Decrypt reads the count from the header, bounded by
        // [MAX_ITERATIONS] so a crafted header can't stall the app.
        const val IMAGE_ITERATIONS = 210_000
        const val THUMBNAIL_ITERATIONS = 65_000
        private const val MAX_ITERATIONS = 10_000_000

        // PBKDF2 costs ~1ms per 1k iterations on a phone. Video playback re-opens the
        // file on seeks and the viewer revisits files on prev/next, so derived keys are
        // cached per (algorithm, iterations, salt, password). Must be cleared when the
        // app locks (see AESCameraApplication).
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
        private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec =
            deriveKeyCached("PBKDF2WithHmacSHA1", password, salt, PBKDF2_ITERATIONS)

        @Synchronized
        private fun deriveKeySha256(password: String, salt: ByteArray, iterations: Int): SecretKeySpec =
            deriveKeyCached("PBKDF2WithHmacSHA256", password, salt, iterations)

        private fun deriveKeyCached(
            algorithm: String,
            password: String,
            salt: ByteArray,
            iterations: Int
        ): SecretKeySpec {
            val cacheKey = algorithm + ":" + iterations + ":" +
                    salt.joinToString("") { "%02x".format(it) } + ":" + password
            keyCache[cacheKey]?.let { return it }

            val factory = SecretKeyFactory.getInstance(algorithm)
            val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
            val secretKey = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

            keyCache[cacheKey] = secretKey
            return secretKey
        }

        // ---------------------------------------------------------------------------------
        // AES/GCM — photos, thumbnails & vault verifier (.enc v1, authenticated).
        // Reads legacy CBC too. Every file is self-contained: password + header fields
        // are all that's needed to decrypt, so shared files survive the import flow.
        // ---------------------------------------------------------------------------------

        /** AES/GCM encrypt with a random nonce. Returns [nonce(12)][ciphertext+tag]. */
        private fun gcmEncrypt(key: SecretKeySpec, plaintext: ByteArray): ByteArray {
            val nonce = ByteArray(GCM_NONCE_BYTES).apply { SecureRandom().nextBytes(this) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            return nonce + cipher.doFinal(plaintext)
        }

        /**
         * Reverses [gcmEncrypt]. Throws (typically `AEADBadTagException`) if the key is
         * wrong or the data was tampered with — this is the authentication check.
         */
        private fun gcmDecrypt(key: SecretKeySpec, blob: ByteArray, offset: Int): ByteArray {
            require(blob.size - offset >= GCM_NONCE_BYTES) { "GCM blob too short" }
            val nonce = blob.copyOfRange(offset, offset + GCM_NONCE_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce))
            val ctStart = offset + GCM_NONCE_BYTES
            return cipher.doFinal(blob, ctStart, blob.size - ctStart)
        }

        /** True if [data] is a v1 authenticated image (has the magic + version). */
        fun isAuthenticatedImage(data: ByteArray): Boolean {
            if (data.size < IMAGE_MAGIC.size + 1) return false
            for (i in IMAGE_MAGIC.indices) {
                if (data[i] != IMAGE_MAGIC[i]) return false
            }
            return data[IMAGE_MAGIC.size].toInt() == IMAGE_VERSION
        }

        /**
         * Encrypts [data] into the self-contained v1 authenticated format. [iterations]
         * is the PBKDF2 cost paid by every later decrypt of this file — [IMAGE_ITERATIONS]
         * for photos, [THUMBNAIL_ITERATIONS] for thumbnails.
         */
        fun encryptImage(
            data: ByteArray,
            password: String,
            iterations: Int = IMAGE_ITERATIONS
        ): ByteArray {
            val salt = ByteArray(IMAGE_SALT_BYTES).apply { SecureRandom().nextBytes(this) }
            val key = deriveKeySha256(password, salt, iterations)
            return IMAGE_MAGIC + byteArrayOf(IMAGE_VERSION.toByte()) + intToBe(iterations) +
                    salt + gcmEncrypt(key, data)
        }

        /**
         * Decrypts a photo/thumbnail/verifier: v1 authenticated files via the header's
         * own KDF parameters, older files via the legacy CBC path. Throws on wrong
         * password or tampering (v1) or padding failure (legacy).
         */
        fun decryptImage(data: ByteArray, password: String): ByteArray {
            if (!isAuthenticatedImage(data)) {
                return decrypt(data, password)
            }
            val itersStart = IMAGE_MAGIC.size + 1
            val saltStart = itersStart + 4
            val blobStart = saltStart + IMAGE_SALT_BYTES
            require(data.size >= blobStart) { "Truncated image header" }
            val iterations = readIntBe(data, itersStart)
            require(iterations in 1..MAX_ITERATIONS) { "Bad iteration count: $iterations" }
            val salt = data.copyOfRange(saltStart, blobStart)
            val key = deriveKeySha256(password, salt, iterations)
            return gcmDecrypt(key, data, blobStart)
        }

        private fun intToBe(value: Int): ByteArray = byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )

        private fun readIntBe(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)

        // ---------------------------------------------------------------------------------
        // AES/CBC — legacy photos & thumbnails (.enc): [salt(16)][iv(16)][ciphertext]
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
