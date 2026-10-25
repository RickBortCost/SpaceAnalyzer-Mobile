# Piano di verifica riproducibile

## Automatico
- `python3 -m unittest discover -s tests -v`: controlli strutturali della M1.
- `./gradlew testDebugUnitTest lintDebug assembleDebug`: validazione input, URI, limiti e subnet; lint e compilazione. Eseguito con successo: 6 test, lint 0 errori e APK generato.
- Verificare APK con `apkanalyzer manifest min-sdk` (29), installare su Android 10. M1 include due librerie native precompilate AndroidX/DataStore: ELF AArch64 verificati. Nessun test di caricamento su dispositivo e nessuna compilazione open62541/JNI.

## Banco Note9 / CX2062 (tutti da eseguire)
1. Avvio senza rete: nessun crash, nessuna misura simulata, TwinCAT Unknown.
2. Ethernet senza DHCP: interfaccia presente, eventuale assenza IPv4 chiaramente visibile.
3. Ethernet con IPv4: confrontare IP, subnet, gateway, DNS con impostazioni Android.
4. Tenere Wi-Fi/cellulare attivi: accertare con cattura sulla rete di prova che TCP usi Ethernet selezionata. Ripetere con due reti Ethernet, se disponibili.
5. Porta aperta, rifiutata e filtrata: risultato puntuale corretto, UI reattiva, termine entro timeout. Un servizio TCP generico basta a provare M1.
6. Scollegare cavo durante il test, ricollegare, ruotare lo schermo, mandare in background: nessun risultato vecchio presentato come connessione attiva, nessun crash o accumulo callback.
7. Salvare e riaprire profilo; invalidare IP, porte e timeout; verificare blocco input non valido.
8. Tema chiaro/scuro, font grandi, verticale/orizzontale: tutti i controlli raggiungibili.
9. Controllare assenza di payload applicativo e assenza modifiche IPC; nessuna autenticazione in M1.

## Protocolli futuri
Non applicabile alla M1: compilazione/caricamento JNI ARM64; certificati trusted/unknown/expired/hostname mismatch; conversioni tutti i tipi e UInt64; browse continuation; callback/timestamp/qualità/sequence/revised intervals; perdita sessione e backoff; rifiuto host key SSH; parser su fixture FreeBSD reali; durata e memoria limitata; revoca credenziali read-only; test delle operazioni vietate. Nessuna di queste prove è stata eseguita.
