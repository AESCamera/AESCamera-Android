package dev.dineshkumar.aescamera.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * Locates, creates and (for a duress password) wipes vault directories.
 *
 * Vault directories have random names that carry no information about the key. Each
 * vault contains a hidden verifier file ([VERIFIER_FILE_NAME]) sealed with the key in the
 * authenticated image format (PBKDF2-SHA256 + AES-GCM, [VERIFIER_ITERATIONS]). The vault
 * for a key is found by test-opening verifiers, so guessing a key from the directory tree
 * costs a full PBKDF2 derivation per guess, the same as attacking an encrypted media file.
 *
 * Any key opens a vault — there is deliberately no "wrong password" signal. Unlock uses
 * [unlockVault], which never creates anything, and the app calls [createVault] only when
 * the first file is saved, so a mistyped password leaves no directory behind and does
 * not add a verifier check to every later unlock.
 *
 * Verifier v2 ([VerifierPayload]) is padded to one fixed size and carries the duress
 * settings, so a duress vault can't be told apart from any other vault without its
 * password. Older verifiers are rewritten as v2 when their vault is unlocked: the v1
 * magic, the pre-authentication AES/CBC verifier, and legacy MD5(key)-named directories
 * (GPU brute-forceable, so they are also renamed).
 */
object VaultUtils {
    private const val TAG = "VaultUtils"
    private const val VERIFIER_FILE_NAME = ".vault"
    private val VERIFIER_MAGIC_V1 = "AESCameraVault:v1".toByteArray(Charsets.UTF_8)

    // PBKDF2 cost of the verifier — the gate an attacker must pass per password guess.
    // Paid once per vault scanned at unlock, off the main thread (dialog_unlocking).
    private const val VERIFIER_ITERATIONS = 210_000

    // Leading bytes of each file overwritten before a wipe deletes it. Covers every
    // header: without its salt a file key can't be derived, even with the password.
    private const val SHRED_BYTES = 4096

    private val VAULT_NAME = Regex("[0-9a-f]{32}")

    /** What a duress password wipes when it is entered at unlock. */
    enum class WipeScope(val code: Int) {
        /** The vault that set the duress password up. */
        THIS_VAULT(1),

        /** Every vault except the duress vault itself. */
        ALL_OTHERS(2);

        companion object {
            fun fromCode(code: Int): WipeScope? = entries.firstOrNull { it.code == code }
        }
    }

    /** The duress password already opens a vault; using it would make that vault a target. */
    class DuressPasswordInUseException : Exception("The duress password already opens a vault")

    /**
     * The plaintext of a v2 verifier. Two roles can share one vault: a duress vault is
     * [armed] with what it wipes, and any vault can record the duress vault it owns.
     * Every v2 verifier encodes to [SIZE] bytes, whatever it holds.
     */
    data class VerifierPayload(
        val armed: Boolean = false,
        val wipeScope: WipeScope? = null,
        val wipeTarget: String? = null,
        val duressVault: String? = null,
        val duressScope: WipeScope? = null,
    ) {
        fun encode(): ByteArray {
            val out = ByteArray(SIZE)
            MAGIC.copyInto(out, 0)
            var flags = 0
            if (armed) flags = flags or FLAG_ARMED
            if (duressVault != null) flags = flags or FLAG_HAS_DURESS
            out[OFFSET_FLAGS] = flags.toByte()
            out[OFFSET_WIPE_SCOPE] = (if (armed) wipeScope?.code ?: 0 else 0).toByte()
            out[OFFSET_DURESS_SCOPE] = (if (duressVault != null) duressScope?.code ?: 0 else 0).toByte()
            if (armed && wipeScope == WipeScope.THIS_VAULT) writeName(out, OFFSET_WIPE_TARGET, wipeTarget)
            writeName(out, OFFSET_DURESS_VAULT, duressVault)
            return out
        }

        companion object {
            const val SIZE = 128
            val MAGIC = "AESCameraVault:v2".toByteArray(Charsets.UTF_8)
            private const val OFFSET_FLAGS = 17
            private const val OFFSET_WIPE_SCOPE = 18
            private const val OFFSET_DURESS_SCOPE = 19
            private const val OFFSET_WIPE_TARGET = 20
            private const val OFFSET_DURESS_VAULT = 52
            private const val NAME_LENGTH = 32
            private const val FLAG_ARMED = 0x01
            private const val FLAG_HAS_DURESS = 0x02

            /** Parses a v2 plaintext; null if it isn't one. Unknown flag bits are ignored. */
            fun decode(plain: ByteArray): VerifierPayload? {
                if (plain.size != SIZE || !plain.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                    return null
                }
                val flags = plain[OFFSET_FLAGS].toInt()
                val armed = flags and FLAG_ARMED != 0
                val hasDuress = flags and FLAG_HAS_DURESS != 0
                val wipeScope = if (armed) WipeScope.fromCode(plain[OFFSET_WIPE_SCOPE].toInt()) else null
                return VerifierPayload(
                    // An armed verifier with an unknown scope wipes nothing.
                    armed = armed && wipeScope != null,
                    wipeScope = wipeScope,
                    wipeTarget = if (wipeScope == WipeScope.THIS_VAULT) readName(plain, OFFSET_WIPE_TARGET) else null,
                    duressVault = if (hasDuress) readName(plain, OFFSET_DURESS_VAULT) else null,
                    duressScope = if (hasDuress) WipeScope.fromCode(plain[OFFSET_DURESS_SCOPE].toInt()) else null,
                )
            }

            private fun writeName(out: ByteArray, offset: Int, name: String?) {
                if (name == null) return
                require(VAULT_NAME.matches(name)) { "Not a vault name" }
                name.toByteArray(Charsets.US_ASCII).copyInto(out, offset)
            }

            private fun readName(plain: ByteArray, offset: Int): String? =
                String(plain, offset, NAME_LENGTH, Charsets.US_ASCII).takeIf { VAULT_NAME.matches(it) }
        }
    }

