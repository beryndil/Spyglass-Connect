package com.spyglass.connect.server

import com.spyglass.connect.Log
import java.io.File
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ECDH key exchange + AES-256-GCM encryption for Spyglass Connect.
 * Uses standard java.security (compatible with Android's java.security).
 *
 * The server's key pair is persisted to disk so the phone's stored
 * public key remains valid across desktop restarts.
 */
class EncryptionManager internal constructor(private val keyPair: KeyPair) {

    companion object {
        private const val CURVE = "secp256r1"
        private const val AES_KEY_BITS = 256
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private val INFO = "spyglass-connect-v1".toByteArray()

        private val KEY_FILE = File(
            System.getProperty("user.home"), ".config/spyglass-connect/ecdh-keypair"
        )

        private fun generateKeyPair(): KeyPair {
            val keyGen = KeyPairGenerator.getInstance("EC")
            keyGen.initialize(ECGenParameterSpec(CURVE), SecureRandom())
            return keyGen.generateKeyPair()
        }

        private const val TAG = "Crypto"

        /** Load a persisted key pair from disk, or generate and save a new one. */
        fun loadOrCreate(): EncryptionManager {
            val loaded = loadKeyPair()
            if (loaded != null) {
                Log.i(TAG, "Loaded ECDH key pair from ${KEY_FILE.absolutePath}")
                return EncryptionManager(loaded)
            }

            Log.i(TAG, "Generating new ECDH key pair")
            val keyPair = generateKeyPair()
            saveKeyPair(keyPair)
            return EncryptionManager(keyPair)
        }

        private fun loadKeyPair(): KeyPair? {
            if (!KEY_FILE.exists()) return null
            return try {
                val lines = KEY_FILE.readLines()
                if (lines.size < 2) return null
                val pubBytes = Base64.getDecoder().decode(lines[0])
                val privBytes = Base64.getDecoder().decode(lines[1])
                val kf = KeyFactory.getInstance("EC")
                val pub = kf.generatePublic(X509EncodedKeySpec(pubBytes))
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                KeyPair(pub, priv)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load key pair: ${e.message}, generating new one")
                null
            }
        }

        private fun saveKeyPair(keyPair: KeyPair) {
            try {
                KEY_FILE.parentFile?.mkdirs()
                val pub = Base64.getEncoder().encodeToString(keyPair.public.encoded)
                val priv = Base64.getEncoder().encodeToString(keyPair.private.encoded)
                KEY_FILE.writeText("$pub\n$priv\n")
                // Restrict permissions to owner only
                KEY_FILE.setReadable(false, false)
                KEY_FILE.setReadable(true, true)
                KEY_FILE.setWritable(false, false)
                KEY_FILE.setWritable(true, true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save key pair: ${e.message}")
            }
        }
    }

    private var sharedKey: SecretKeySpec? = null
    private val secureRandom = SecureRandom()

    /** Primary constructor — generates a new ECDH key pair (not persisted). */
    constructor() : this(generateKeyPair())

    /**
     * Create a per-client encryption session that uses the server's key pair
     * but has its own shared key. This prevents multi-client key collisions.
     */
    fun createClientSession(): EncryptionManager = EncryptionManager(keyPair)

    /** Get our public key as Base64 for transmission. */
    fun getPublicKeyBase64(): String =
        Base64.getEncoder().encodeToString(keyPair.public.encoded)

    /** Generate a random nonce for the QR code. */
    fun generateNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * Derive shared AES-256 key from the peer's public key.
     */
    fun deriveSharedKey(peerPublicKeyBase64: String) {
        val peerKeyBytes = Base64.getDecoder().decode(peerPublicKeyBase64)
        val keyFactory = KeyFactory.getInstance("EC")
        val peerPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(peerKeyBytes))

        val keyAgreement = KeyAgreement.getInstance("ECDH")
        keyAgreement.init(keyPair.private)
        keyAgreement.doPhase(peerPublicKey, true)
        val rawSecret = keyAgreement.generateSecret()
        val sharedSecret = normalizeSecret(rawSecret)

        Log.d(TAG, "ECDH provider: ${keyAgreement.provider.name}")
        Log.d(TAG, "Raw secret (${rawSecret.size} bytes), normalized to ${sharedSecret.size} bytes")
        Log.d(TAG, "Shared secret (first 8): ${sharedSecret.take(8).joinToString("") { "%02x".format(it) }}...")
        Log.d(TAG, "Our pubkey hash: ${keyPair.public.encoded.take(8).joinToString("") { "%02x".format(it) }}...")
        Log.d(TAG, "Peer pubkey hash: ${peerKeyBytes.take(8).joinToString("") { "%02x".format(it) }}...")

        // HKDF-SHA256: extract + expand
        val prk = hkdfExtract(ByteArray(32), sharedSecret)
        val okm = hkdfExpand(prk, INFO, AES_KEY_BITS / 8)
        Log.d(TAG, "Derived AES key (first 8): ${okm.take(8).joinToString("") { "%02x".format(it) }}...")
        sharedKey = SecretKeySpec(okm, "AES")
    }

    /** Check if encryption is established. */
    val isReady: Boolean get() = sharedKey != null

    /** Encrypt a plaintext message. Returns Base64(IV + ciphertext + tag). */
    fun encrypt(plaintext: String): String {
        val key = sharedKey ?: throw IllegalStateException("Shared key not derived")
        val iv = ByteArray(GCM_IV_BYTES)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)

        return Base64.getEncoder().encodeToString(result)
    }

    /** Decrypt a Base64(IV + ciphertext + tag) message. */
    fun decrypt(encryptedBase64: String): String {
        val key = sharedKey ?: throw IllegalStateException("Shared key not derived")
        val data = Base64.getDecoder().decode(encryptedBase64)

        val iv = data.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = data.copyOfRange(GCM_IV_BYTES, data.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    /**
     * Normalize the raw ECDH shared secret to exactly [expectedLen] bytes.
     * SunEC (JVM) and Conscrypt (Android) may produce different-length X coordinate
     * outputs — this ensures HKDF input is identical on both platforms.
     */
    private fun normalizeSecret(secret: ByteArray, expectedLen: Int = 32): ByteArray {
        return when {
            secret.size == expectedLen -> secret
            secret.size > expectedLen -> secret.copyOfRange(secret.size - expectedLen, secret.size)
            else -> ByteArray(expectedLen - secret.size) + secret
        }
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var i: Byte = 1

        while (offset < length) {
            mac.update(t)
            mac.update(info)
            mac.update(i)
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            i++
        }

        return result
    }
}
