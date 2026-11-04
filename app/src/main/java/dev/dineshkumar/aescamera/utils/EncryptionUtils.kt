package dev.dineshkumar.aescamera.utils

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Outcome of an .encv integrity check (see EncryptionUtils.verifyEncv). */
enum class EncvVerification {
    /** v3 trailer present and correct: the file is authentic and complete. */
    VERIFIED,

    /** No trailer to check: a v2 file, or a v3 recording that died before finalizing. */
    UNVERIFIED,

    /** Trailer expected but wrong: tampered/corrupt file — or the wrong password. */
    FAILED
}

@Suppress("SpellCheckingInspection")
internal class EncryptionUtils {
    companion object {
        private const val KEY_BITS = 256
        private const val KEY_CACHE_MAX_SIZE = 16

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
        const val MAX_ITERATIONS = 2_000_000

        // PBKDF2 costs ~1ms per 1k iterations on a phone. Video playback re-opens the
        // file on seeks and the viewer revisits files on prev/next, so derived keys are
        // cached per (algorithm, iterations, salt, password). Must be cleared when the
        // app locks (see AESCameraApplication). The cache is also how keys are
        // pre-derived: prewarmImageKey/prewarmVideoKey put the key for a salt generated
        // ahead of time into the cache, and the later encrypt with that salt is a hit.
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

        /** Legacy CBC photos: PBKDF2-HMAC-SHA1 at the fixed legacy cost. */
        @Synchronized
        private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec =
            deriveKeyCached(VideoKdf.LEGACY.algorithm, password, salt, VideoKdf.LEGACY.iterations)

        @Synchronized
        private fun deriveKeySha256(password: String, salt: ByteArray, iterations: Int): SecretKeySpec =
            deriveKeyCached("PBKDF2WithHmacSHA256", password, salt, iterations)

        /** Video keys: the parameters come from the file's header ([EncvHeader.kdf]). */
        @Synchronized
        private fun deriveVideoKey(password: String, salt: ByteArray, kdf: VideoKdf): SecretKeySpec =
            deriveKeyCached(kdf.algorithm, password, salt, kdf.iterations)

        /** A fresh random salt for a photo or video key. */
        fun newSalt(): ByteArray = ByteArray(IMAGE_SALT_BYTES).apply { SecureRandom().nextBytes(this) }

        /**
         * Derives the photo key for [salt] now so that a later [encryptImage] with the
         * same salt pays nothing. Call off the main thread while the camera is idle.
         */
        fun prewarmImageKey(password: String, salt: ByteArray, iterations: Int = IMAGE_ITERATIONS) {
            deriveKeySha256(password, salt, iterations)
        }

        /** Video counterpart of [prewarmImageKey]: the salt goes to [createEncryptedVideoOutputStream]. */
        fun prewarmVideoKey(password: String, salt: ByteArray, kdf: VideoKdf = VideoKdf.DEFAULT) {
            deriveVideoKey(password, salt, kdf)
        }

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
            iterations: Int = IMAGE_ITERATIONS,
            salt: ByteArray = newSalt()
        ): ByteArray {
            require(salt.size == IMAGE_SALT_BYTES) { "Salt must be $IMAGE_SALT_BYTES bytes" }
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
        // AES/CTR — videos (.encv v2+): [header(16)][salt(16)][iv(16)][ciphertext]
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
         * stream that was encrypted with [salt]/[iv] under the key derived with [kdf]
         * (from the file's header). Works for both encryption and decryption (CTR is
         * symmetric), [mode] only affects the Cipher API contract.
         */
        fun ctrCipherAt(
            mode: Int,
            password: String,
            salt: ByteArray,
            iv: ByteArray,
            kdf: VideoKdf,
            plaintextOffset: Long = 0
        ): Cipher {
            val secretKey = deriveVideoKey(password, salt, kdf)
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
         * [outputFile]: the plaintext [header], then random salt + iv, then AES/CTR
         * ciphertext. An HMAC accumulates over the ciphertext as it is written; close
         * the stream, then call [EncryptedVideoOutput.finish] to patch the final header
         * and append the v3 authentication trailer (skipping finish leaves a valid,
         * unverified file — the crash case).
         *
         * Used as the sink of the video recording pipe drain — plaintext never
         * touches disk. [salt] may be one whose key was pre-derived with
         * [prewarmVideoKey], so that recording starts without paying the derivation.
         */
        fun createEncryptedVideoOutputStream(
            outputFile: File,
            password: String,
            header: EncvHeader,
            salt: ByteArray = newSalt()
        ): EncryptedVideoOutput {
            require(salt.size == EncvHeader.SALT_SIZE) { "Salt must be ${EncvHeader.SALT_SIZE} bytes" }
            val iv = ByteArray(EncvHeader.IV_SIZE).apply { SecureRandom().nextBytes(this) }

            val cipher = ctrCipherAt(Cipher.ENCRYPT_MODE, password, salt, iv, header.kdf)
            val mac = videoMac(password, salt, header.kdf)

            val fileStream = FileOutputStream(outputFile)
            try {
                fileStream.write(header.toBytes())
                fileStream.write(salt)
                fileStream.write(iv)
            } catch (e: Exception) {
                runCatching { fileStream.close() }
                throw e
            }

            // Order matters: the MAC tee sits below the cipher so it sees ciphertext
            // (encrypt-then-MAC); the prefix above was written before the tee and is
            // fed into the MAC at finish() instead, once its final contents are known.
            val cipherStream = CipherOutputStream(MacOutputStream(fileStream, mac), cipher)
            return EncryptedVideoOutput(outputFile, header, salt, iv, mac, cipherStream)
        }

        /**
         * Integrity-checks an .encv file against its v3 trailer: HMAC-SHA256 over
         * ciphertext ‖ header ‖ salt ‖ iv, compared in constant time. Reads the whole
         * file sequentially (~a second per few hundred MB) — call off the main thread.
         */
        fun verifyEncv(file: File, password: String): EncvVerification {
            try {
                RandomAccessFile(file, "r").use { raf ->
                    val headerBytes = ByteArray(EncvHeader.SIZE)
                    val salt = ByteArray(EncvHeader.SALT_SIZE)
                    val iv = ByteArray(EncvHeader.IV_SIZE)
                    raf.readFully(headerBytes)
                    raf.readFully(salt)
                    raf.readFully(iv)
                    val header = EncvHeader.fromBytes(headerBytes)

                    if (!header.isAuthenticated) {
                        return EncvVerification.UNVERIFIED
                    }

                    val trailerStart = raf.length() - EncvHeader.TRAILER_SIZE
                    if (trailerStart < EncvHeader.PREFIX_SIZE) {
                        return EncvVerification.FAILED
                    }

                    val mac = videoMac(password, salt, header.kdf)
                    val buffer = ByteArray(256 * 1024)
                    var remaining = trailerStart - EncvHeader.PREFIX_SIZE
                    raf.seek(EncvHeader.PREFIX_SIZE.toLong())
                    while (remaining > 0) {
                        val read = raf.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                        if (read < 0) return EncvVerification.FAILED
                        mac.update(buffer, 0, read)
                        remaining -= read
                    }
                    mac.update(headerBytes)
                    mac.update(salt)
                    mac.update(iv)
                    val expected = mac.doFinal()

                    val trailer = ByteArray(EncvHeader.TRAILER_SIZE)
                    raf.seek(trailerStart)
                    raf.readFully(trailer)

                    val magicOk = trailer.sliceArray(0 until EncvHeader.TRAILER_MAGIC.size)
                        .contentEquals(EncvHeader.TRAILER_MAGIC)
                    val macOk = MessageDigest.isEqual(
                        expected,
                        trailer.sliceArray(EncvHeader.TRAILER_MAGIC.size until EncvHeader.TRAILER_SIZE)
                    )
                    return if (magicOk && macOk) EncvVerification.VERIFIED
                    else EncvVerification.FAILED
                }
            } catch (e: Exception) {
                // Unreadable / truncated below the prefix / not an .encv at all.
                return EncvVerification.FAILED
            }
        }

        /**
         * The trailer's HMAC key, derived from the file's CTR key with a fixed info
         * string (one HMAC block — effectively HKDF-expand) so the encryption and
         * authentication keys stay domain-separated.
         */
        private fun videoMac(password: String, salt: ByteArray, kdf: VideoKdf): Mac {
            val fileKey = deriveVideoKey(password, salt, kdf)
            val kdf = Mac.getInstance("HmacSHA256")
            kdf.init(SecretKeySpec(fileKey.encoded, "HmacSHA256"))
            val macKey = kdf.doFinal(VIDEO_MAC_INFO)
            return Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(macKey, "HmacSHA256"))
            }
        }

        private val VIDEO_MAC_INFO = "AESCamera encv v3 mac".toByteArray(Charsets.US_ASCII)
    }
}
