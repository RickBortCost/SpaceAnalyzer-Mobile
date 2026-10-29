package it.spaceanalyzer.mobile.ui

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import it.spaceanalyzer.mobile.link.DEMO_NAME
import it.spaceanalyzer.mobile.link.LatestValue
import it.spaceanalyzer.mobile.link.LinkState
import it.spaceanalyzer.mobile.model.LinkProfile
import it.spaceanalyzer.mobile.viewmodel.MainViewModel
import it.spaceanalyzer.mobile.viewmodel.Pairing
import it.spaceanalyzer.mobile.viewmodel.RecordingFile
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

private const val STALE_MS = 5_000L

@Composable
fun SpaceApp(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val rec by vm.recording.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var demoPending by remember { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (demoPending) vm.connectDemo() else vm.connect(); demoPending = false
    }
    val needsPermission = Build.VERSION.SDK_INT >= 33
    val connect: () -> Unit = {
        if (needsPermission) { demoPending = false; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) } else vm.connect()
    }
    val demo: () -> Unit = {
        if (needsPermission) { demoPending = true; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) } else vm.connectDemo()
    }
    SpaceTheme {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("Collega", "Live", "Registrazioni", "Rete").forEachIndexed { i, title ->
                    NavigationBarItem(selected = page == i, onClick = { page = i },
                        icon = { Text(listOf("⇄", "∿", "●", "☰")[i]) }, label = { Text(title, maxLines = 1) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Space Fieldbus Analyzer", style = MaterialTheme.typography.headlineSmall)
                Text("Sola ricezione dal PC • ${stateText(state)}", style = MaterialTheme.typography.labelMedium)
                if (rec.file != null) Text("● REGISTRAZIONE IN CORSO • ${rec.samples} campioni" +
                    if (rec.lowSpace) " • SPAZIO QUASI ESAURITO" else "",
                    color = if (rec.lowSpace) LocalSpaceColors.current.warning else LocalSpaceColors.current.recording,
                    style = MaterialTheme.typography.labelLarge)
                rec.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when (page) {
                    0 -> ConnectPage(vm, connect, demo)
                    1 -> LivePage(vm)
                    2 -> RecordingsPage(vm)
                    3 -> NetworkPage(vm)
                }
            }
        }
    }
}

private fun stateText(s: LinkState) = when (s) {
    LinkState.Idle -> "Non collegato"
    LinkState.Connecting -> "Collegamento…"
    is LinkState.Streaming -> "Collegato a ${s.serverName}"
    is LinkState.Reconnecting -> "Riconnessione (${s.attempt}) • ${s.reason}"
    is LinkState.Failed -> "Errore: ${s.reason}"
}

@Composable
private fun ConnectPage(vm: MainViewModel, connect: () -> Unit, demo: () -> Unit) {
    val saved by vm.profile.collectAsStateWithLifecycle()
    val savedCode by vm.accessCode.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val found by vm.discovered.collectAsStateWithLifecycle()
    var name by rememberSaveable(saved.name) { mutableStateOf(saved.name) }
    var host by rememberSaveable(saved.host) { mutableStateOf(saved.host) }
    var port by rememberSaveable(saved.port) { mutableStateOf(saved.port) }
    var code by rememberSaveable(savedCode) { mutableStateOf(savedCode) }
    val draft = LinkProfile(name.trim(), host.trim(), port.trim(), saved.pinnedFingerprint)
    val active = state !is LinkState.Idle && state !is LinkState.Failed

    Text("Collegamento al PC", style = MaterialTheme.typography.titleLarge)
    Text("1. Collegare il telefono al PC con il cavo USB e attivare «Tethering USB» (Impostazioni → Connessioni → Hotspot e tethering). " +
        "Il Wi-Fi non serve e l’IP del telefono non va modificato: lo assegna Android.\n" +
        "2. Sul PC avviare SpaceLinkBridge.exe (o il servizio «Collegamento mobile» di Space): mostra codice di accesso, impronta e indirizzo da usare.")
    if (found.isNotEmpty() && !active) {
        Text("PC trovati sulla rete", style = MaterialTheme.typography.titleMedium)
        found.values.forEach { pc ->
            OutlinedButton(onClick = { host = pc.host; port = pc.port.toString(); name = pc.name }) {
                Text("${pc.name} — ${pc.host}:${pc.port}")
            }
        }
    }
    Field("Nome", name, enabled = !active) { name = it }
    Field("Indirizzo IPv4 del PC", host, enabled = !active, keyboard = KeyboardType.Number) { host = it }
    Field("Porta", port, enabled = !active, keyboard = KeyboardType.Number) { port = it }
    Field("Codice di accesso", code, enabled = !active, secret = true) { code = it }
    draft.validationError()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val dirty = draft.copy(pinnedFingerprint = "") != saved.copy(pinnedFingerprint = "") || code != savedCode
    Button(enabled = !active && draft.validationError() == null && dirty, onClick = { vm.save(draft, code) }) { Text("Salva") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Identità del PC", style = MaterialTheme.typography.titleMedium)
            if (saved.pinnedFingerprint.isBlank()) Text("Non verificata. Il collegamento è bloccato finché non si conferma l’impronta.")
            else Text("Impronta confermata:\n${saved.pinnedFingerprint.chunked(48).joinToString("\n")}")
            when (val p = pairing) {
                Pairing.Idle -> Unit
                Pairing.Checking -> Text("Lettura del certificato…")
                is Pairing.Error -> Text(p.message, color = MaterialTheme.colorScheme.error)
                is Pairing.Found -> {
                    Text("Impronta presentata dal PC:\n${p.fingerprint.chunked(48).joinToString("\n")}")
                    Text("Confrontarla con quella mostrata in Space. Confermare solo se coincide.")
                    Button(onClick = { vm.confirmPairing(draft, code) }) { Text("Coincide: considera attendibile") }
                }
            }
            OutlinedButton(enabled = !active && draft.validationError() == null && pairing !is Pairing.Checking,
                onClick = { vm.verifyPc(draft) }) { Text("Verifica PC") }
        }
    }
    if (active) Button(onClick = vm::disconnect) { Text("Disconnetti") }
    else {
        Button(enabled = saved.pinnedFingerprint.isNotBlank() && !dirty, onClick = connect) { Text("Connetti") }
        OutlinedButton(onClick = demo) { Text("Modalità prova (dati simulati, senza PC)") }
    }
    if (dirty) Text("Salvare le modifiche prima di connettersi.")
}

