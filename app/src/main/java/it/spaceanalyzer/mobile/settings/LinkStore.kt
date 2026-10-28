package it.spaceanalyzer.mobile.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import it.spaceanalyzer.mobile.model.LinkProfile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.linkData by preferencesDataStore("link")

/** Non-sensitive profile in DataStore; the access code only encrypted with an Android Keystore key. */
class LinkStore(private val context: Context) {
    private val name = stringPreferencesKey("name")
    private val host = stringPreferencesKey("host")
    private val port = stringPreferencesKey("port")
    private val fingerprint = stringPreferencesKey("fingerprint")
    private val code = stringPreferencesKey("code")

    val profile: Flow<LinkProfile> = context.linkData.data.map { p ->
        val d = LinkProfile()
        LinkProfile(p[name] ?: d.name, p[host] ?: d.host, p[port] ?: d.port, p[fingerprint] ?: d.pinnedFingerprint)
    }

    suspend fun save(profile: LinkProfile) {
        require(profile.validationError() == null)
        context.linkData.edit { p ->
            val changedPc = p[host] != profile.host || p[port] != profile.port
            p[name] = profile.name; p[host] = profile.host; p[port] = profile.port
            // A pin never survives an address change: it was confirmed for another machine.
            p[fingerprint] = if (changedPc && profile.pinnedFingerprint == p[fingerprint]) "" else profile.pinnedFingerprint
        }
    }

    suspend fun accessCode(): String = context.linkData.data.first()[code]?.let(SecretBox::open).orEmpty()

    suspend fun saveAccessCode(value: String) {
        context.linkData.edit { p -> if (value.isEmpty()) p.remove(code) else p[code] = SecretBox.seal(value) }
    }
}

private object SecretBox {
    private const val ALIAS = "sfa_access_code"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }
    fun open(sealed: String): String? = runCatching {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12))) }
        String(c.doFinal(raw, 12, raw.size - 12))
    }.getOrNull()
}
