package com.cairn.app.crypto

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object ChunkCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16

    data class EncryptedChunk(
        val nonce: ByteArray,
        val ciphertextWithTag: ByteArray
    )

    fun encrypt(key: SecretKey, plaintext: ByteArray, associatedData: ByteArray? = null): EncryptedChunk {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        associatedData?.let(cipher::updateAAD)
        val nonce = cipher.iv
        require(nonce.size == NONCE_BYTES) { "Unexpected AES-GCM nonce size: ${nonce.size}" }
        return EncryptedChunk(nonce, cipher.doFinal(plaintext))
    }

    fun decrypt(
        key: SecretKey,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray,
        associatedData: ByteArray? = null
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        associatedData?.let(cipher::updateAAD)
        return cipher.doFinal(ciphertextWithTag)
    }
}