@Composable
private fun LivePage(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val signals by vm.signals.collectAsStateWithLifecycle()
    val latest by vm.latest.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val colors = LocalSpaceColors.current
    var selected by rememberSaveable { mutableStateOf(setOf<Int>()) }
    var windowUs by rememberSaveable { mutableLongStateOf(30_000_000L) }
    var viewEndUs by rememberSaveable { mutableStateOf<Long?>(null) }
    var cursorUs by rememberSaveable { mutableStateOf<Long?>(null) }
    var paused by rememberSaveable { mutableStateOf(false) }
    var frame by remember { mutableStateOf(listOf<Triple<Int, androidx.compose.ui.graphics.Color, it.spaceanalyzer.mobile.link.Series>>()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(selected, paused) {
        while (true) {
            now = System.currentTimeMillis()
            if (!paused) frame = selected.sorted().mapIndexedNotNull { i, id ->
                vm.trend(id)?.let { Triple(id, colors.series[i % colors.series.size], it) }
            }
            delay(250)
        }
    }
    val streaming = state is LinkState.Streaming
    if (!streaming && signals.isEmpty()) {
        Text("Nessun dato. Collegarsi al PC dalla pagina «Collega».")
        return
    }
    if ((state as? LinkState.Streaming)?.serverName == DEMO_NAME)
        Text("DATI SIMULATI — modalità prova, nessun PC collegato", color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleSmall)
    if (!streaming) Text("Collegamento non attivo: i valori mostrati sono gli ultimi ricevuti.", color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (recording.file == null) Button(enabled = streaming, onClick = vm::startRecording,
            colors = ButtonDefaults.buttonColors(containerColor = SpaceBrand.orange, contentColor = Color.Black)) { Text("Registra") }
        else Button(onClick = vm::stopRecording,
            colors = ButtonDefaults.buttonColors(containerColor = SpaceBrand.error, contentColor = Color.White)) { Text("Stop registrazione") }
        OutlinedButton(onClick = { paused = !paused }) { Text(if (paused) "Riprendi grafico" else "Pausa grafico") }
    }
    if (recording.lowSpace) Text("Spazio libero sul telefono quasi esaurito: la registrazione si fermerà da sola.",
        color = LocalSpaceColors.current.warning)
    Text("Batch ${stats.lastSeq} • campioni ${stats.samples} • interruzioni ${stats.gaps}", style = MaterialTheme.typography.bodySmall)

    val shown = frame.filter { it.first in selected }
    val latestEnd = shown.maxOfOrNull { it.third.timesUs.lastOrNull() ?: 0L } ?: 0L
    val end = viewEndUs?.coerceAtMost(latestEnd) ?: latestEnd
    if (shown.isNotEmpty()) {
        TrendChart(shown.map { it.second to it.third }, windowUs, end, cursorUs, colors.plotText, colors.plotGrid,
            onPan = { f ->
                val moved = end - (f * windowUs).toLong()
                viewEndUs = if (moved >= latestEnd) null else moved
            },
            onZoom = { z -> windowUs = (windowUs / z).toLong().coerceIn(MIN_WINDOW_US, MAX_WINDOW_US) },
            onTap = { f -> cursorUs = end - windowUs + (f * windowUs).toLong() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 30, 60, 300).forEach { s ->
                FilterChip(selected = windowUs == s * 1_000_000L, onClick = { windowUs = s * 1_000_000L }, label = { Text("${s}s") })
            }
        }
        Text("Finestra ${"%.1f".format(windowUs / 1e6)} s • pizzica per ingrandire, trascina per scorrere, tocca per il cursore",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (viewEndUs != null) OutlinedButton(onClick = { viewEndUs = null }) { Text("Torna al live") }
            if (cursorUs != null) OutlinedButton(onClick = { cursorUs = null }) { Text("Rimuovi cursore") }
        }
        cursorUs?.let { t ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Cursore • " + java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                        .format(Date(t / 1000)), style = MaterialTheme.typography.titleSmall)
                    shown.forEach { (id, color, series) ->
                        val sig = signals.firstOrNull { it.id == id }
                        val v = valueAt(series, t)
                        Text("${sig?.name ?: id}: ${v?.let { "%.6g %s".format(it, sig?.unit.orEmpty()) } ?: "—"}", color = color)
                    }
                }
            }
        }
    } else Text("Selezionare uno o più segnali per il grafico.")

    signals.forEach { sig ->
        val v: LatestValue? = latest[sig.id]
        val stale = v == null || now - v.receivedAtMs > STALE_MS
        val color = frame.firstOrNull { it.first == sig.id }?.second
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(checked = sig.id in selected, onCheckedChange = { selected = if (it) selected + sig.id else selected - sig.id })
                Column(Modifier.weight(1f)) {
                    Text(sig.name, style = MaterialTheme.typography.titleSmall)
                    Text(when {
                        v == null -> "nessun dato"
                        v.quality != 0 -> "qualità non buona (${v.quality})"
                        stale -> "dato obsoleto"
                        else -> "aggiornato"
                    }, style = MaterialTheme.typography.bodySmall,
                        color = if (v == null || stale || v.quality != 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(v?.let { "%.6g %s".format(it.value, sig.unit) } ?: "—", style = MaterialTheme.typography.titleMedium,
                    color = color ?: MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun RecordingsPage(vm: MainViewModel) {
    val list by vm.recordings.collectAsStateWithLifecycle()
    val active by vm.recording.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<Pair<RecordingFile, Boolean>?>(null) }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val p = pending; pending = null
        if (uri != null && p != null) vm.export(uri, p.first, p.second)
    }
    LaunchedEffect(Unit) { vm.refreshRecordings() }
    Text("Registrazioni sul telefono", style = MaterialTheme.typography.titleLarge)
    if (list.isEmpty()) Text("Nessuna registrazione. Avviarne una dalla pagina «Live».")
    list.forEach { r ->
        val recordingNow = active.file == r.file
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(r.file.name, style = MaterialTheme.typography.titleSmall)
                Text("${DateFormat.getDateTimeInstance().format(Date(r.modifiedMs))} • ${r.sizeBytes / 1024} KB" +
                    if (recordingNow) " • in corso" else "", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !recordingNow, onClick = { pending = r to false; create.launch(r.file.name) }) { Text("Esporta") }
                    TextButton(enabled = !recordingNow, onClick = { pending = r to true; create.launch(r.file.nameWithoutExtension + ".csv") }) { Text("CSV") }
                    TextButton(enabled = !recordingNow, onClick = { vm.delete(r) }) { Text("Elimina") }
                }
            }
        }
    }
}

@Composable
private fun NetworkPage(vm: MainViewModel) {
    val networks by vm.networks.collectAsStateWithLifecycle()
    Text("Reti del telefono", style = MaterialTheme.typography.titleLarge)
    Text("Con il tethering USB attivo compare un’interfaccia dedicata. Il collegamento al PC usa quella rete.")
    if (networks.isEmpty()) Text("Nessuna rete rilevata.")
    networks.forEach { n ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${n.transport} • ${n.name}", style = MaterialTheme.typography.titleMedium)
                Text("IP / subnet: ${n.addresses.joinToString("\n").ifEmpty { "non assegnato" }}")
                Text("Gateway: ${n.gateways.joinToString().ifEmpty { "nessuno" }}")
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, enabled: Boolean = true, secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, enabled = enabled,
        singleLine = true, modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard))
}
