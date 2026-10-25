package it.spaceanalyzer.mobile.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import it.spaceanalyzer.mobile.model.Profile
import it.spaceanalyzer.mobile.viewmodel.MainViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun SpaceApp(vm: MainViewModel = viewModel()) {
    val networks by vm.networks.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val probe by vm.probe.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val ethernet = networks.filter { it.ethernet }
    val selected = ethernet.firstOrNull { it.id == selectedId } ?: ethernet.firstOrNull()
    LaunchedEffect(selected) { selectedId = selected?.id; vm.invalidate() }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.invalidate() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
        Scaffold(bottomBar = {
            NavigationBar {
                listOf("Overview", "Rete", "Impostazioni").forEachIndexed { i, title ->
                    NavigationBarItem(selected = page == i, onClick = { page = i },
                        icon = { Text(listOf("◉", "⇄", "⚙")[i]) }, label = { Text(title) })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Space Analyzer Mobile", style = MaterialTheme.typography.headlineSmall)
                Text("SOLA LETTURA • v0.1 / Milestone 1", style = MaterialTheme.typography.labelMedium)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                when (page) {
                    0 -> {
                        InfoCard(profile.name, "IPC: ${profile.host}\nHostname: Unknown")
                        InfoCard("Connessioni", "Ethernet: ${if (selected == null) "Disconnected" else "Connected — ${selected.name}"}\nOPC UA: non implementato\nSSH: non implementato\nTwinCAT: Unknown")
                        InfoCard("Diagnostica industriale", "CPU, RAM, disco e uptime: non disponibili. Richiedono il client SSH della Milestone 3.")
                        Button(onClick = { page = 1 }) { Text("Apri Network Diagnostics") }
                    }
                    1 -> {
                        Text("Network Diagnostics", style = MaterialTheme.typography.titleLarge)
                        if (networks.isEmpty()) Text("Nessuna rete rilevata. Collegare l’adattatore USB-C/Ethernet.")
                        networks.forEach { n ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${n.transport} • ${n.name}", style = MaterialTheme.typography.titleMedium)
                                    Text("IP / subnet: ${n.addresses.joinToString("\n").ifEmpty { "non assegnato" }}")
                                    Text("Gateway: ${n.gateways.joinToString().ifEmpty { "nessuno" }}")
                                    Text("DNS: ${n.dns.joinToString().ifEmpty { "nessuno" }}")
                                    if (n.ethernet) TextButton(onClick = { selectedId = n.id }) {
                                        Text(if (selected?.id == n.id) "Ethernet selezionata" else "Seleziona Ethernet")
                                    }
                                }
                            }
                        }
                        Text("Test senza invio di comandi o dati applicativi.")
                        Button(enabled = selected != null && !probe.running,
                            onClick = { selected?.let { vm.test(it.id) } }) {
                            Text(if (probe.running) "Test in corso…" else "Verifica porte TCP")
                        }
                        InfoCard("OPC UA • ${profile.endpoint}", probe.opc)
                        InfoCard("SSH • ${profile.host}:${profile.sshPort}", probe.ssh)
                        probe.checkedAt?.let { Text("Risultato puntuale: ${DateFormat.getDateTimeInstance().format(Date(it))}. Non è un monitoraggio continuo.") }
                        InfoCard("IPv4 manuale", "Telefono: ${profile.phoneIp}/${profile.prefix}\nIPC previsto: ${profile.host}\nConfigurare l’IP Ethernet dalle impostazioni Samsung, se disponibili. Questa app salva il riferimento ma non modifica la rete. Se l’opzione manca, usare DHCP su una rete di prova separata; non cambiare automaticamente il CX2062.")
                        val context = LocalContext.current
                        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }) { Text("Apri impostazioni Android") }
                    }
                    2 -> ProfileEditor(profile, vm::save)
                }
                Text("Nessuna misura simulata. Nessuna modifica al PLC o all’IPC.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body)
        }
    }
}

@Composable
private fun ProfileEditor(saved: Profile, save: (Profile) -> Unit) {
    var name by rememberSaveable(saved) { mutableStateOf(saved.name) }
    var host by rememberSaveable(saved) { mutableStateOf(saved.host) }
    var endpoint by rememberSaveable(saved) { mutableStateOf(saved.endpoint) }
    var port by rememberSaveable(saved) { mutableStateOf(saved.sshPort) }
    var phone by rememberSaveable(saved) { mutableStateOf(saved.phoneIp) }
    var prefix by rememberSaveable(saved) { mutableStateOf(saved.prefix) }
    var timeout by rememberSaveable(saved) { mutableStateOf(saved.timeoutMs) }
    Text("Profilo connessione", style = MaterialTheme.typography.titleLarge)
    Field("Nome IPC", name) { name = it }
    Field("IPv4 IPC / SSH", host) { host = it }
    Field("Endpoint OPC UA", endpoint) { endpoint = it }
    Field("Porta SSH", port) { port = it }
    Field("IPv4 telefono (solo riferimento)", phone) { phone = it }
    Field("Prefisso subnet (24 = 255.255.255.0)", prefix) { prefix = it }
    Field("Timeout TCP (ms)", timeout) { timeout = it }
    val draft = Profile(name.trim(), host.trim(), endpoint.trim(), port.trim(), phone.trim(), prefix.trim(), timeout.trim())
    draft.validationError()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(enabled = draft.validationError() == null && draft != saved, onClick = { save(draft) }) { Text("Salva profilo") }
    Text("${if (draft == saved) "Profilo salvato." else "Modifiche non salvate."} Un profilo nella Milestone 1. Nessuna credenziale viene raccolta.")
}

@Composable
private fun Field(label: String, value: String, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth())
}
