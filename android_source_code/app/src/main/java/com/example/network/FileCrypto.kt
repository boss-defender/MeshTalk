package com.example.network

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Unchanged from the original APK: AES-256-CTR, a zero-padded static key and a
 * random 16-byte IV sent immediately after every FILE_HEADER frame.
 */
object FileCrypto {
    private val keyBytes: ByteArray = "P2P_TACTICAL_DIRECT_BINARY_KEY"
        .toByteArray(Charsets.UTF_8)
        .copyOf(32)

    fun newIv(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    fun encrypt(iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        }

    fun decrypt(iv: ByteArray): Cipher =
        Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        }
}
