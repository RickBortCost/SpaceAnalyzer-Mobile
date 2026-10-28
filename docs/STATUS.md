# Stato — 0.2.0 (28 ottobre 2026)

## Verificato sul computer di sviluppo (Linux, JDK 21, Gradle 8.13, SDK 36)
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease`: **BUILD SUCCESSFUL**.
- **24 test JUnit passati** (più 1 test di interoperabilità, saltato nell'esecuzione standard perché richiede il server Python; eseguito separatamente, vedi sotto), tra cui:
  - round trip reale **TLS + gRPC** tra il client di produzione e un server di test (abbinamento, hello, segnali, stream, ripresa, codice errato, impronta errata);
  - sessione: riconnessione con ripresa dall'ultimo `seq`, conteggio interruzioni, stop senza retry su errore fatale, registrazione che attraversa una riconnessione;
  - formato SFAR (round trip, file troncato, file estraneo, CSV con neutralizzazione di formule e separatori), beacon, decimazione grafico, buffer circolare.
- **Interoperabilità** con un server indipendente (Python/grpcio, `tools/sfa_dev_server.py`): abbinamento, autenticazione, stream, ripresa senza gap e rifiuto del codice errato — passata. Nel CI è un passo obbligatorio.
- Lint: 0 errori. Controlli strutturali Python: 6 passati (sola lettura del protocollo, nessun «accetta tutto», permessi attesi).
- APK debug `app/build/outputs/apk/debug/app-debug.apk` (versionCode 3, minSdk 29, targetSdk 36, arm64-v8a + x86_64) e release minificato non firmato (≈3,3 MB).

## Modalità prova
Dalla pagina Collega l'app può generare segnali simulati sul telefono (nessun PC). Prova interfaccia, grafico, registrazione ed esportazione, ma **non** il collegamento reale (TLS, USB, gRPC), che è coperto solo dai test automatici.

## NON verificato
- Esecuzione su telefono fisico o emulatore (nessuno disponibile nell'ambiente di sviluppo): UI, servizio in primo piano, Keystore, file picker, comportamento del tethering USB sui modelli reali.
- Il **release minificato (R8)** compila ma non è mai stato eseguito: provarlo prima di distribuirlo. È presente una regola keep per le classi del protocollo.
- Il **lato Windows**: il server in Space non esiste ancora. Il protocollo è stato validato solo contro server di test.
- Prestazioni con carichi elevati (migliaia di campioni/s), prove di durata, consumo batteria.
- Il workflow GitHub Actions della 0.2.0 è riuscito sulla PR #1 (incluso l'avvio su emulatore Android 10); i log dei singoli passi non sono stati riletti.

## Limiti noti
- Valori solo numerici (`double`); niente stringhe o strutture.
- Registrazioni in formato SFAR provvisorio; lettura dei file nativi di Space non implementata (formato non fornito).
- Il tethering USB va attivato a mano; se il cavo si scollega l'app si riconnette da sola, ma il tethering va riattivato dall'utente.
- Il servizio in primo piano di tipo `dataSync` è soggetto ai limiti di durata di Android 15+ (circa 6 h su 24 h); per acquisizioni più lunghe serve un'altra strategia.
- Nessun controllo del PC dal telefono (per scelta).
- Una sola connessione/profilo alla volta.

## Prossimi passi consigliati
1. Server SFA Link in Space (Windows) secondo `docs/PROTOCOL.md`.
2. Collaudo su telefono (piano in `docs/VALIDATION.md`), compreso il release R8.
3. Lettura dei file nativi Space e/o convertitore SFAR ↔ Space.
4. Prove di durata e di carico; valutare un'estensione dei tipi (stringhe, booleani nativi).