    /** A vault opened by a key, with the settings its verifier carries. */
    data class VaultMatch(val dir: File, val payload: VerifierPayload)

    /** The directory under which all vaults live (see [VaultStorage]). */
    fun baseDir(context: Context): File = VaultStorage.currentBaseDir(context)

    /** Returns the existing vault for [key], or null if this key has none yet. Never wipes. */
    fun findVault(key: String, context: Context): File? = findVault(key, baseDir(context))

    /**
     * Opens the vault for [key] at the unlock prompt. If [key] is a duress password, its
     * targets are wiped first and the duress (decoy) vault is returned.
     */
    fun unlockVault(key: String, context: Context): VaultMatch? = unlockVaultMatch(key, baseDir(context))

    /** Creates a new vault for [key]. Only call when [findVault] returned null. */
    fun createVault(key: String, context: Context): File = createVault(key, baseDir(context))

    /** Returns the vault directory for [key], creating it if none exists yet. */
    fun openVault(key: String, baseDir: File): File =
        findVault(key, baseDir) ?: createVault(key, baseDir)

    /** [findVault] core, separated from [Context] for unit testing. */
    fun findVault(key: String, baseDir: File): File? = findVaultMatch(key, baseDir)?.dir

    /** [unlockVault] core, separated from [Context] for unit testing. */
    fun unlockVault(key: String, baseDir: File): File? = unlockVaultMatch(key, baseDir)?.dir

    /** [unlockVault] with the opened vault's verifier settings. */
    fun unlockVaultMatch(key: String, baseDir: File): VaultMatch? {
        val match = findVaultMatch(key, baseDir) ?: return null
        if (match.payload.armed) {
            wipeTargets(match, baseDir)
        }
        return match
    }

    /**
     * The vault [key] opens and its verifier settings, or null. Never creates a vault,
     * but upgrades an older one it finds (verifier format, MD5 name).
     */
    fun findVaultMatch(key: String, baseDir: File): VaultMatch? {
        // An existing vault whose verifier this key opens.
        baseDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            val payload = readVerifier(dir, key) ?: return@forEach
            return VaultMatch(renameIfLegacy(dir, key), payload)
        }

        // A legacy vault named MD5(key) with no verifier at all: adopt it.
        val legacyDir = File(baseDir, CommonUtils.getKeyDigest(key))
        if (legacyDir.isDirectory) {
            Log.i(TAG, "Adopting legacy MD5-named vault")
            writeVerifier(legacyDir, key, VerifierPayload())
            return VaultMatch(renameIfLegacy(legacyDir, key), VerifierPayload())
        }

