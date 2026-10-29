# Space Link Bridge — eseguibile Windows

`SpaceLinkBridge.exe` è un programma **separato** che collega l'app Android a **Space Fieldbus Analyzer senza modificare Space**: legge i segnali del Live dalla API locale di Space (la stessa che usa la sua pagina web) e li serve al telefono con il protocollo SFA Link (`docs/PROTOCOL.md`). Non richiede installazioni: è un solo `.exe` autonomo (Windows 10/11 x64).

```
Space (PC) ── API locale 127.0.0.1 ──► SpaceLinkBridge.exe ── USB tethering / Wi-Fi, TLS ──► app Android
```

## Uso

1. Avviare **Space** e collegarlo alla sorgente come al solito (selezionare i segnali nel Live).
2. Avviare `SpaceLinkBridge.exe` (doppio clic). Mostra stato di Space, **codice di accesso**, **impronta del certificato** e gli indirizzi del PC. Al primo avvio Windows chiede l'accesso al firewall: con il tethering USB la rete del telefono è classificata «pubblica», quindi spuntare **anche le reti pubbliche** (oppure creare una regola in ingresso per TCP 47800 e UDP 47810).
3. Telefono: collegare il cavo USB e attivare «Tethering USB». Nell'app, pagina **Collega**: scegliere il PC trovato (o inserire l'IPv4 mostrato dal ponte), inserire il codice, **Verifica PC**, confrontare l'impronta con quella del ponte, **Coincide**, **Connetti**.
4. Tasti nel ponte: **N** nuovo codice di accesso (interrompe i telefoni collegati), **U** alterna «solo USB» / «tutte le schede», **Q** esci.

### Solo USB: nessun Wi-Fi, nessun IP da impostare

- **Non si imposta nessun indirizzo IP sul telefono**: con il tethering USB è Android a dare gli indirizzi al PC (di norma `192.168.42.x`, il telefono è `192.168.42.129`). Il Wi-Fi del telefono e del PC non è usato.
- **Per impostazione predefinita il ponte ascolta soltanto sull'adattatore USB del telefono** (quello che Windows chiama «Remote NDIS based Internet Sharing Device» o «USB NCM»; riconosciuto dal nome e dalla rete `192.168.42.x`). Wi-Fi, Ethernet aziendale e VPN del PC **non** aprono la porta e non ricevono l'annuncio. Finché il telefono non è collegato, il ponte mostra «in attesa del collegamento USB» e parte da solo appena l'adattatore compare (e si sposta se l'indirizzo cambia o il cavo viene ricollegato).
- Il ponte mostra «Indirizzo da usare nell'app» (l'IPv4 dell'adattatore USB). Se il telefono non compare tra i PC trovati, digitare quell'indirizzo.
- Se il riconoscimento dell'adattatore non funzionasse con un altro modello (nome diverso), premere **U** (o avviare con `--all-interfaces`) per ascoltare su tutte le schede; l'accesso resta protetto da TLS e codice.
- Il tethering condivide la connessione dati del telefono con il PC. Per non consumare dati mobili sul PC si possono disattivare i dati mobili del telefono: il collegamento al ponte non li usa (non verificato sul Note 9).

Il ponte e Space si avviano in qualsiasi ordine: se Space non c'è, il ponte lo cerca ogni 2 secondi (127.0.0.1, porte 5000–5019) e si ricollega da solo se Space viene chiuso e riaperto.

### Opzioni

| Opzione | Significato |
|---|---|
| `--space-url <url>` | indirizzo di Space (solo `127.0.0.1`/`localhost`); predefinito: ricerca automatica |
| `--port <n>` | porta per il telefono (predefinita 47800, poi ricordata) |
| `--poll-ms <n>` | intervallo di lettura da Space, 20–1000 ms (predefinito 100) |
| `--data-dir <cartella>` | impostazioni e certificato (predefinita `%LOCALAPPDATA%\SpaceLinkBridge`) |
| `--new-code` | genera subito un nuovo codice di accesso |
| `--all-interfaces` / `--usb-only` | ascolto su tutte le schede di rete / solo adattatore USB del telefono (predefinito, scelta ricordata) |

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

- Il servizio per il telefono ascolta **solo mentre il ponte è in esecuzione** e, per impostazione predefinita, solo sull'adattatore USB del telefono; su quella porta c'è soltanto il protocollo SFA Link (né Space né la sua interfaccia sono raggiungibili).
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
- 51 test automatici (compreso il riconoscimento dell'adattatore USB con nomi reali di Windows e l'avvio/arresto/spostamento al comparire e scomparire dell'adattatore): hub (numerazione, ripresa, `gap`, filtro, perdita di batch, cambio segnali), sicurezza (codice, blocco, certificato), gRPC + TLS con impronta fissata come sul telefono, decodifica del formato di Space, finto Space in-process (token, cursori, riavvio di Space, nomi), percorso completo Space → ponte → client gRPC.
- **Space vero** (V3.14.1) collegato a un server OPC UA di prova: il ponte ha letto 4 segnali a 10 Hz (≈40 campioni/s), sequenza contigua, timestamp corretti, ripresa senza buchi, codice errato rifiutato; provato con un client gRPC Python e con il **client Android reale** (Kotlin).
- L'exe Windows x64 si compila (47,7 MB).

**Non verificato**: esecuzione dell'exe su Windows (la pipeline lo prova alla prima esecuzione), firewall e tethering USB con un telefono reale, sorgenti diverse da OPC UA (ADS, S7, MQTT, serie calcolate: il formato dei buffer è lo stesso, ma non sono state provate), carichi elevati e prove di durata.
