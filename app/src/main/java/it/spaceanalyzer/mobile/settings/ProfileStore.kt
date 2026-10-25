package it.spaceanalyzer.mobile.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import it.spaceanalyzer.mobile.model.Profile
import kotlinx.coroutines.flow.map

private val Context.profileData by preferencesDataStore("connection")
class ProfileStore(private val context: Context) {
    private val keys = listOf("name", "host", "endpoint", "sshPort", "phoneIp", "prefix", "timeoutMs").map(::stringPreferencesKey)
    val profile = context.profileData.data.map { p ->
        val defaults = Profile()
        Profile(p[keys[0]] ?: defaults.name, p[keys[1]] ?: defaults.host,
            p[keys[2]] ?: defaults.endpoint, p[keys[3]] ?: defaults.sshPort,
            p[keys[4]] ?: defaults.phoneIp, p[keys[5]] ?: defaults.prefix,
            p[keys[6]] ?: defaults.timeoutMs)
    }
    suspend fun save(profile: Profile) {
        require(profile.validationError() == null)
        context.profileData.edit { p ->
            listOf(profile.name, profile.host, profile.endpoint, profile.sshPort,
                profile.phoneIp, profile.prefix, profile.timeoutMs).forEachIndexed { i, value -> p[keys[i]] = value }
        }
    }
}
