# Space Fieldbus Analyzer Mobile — 0.2.0

App Android nativa (Kotlin + Compose) che si collega via USB al PC con Space Fieldbus Analyzer, **visualizza i segnali in tempo reale e li registra sul telefono**. L'acquisizione (OPC UA, MQTT, bus di campo) resta sul PC; il telefono è un client in sola ricezione.

**Stato:** prima release. Client, protocollo, registrazione e UI sono compilati e coperti da test automatici sul computer di sviluppo. **Non è ancora stata provata su un telefono fisico né con il lato Windows di Space** (che deve implementare il server descritto in `docs/PROTOCOL.md`). Vedere `docs/STATUS.md`.

## Funzioni
- **Collegamento** al PC su TCP: gRPC (HTTP/2 + Protobuf) con TLS e certificato fissato dall'utente (nessuna CA, nessun «accetta tutto»), codice di accesso nel Keystore Android, ricerca automatica dei PC tramite beacon UDP.
- **Live**: valori, qualità, indicazione di dato obsoleto (> 5 s), grafico multi-segnale con finestra 10 s–5 min, pausa, decimazione min/max per pixel.
- **Registrazione** locale in formato SFAR (append-only, tollerante a spegnimenti improvvisi, flush ogni secondo), esportazione dell'originale o in CSV tramite il selettore file di Android (nessun permesso di archiviazione).
- **Riconnessione** automatica con backoff e ripresa dall'ultimo numero di sequenza; le interruzioni non recuperabili sono contate e mostrate.
- **Servizio in primo piano** (notifica) per continuare ricezione e registrazione a schermo spento.
- **Rete**: interfacce del telefono, utile per verificare il tethering USB.
- Sola lettura: il protocollo non contiene alcuna operazione di scrittura o comando verso il PC.

## Uso
1. Collegare telefono e PC con il cavo USB e attivare **Tethering USB** (Impostazioni → Connessioni → Hotspot e tethering). Android non permette all'app di attivarlo.
2. In Space avviare il servizio di collegamento mobile: mostra codice di accesso e impronta del certificato.
3. Nell'app: pagina **Collega** → scegliere il PC trovato (o digitare l'IPv4), inserire il codice → **Salva** → **Verifica PC** → confrontare l'impronta con quella del PC → **Coincide**.
4. **Connetti**. Pagina **Live** per i segnali, **Registra** per salvare; **Registrazioni** per esportare.

Senza il lato Windows si può provare l'app con `tools/sfa_dev_server.py` (segnali sintetici, solo per test, mai incluso nell'APK).

## Build
Requisiti: JDK 17+ (bytecode 17), Android SDK 36 con Build Tools 36.0.0. Nessun NDK.

```
./gradlew testDebugUnitTest lintDebug assembleDebug   # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease                              # APK minificato (R8), non firmato senza chiave
python3 -m unittest discover -s tests                  # controlli strutturali
```
Release firmata: impostare fuori dal repository `SAM_KEYSTORE_PATH`, `SAM_STORE_PASSWORD`, `SAM_KEY_ALIAS`, `SAM_KEY_PASSWORD`. Non versionare chiavi.

Test di interoperabilità con un server indipendente (Python):
```
pip install grpcio grpcio-tools
python3 tools/sfa_dev_server.py --code 123456 --port 47801
SFA_INTEROP_PORT=47801 SFA_INTEROP_CODE=123456 ./gradlew testDebugUnitTest --tests '*InteropTest'
```

## Versioni
JDK build 17+ / bytecode 17 · Gradle 8.13 · AGP 8.13.2 · Kotlin 2.2.21 · Compose BOM 2025.11.01 · gRPC 1.85.1 (okhttp, lite) · protobuf-javalite/protoc 4.28.3 · plugin protobuf 0.9.5 · DataStore 1.1.7 · Coroutines 1.10.2 · minSdk 29 · targetSdk/compileSdk 36 · ABI arm64-v8a, x86_64.

## Documentazione
`docs/PROTOCOL.md` (specifica per il lato PC) · `docs/STATUS.md` · `docs/VALIDATION.md` · `docs/ARCHITECTURE.md`. Il progetto precedente (diagnostica Beckhoff con OPC UA/SSH) è documentato in `docs/legacy-beckhoff-request.txt` e non è parte di questa release.