        return null
    }

    /** [createVault] core: a new vault under a random name, sealed with [key]. */
    fun createVault(key: String, baseDir: File): File = createVault(key, baseDir, VerifierPayload())

    private fun createVault(key: String, baseDir: File, payload: VerifierPayload): File {
        baseDir.mkdirs()
        val newDir = File(baseDir, randomVaultName()).apply { mkdirs() }
        writeVerifier(newDir, key, payload)
        return newDir
    }

    /** The verifier settings of [vault], or null if [key] doesn't open it. */
    fun readPayload(vault: File, key: String): VerifierPayload? = readVerifier(vault, key)

    /**
     * Sets up [duressKey] as the duress password of [ownerVault] (opened by [ownerKey]):
     * creates its decoy vault, armed to wipe [scope], and records it in the owner's
     * verifier. Any duress vault the owner already had is wiped first. Runs several
     * PBKDF2 derivations, so call off the main thread.
     *
     * @throws DuressPasswordInUseException if [duressKey] already opens a vault.
     */
    fun setDuress(ownerKey: String, ownerVault: File, duressKey: String, scope: WipeScope, baseDir: File): File {
        val owner = readVerifier(ownerVault, ownerKey) ?: error("Owner key doesn't open its vault")
        if (duressKey == ownerKey || findVaultMatch(duressKey, baseDir) != null) {
            throw DuressPasswordInUseException()
        }
        owner.duressVault?.let { previous -> File(baseDir, previous).takeIf { it.isDirectory }?.let(::wipeVault) }

        val duressVault = createVault(
            duressKey,
            baseDir,
            VerifierPayload(armed = true, wipeScope = scope, wipeTarget = ownerVault.name.takeIf { scope == WipeScope.THIS_VAULT }),
        )
        writeVerifier(ownerVault, ownerKey, owner.copy(duressVault = duressVault.name, duressScope = scope))
        return duressVault
    }

    /** Removes the duress password of [ownerVault], wiping its decoy vault. */
    fun removeDuress(ownerKey: String, ownerVault: File, baseDir: File) {
        val owner = readVerifier(ownerVault, ownerKey) ?: error("Owner key doesn't open its vault")
        owner.duressVault?.let { name -> File(baseDir, name).takeIf { it.isDirectory }?.let(::wipeVault) }
        writeVerifier(ownerVault, ownerKey, owner.copy(duressVault = null, duressScope = null))
    }

    /**
     * The decoy vault of [ownerVault] if [duressKey] is its duress password, for adding
     * decoy content from Settings. Never wipes anything.
     */
    fun findDuressVault(ownerKey: String, ownerVault: File, duressKey: String, baseDir: File): File? {
        val name = readVerifier(ownerVault, ownerKey)?.duressVault ?: return null
        val dir = File(baseDir, name)
        return dir.takeIf { readVerifier(it, duressKey)?.armed == true }
    }

    /**
     * Wipes what an armed duress vault targets. Each target's verifier goes first, so
     * even an interrupted wipe leaves the target unopenable; the next duress unlock
     * finishes the rest.
     */
    private fun wipeTargets(match: VaultMatch, baseDir: File) {
        val payload = match.payload
        val targets = when (payload.wipeScope) {
            WipeScope.THIS_VAULT -> listOfNotNull(payload.wipeTarget?.let { File(baseDir, it) })
            WipeScope.ALL_OTHERS -> baseDir.listFiles()?.filter { it.isDirectory && it != match.dir }.orEmpty()
            null -> emptyList()
        }
        targets.filter { it.isDirectory && it != match.dir }.forEach(::wipeVault)
    }

    /** Shreds every file of [vault], verifier first, then removes the directory tree. */
    fun wipeVault(vault: File) {
        shred(File(vault, VERIFIER_FILE_NAME))
        vault.walkBottomUp().forEach { file ->
            if (file.isFile) shred(file) else file.delete()
        }
    }

    /** Overwrites the leading bytes (every header and salt) with random data, then deletes. */
    private fun shred(file: File) {
        if (!file.isFile) return
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val length = minOf(raf.length(), SHRED_BYTES.toLong()).toInt()
                if (length > 0) {
                    raf.write(ByteArray(length).also { SecureRandom().nextBytes(it) })
                    raf.fd.sync()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not overwrite ${file.name} before deleting it", e)
        }
        if (!file.delete()) Log.w(TAG, "Could not delete ${file.name}")
    }

    /**
     * The settings in [dir]'s verifier if [key] opens it, else null. A v1 or legacy CBC
     * verifier that [key] opens is rewritten as v2 in place.
     */
    private fun readVerifier(dir: File, key: String): VerifierPayload? {
        val verifier = File(dir, VERIFIER_FILE_NAME)
        if (!verifier.isFile) {
            return null
        }
        val bytes = try {
            verifier.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Unreadable verifier in ${dir.name}", e)
            return null
        }

        val plain = try {
            EncryptionUtils.decryptImage(bytes, key)
        } catch (e: Exception) {
            return null // wrong key (bad tag / bad padding) or corrupt verifier
        }
        VerifierPayload.decode(plain)?.let { return it }
        if (!plain.contentEquals(VERIFIER_MAGIC_V1)) {
            return null
        }
        Log.i(TAG, "Upgrading verifier to v2")
        writeVerifier(dir, key, VerifierPayload())
        return VerifierPayload()
    }

    private fun writeVerifier(dir: File, key: String, payload: VerifierPayload) {
        val sealed = EncryptionUtils.encryptImage(payload.encode(), key, VERIFIER_ITERATIONS)
        val target = File(dir, VERIFIER_FILE_NAME)
        val temp = File(dir, "$VERIFIER_FILE_NAME.tmp")
        temp.writeBytes(sealed)
        if (!temp.renameTo(target)) {
            target.writeBytes(sealed)
            temp.delete()
        }
    }

    /**
     * Renames an MD5(key)-named vault to a random name — the old name is offline
     * brute-forceable. If the rename fails the vault keeps working under its old name
     * (found via its verifier) and the rename is retried on the next unlock.
     */
    private fun renameIfLegacy(dir: File, key: String): File {
        if (dir.name != CommonUtils.getKeyDigest(key)) {
            return dir
        }
        val renamed = File(dir.parentFile, randomVaultName())
        return if (dir.renameTo(renamed)) {
            Log.i(TAG, "Renamed legacy vault to a random name")
            renamed
        } else {
            Log.w(TAG, "Could not rename legacy vault; keeping MD5 name for now")
            dir
        }
    }

    private fun randomVaultName(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
