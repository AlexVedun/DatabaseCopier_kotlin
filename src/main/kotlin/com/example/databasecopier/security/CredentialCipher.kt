package com.example.databasecopier.security

import com.example.databasecopier.getAppDataDir
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрует пароли к внешним БД перед сохранением в служебной SQLite (Шаг 0 инструкции: пароли
 * никогда не хранятся в открытом виде). Ключ генерируется один раз и хранится локально в
 * каталоге данных приложения — этого достаточно для защиты от простого просмотра файла БД,
 * не для защиты от доступа с правами того же пользователя ОС.
 */
object CredentialCipher {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12

    private val secretKey: SecretKey by lazy { loadOrCreateKey() }

    fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + cipherBytes)
    }

    fun decrypt(encoded: String): String {
        val combined = Base64.getDecoder().decode(encoded)
        val iv = combined.copyOfRange(0, IV_LENGTH_BYTES)
        val cipherBytes = combined.copyOfRange(IV_LENGTH_BYTES, combined.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyFile = File(getAppDataDir(), "master.key")
        if (keyFile.exists()) {
            return SecretKeySpec(Base64.getDecoder().decode(keyFile.readText()), "AES")
        }
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        keyFile.writeText(Base64.getEncoder().encodeToString(key.encoded))
        return key
    }
}
