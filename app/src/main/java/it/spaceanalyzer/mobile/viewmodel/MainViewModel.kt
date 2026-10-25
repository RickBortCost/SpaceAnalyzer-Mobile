package it.spaceanalyzer.mobile.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.spaceanalyzer.mobile.model.Profile
import it.spaceanalyzer.mobile.networking.NetworkRepository
import it.spaceanalyzer.mobile.settings.ProfileStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProbeState(val running: Boolean = false, val opc: String = "Non verificato",
    val ssh: String = "Non verificato", val networkId: Long? = null, val checkedAt: Long? = null)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = NetworkRepository(application)
    private val store = ProfileStore(application)
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    val networks = repository.networks.catch { _error.value = "Diagnostica rete: ${it.message}" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    val profile = store.profile.catch { _error.value = "Lettura impostazioni: ${it.message}" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Profile())
    private val _probe = MutableStateFlow(ProbeState())
    val probe = _probe.asStateFlow()
    private var probeJob: Job? = null
    fun save(p: Profile) {
        p.validationError()?.let { _error.value = it; return }
        viewModelScope.launch {
            try { store.save(p); invalidate(); _error.value = null }
            catch (e: Exception) { _error.value = "Salvataggio: ${e.message}" }
        }
    }
    fun invalidate() { probeJob?.cancel(); _probe.value = ProbeState() }
    fun test(networkId: Long) {
        if (_probe.value.running) return
        val p = profile.value
        p.validationError()?.let { _error.value = it; return }
        val opc = p.opcAddress() ?: return
        probeJob = viewModelScope.launch {
            _probe.value = ProbeState(running = true, networkId = networkId)
            coroutineScope {
                val a = async { repository.probe(networkId, opc.first, opc.second, p.timeoutMs.toInt()) }
                val b = async { repository.probe(networkId, p.host, p.sshPort.toInt(), p.timeoutMs.toInt()) }
                _probe.value = ProbeState(opc = a.await(), ssh = b.await(), networkId = networkId,
                    checkedAt = System.currentTimeMillis())
            }
        }
    }
}
