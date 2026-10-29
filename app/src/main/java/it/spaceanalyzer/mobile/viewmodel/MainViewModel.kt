package it.spaceanalyzer.mobile.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import it.spaceanalyzer.mobile.SfaApp
import it.spaceanalyzer.mobile.link.DEMO_HOST
import it.spaceanalyzer.mobile.link.DiscoveredPc
import it.spaceanalyzer.mobile.link.discoveryFlow
import it.spaceanalyzer.mobile.link.fetchServerFingerprint
import it.spaceanalyzer.mobile.model.LinkProfile
import it.spaceanalyzer.mobile.networking.NetworkRepository
import it.spaceanalyzer.mobile.record.sfarToCsv
import it.spaceanalyzer.mobile.service.LinkService
import it.spaceanalyzer.mobile.settings.LinkStore
import java.io.File
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Pairing {
    data object Idle : Pairing
    data object Checking : Pairing
    /** Certificate presented by the PC; becomes trusted only after explicit confirmation. */
    data class Found(val fingerprint: String) : Pairing
    data class Error(val message: String) : Pairing
}

data class RecordingFile(val file: File, val sizeBytes: Long, val modifiedMs: Long)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val session = (application as SfaApp).session
    private val store = LinkStore(application)
    private val networkRepository = NetworkRepository(application)
    private val recordingsDir = File(application.filesDir, "recordings").apply { mkdirs() }

    val state = session.state
    val signals = session.signals
    val latest = session.latest
    val stats = session.stats
    val recording = session.recording
    fun trend(signalId: Int) = session.trend(signalId)

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    val networks = networkRepository.networks.catch { _message.value = "Diagnostica rete: ${it.message}" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    val profile = store.profile.catch { _message.value = "Lettura impostazioni: ${it.message}" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LinkProfile())
    private val _accessCode = MutableStateFlow("")
    val accessCode = _accessCode.asStateFlow()
    private val _pairing = MutableStateFlow<Pairing>(Pairing.Idle)
    val pairing = _pairing.asStateFlow()
    private val _discovered = MutableStateFlow<Map<String, DiscoveredPc>>(emptyMap())
    val discovered = _discovered.asStateFlow()
    private val _recordings = MutableStateFlow<List<RecordingFile>>(emptyList())
    val recordings = _recordings.asStateFlow()

    init {
        viewModelScope.launch { _accessCode.value = store.accessCode() }
        viewModelScope.launch {
            discoveryFlow().catch { _message.value = "Ricerca PC non disponibile: ${it.message}" }
                .collect { _discovered.value = _discovered.value + (it.host to it) }
        }
        refreshRecordings()
    }

    fun save(p: LinkProfile, code: String) {
        p.validationError()?.let { _message.value = it; return }
        viewModelScope.launch {
            try {
                val changedPc = p.host != profile.value.host || p.port != profile.value.port
                store.save(if (changedPc) p.copy(pinnedFingerprint = "") else p)
                store.saveAccessCode(code); _accessCode.value = code
                if (changedPc) _pairing.value = Pairing.Idle
                _message.value = null
            } catch (e: Exception) { _message.value = "Salvataggio: ${e.message}" }
        }
    }

    /** Reads the certificate the PC presents; nothing is trusted until [confirmPairing]. */
    fun verifyPc(p: LinkProfile) {
        p.validationError()?.let { _message.value = it; return }
        _pairing.value = Pairing.Checking
        viewModelScope.launch {
            _pairing.value = try {
                Pairing.Found(withContext(Dispatchers.IO) {
                    fetchServerFingerprint(InetSocketAddress(p.resolved(), p.portNumber()), 4000)
                })
            } catch (e: Exception) { Pairing.Error("PC non raggiungibile o non compatibile: ${e.message.orEmpty().take(120)}") }
        }
    }

    fun confirmPairing(p: LinkProfile, code: String) {
        val found = _pairing.value as? Pairing.Found ?: return
        save(p.copy(pinnedFingerprint = found.fingerprint), code)
        _pairing.value = Pairing.Idle
    }

    fun connect() {
        val p = profile.value
        p.validationError()?.let { _message.value = it; return }
        if (p.pinnedFingerprint.isBlank()) { _message.value = "Verificare e confermare prima l’impronta del PC"; return }
        if (_accessCode.value.isBlank()) { _message.value = "Inserire il codice di accesso mostrato sul PC"; return }
        _message.value = null
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java))
        session.start(p, _accessCode.value)
    }

    /** Local simulated source: no PC needed. Clearly labelled everywhere it appears. */
    fun connectDemo() {
        _message.value = null
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java))
        session.start(LinkProfile(name = "Prova", host = DEMO_HOST), "")
    }

    fun disconnect() { session.stop(); refreshRecordings() }

    fun startRecording() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val acquisition = (state.value as? it.spaceanalyzer.mobile.link.LinkState.Streaming)?.acquisition.orEmpty()
        session.startRecording(File(recordingsDir, "$stamp.sfar"), acquisition)
        refreshRecordings()
    }

    fun stopRecording() { session.stopRecording(); refreshRecordings() }

    fun refreshRecordings() {
        _recordings.value = recordingsDir.listFiles { f -> f.extension == "sfar" }.orEmpty()
            .map { RecordingFile(it, it.length(), it.lastModified()) }.sortedByDescending { it.modifiedMs }
    }

    fun delete(r: RecordingFile) {
        if (recording.value.file == r.file) return
        r.file.delete(); refreshRecordings()
    }

    fun export(uri: Uri, r: RecordingFile, csv: Boolean) {
        viewModelScope.launch {
            _message.value = try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    resolver.openOutputStream(uri)!!.use { out ->
                        r.file.inputStream().use { input -> if (csv) sfarToCsv(input, out) else input.copyTo(out) }
                    }
                }
                "Esportato: ${r.file.name}${if (csv) " (CSV)" else ""}"
            } catch (e: Exception) { "Esportazione fallita: ${e.message}" }
        }
    }
}
