# Space Link Bridge — eseguibile Windows

`SpaceLinkBridge.exe` è un programma **separato** che collega l'app Android a **Space Fieldbus Analyzer senza modificare Space**: legge i segnali del Live dalla API locale di Space (la stessa che usa la sua pagina web) e li serve al telefono con il protocollo SFA Link (`docs/PROTOCOL.md`). Non richiede installazioni: è un solo `.exe` autonomo (Windows 10/11 x64).

```
Space (PC) ── API locale 127.0.0.1 ──► SpaceLinkBridge.exe ── USB tethering / Wi-Fi, TLS ──► app Android
```

## Uso

1. Avviare **Space** e collegarlo alla sorgente come al solito (selezionare i segnali nel Live).
2. Avviare `SpaceLinkBridge.exe` (doppio clic). Mostra stato di Space, **codice di accesso**, **impronta del certificato** e gli indirizzi del PC. Al primo avvio Windows chiede l'accesso al firewall: con il tethering USB la rete del telefono è classificata «pubblica», quindi spuntare **anche le reti pubbliche** (oppure creare una regola in ingresso per TCP 47800 e UDP 47810).
3. Telefono: collegare il cavo USB e attivare «Tethering USB». Nell'app, pagina **Collega**: scegliere il PC trovato (o inserire l'IPv4 mostrato dal ponte), inserire il codice, **Verifica PC**, confrontare l'impronta con quella del ponte, **Coincide**, **Connetti**.
4. Tasti nel ponte: **N** nuovo codice di accesso (interrompe i telefoni collegati), **Q** esci.

Il ponte e Space si avviano in qualsiasi ordine: se Space non c'è, il ponte lo cerca ogni 2 secondi (127.0.0.1, porte 5000–5019) e si ricollega da solo se Space viene chiuso e riaperto.

### Opzioni

| Opzione | Significato |
|---|---|
| `--space-url <url>` | indirizzo di Space (solo `127.0.0.1`/`localhost`); predefinito: ricerca automatica |
| `--port <n>` | porta per il telefono (predefinita 47800, poi ricordata) |
| `--poll-ms <n>` | intervallo di lettura da Space, 20–1000 ms (predefinito 100) |
| `--data-dir <cartella>` | impostazioni e certificato (predefinita `%LOCALAPPDATA%\SpaceLinkBridge`) |
| `--new-code` | genera subito un nuovo codice di accesso |

## Cosa legge da Space (solo lettura)

Il ponte usa **solo** queste chiamate alla API locale di Space; un test strutturale (`tests/test_source_contract.py`) lo verifica:

| Chiamata | Scopo |
|---|---|
| `GET /api/status` | riconoscere Space e leggerne la versione |
| `GET /` | ottenere il token di pagina (necessario per la lettura dei buffer) |
| `GET /api/config` | nomi e unità dei segnali (selezione del workspace, serie calcolate) |
| `POST /api/live/samples` | campioni del Live a piena risoluzione dopo un cursore: **legge** i buffer, non li modifica |

Non chiama mai connessione, selezione, registrazione, impostazioni o altro che cambi lo stato di Space. Il ponte non scrive nulla verso Space né verso il telefono.

- I **nomi** dei segnali sono quelli salvati da Space nel workspace (il salvataggio automatico avviene circa 1 secondo dopo ogni cambio di selezione). Finché non sono salvati compare il NodeId.
- Allo **start** (e dopo «Pulisci» in Space) il ponte non invia la storia già presente nei buffer: invia solo i campioni nuovi. La registrazione completa resta quella di Space.
- I campioni **non numerici** o non finiti (per esempio con qualità Bad) non sono inviati; la qualità Uncertain/Bad dei campioni numerici è inviata al telefono.
- Il tempo è quello assegnato da Space (millisecondi Unix), convertito in microsecondi.

## Sicurezza

- Il servizio per il telefono ascolta su tutte le interfacce **solo mentre il ponte è in esecuzione**; su quella porta c'è soltanto il protocollo SFA Link (né Space né la sua interfaccia sono raggiungibili).
- Cifratura TLS con certificato autofirmato generato al primo avvio (`<cartella dati>\server.pfx`); il telefono si fida solo dell'impronta SHA-256 confermata dall'utente.
- Codice di accesso a 8 cifre casuali, confronto a tempo costante; dopo 5 tentativi errati in un minuto le richieste sono bloccate per il resto del minuto.
- Il ponte parla con Space solo su loopback.

## Build

Serve il .NET 8 SDK solo per compilare.

```
windows-bridge\build-exe.ps1        # Windows: test + exe in windows-bridge\dist\SpaceLinkBridge.exe
windows-bridge/build-exe.sh         # Linux/macOS: compila lo stesso exe per Windows
dotnet test windows-bridge/SpaceLinkBridge.Tests/SpaceLinkBridge.Tests.csproj
```

La pipeline `.github/workflows/bridge.yml` esegue i test **su Windows**, pubblica l'exe e lo prova (`--help` e apertura della porta), caricandolo come artifact `SpaceLinkBridge-win-x64`.

L'eseguibile **non è firmato**: Windows SmartScreen può mostrare un avviso («Altre informazioni → Esegui comunque»). Per distribuirlo ad altri conviene firmarlo con un certificato di firma del codice.

## Stato di verifica

Verificato (Linux, .NET 8):
- 36 test automatici: hub (numerazione, ripresa, `gap`, filtro, perdita di batch, cambio segnali), sicurezza (codice, blocco, certificato), gRPC + TLS con impronta fissata come sul telefono, decodifica del formato di Space, finto Space in-process (token, cursori, riavvio di Space, nomi), percorso completo Space → ponte → client gRPC.
- **Space vero** (V3.14.1) collegato a un server OPC UA di prova: il ponte ha letto 4 segnali a 10 Hz (≈40 campioni/s), sequenza contigua, timestamp corretti, ripresa senza buchi, codice errato rifiutato; provato con un client gRPC Python e con il **client Android reale** (Kotlin).
- L'exe Windows x64 si compila (47,7 MB).

**Non verificato**: esecuzione dell'exe su Windows (la pipeline lo prova alla prima esecuzione), firewall e tethering USB con un telefono reale, sorgenti diverse da OPC UA (ADS, S7, MQTT, serie calcolate: il formato dei buffer è lo stesso, ma non sono state provate), carichi elevati e prove di durata.
