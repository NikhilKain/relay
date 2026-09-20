package com.vythera.relay.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.vythera.relay.security.IdentityCodec
import com.vythera.relay.security.IdentityStore
import com.vythera.relay.security.RelayIdentity
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the device identity encrypted at rest with an AES-256-GCM key that lives in the
 * Android Keystore (hardware-backed where the device supports it) and never leaves it.
 *
 * The TLS private key itself is a software key: JSSE needs to sign with it during the
 * handshake, and TLS through Keystore keys is not dependable across the Android versions
 * Relay supports. Wrapping it means a copy of the app's files is useless without the
 * device's Keystore. See docs/security-model.md.
 *
 * The file is excluded from backups (see backup rules), so a restored install gets a new
 * identity and must be paired again, rather than two devices sharing one identity.
 */
class KeystoreIdentityStore(context: Context) : IdentityStore {
    private val file = File(context.noBackupFilesDir, "identity.bin")

    override fun load(): RelayIdentity? {
        if (!file.isFile) return null
        return runCatching {
            val bytes = file.readBytes()
            val ivLength = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivLength)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
            IdentityCodec.decode(cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength))
        }.getOrNull()
    }

    override fun save(identity: RelayIdentity) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(IdentityCodec.encode(identity))
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted)
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "relay-identity-wrap"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
