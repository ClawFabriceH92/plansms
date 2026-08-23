package com.fabrice.plansms.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Chiffrement AES-256/GCM des secrets (mots de passe FTP, SMTP) avec une clé
 * conservée dans l'Android Keystore : elle ne quitte jamais le matériel sécurisé
 * et n'est pas extractible, même avec un accès au stockage de l'app.
 */
object SecretStore {
    private const val ALIAS = "plansms_secrets"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Retourne "ivBase64:donneesBase64", ou "" si le chiffrement échoue. */
    fun encrypt(plain: String): String = try {
        if (plain.isEmpty()) {
            ""
        } else {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key())
            val out = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(out, Base64.NO_WRAP)
        }
    } catch (_: Exception) {
        ""
    }

    fun decrypt(stored: String): String = try {
        val parts = stored.split(":")
        if (parts.size != 2) {
            ""
        } else {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(data), Charsets.UTF_8)
        }
    } catch (_: Exception) {
        ""
    }
}
