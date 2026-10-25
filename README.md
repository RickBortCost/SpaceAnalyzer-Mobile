# Space Analyzer Mobile — 0.1.0 / M1

App Android nativa Kotlin + Compose per il primo passo della diagnostica Beckhoff CX2062.
**Stato: APK Debug Milestone 1 compilato il 25 ottobre 2026; 6 test JUnit passati e lint senza errori. App non ancora validata sul telefono.**
Non è ancora il prodotto OPC UA/SSH completo richiesto. Nessun dato simulato.

## Funzioni implementate nel sorgente
- Overview e Network Diagnostics: reti Ethernet, Wi-Fi, cellulare, VPN; IP, prefisso, subnet IPv4, gateway e DNS.
- Selezione della rete Ethernet e test TCP indipendenti verso endpoint OPC UA e porta SSH.
- Socket associati alla rete selezionata, timeout limitato, esecuzione su Dispatchers.IO, chiusura con `use`.
- Nessun pacchetto applicativo OPC UA/SSH, comando shell, scrittura PLC o cambio configurazione remoto.
- Un profilo persistente DataStore; validazione indirizzi/porte; tema di sistema chiaro/scuro, layout scorrevole in entrambi gli orientamenti.
- Risultati TCP marcati con ora della verifica; invalidazione cambiando rete, profilo o andando in background.
- Callback di rete rimosse quando la UI non è attiva. Nessun servizio di background.

## Versioni fissate (build eseguita con successo)
| Componente | Versione |
|---|---|
| JDK build locale / CI / bytecode | Temurin 17.0.21+9 / 17 / 17 |
| Android Build Tools | 36.0.0 |
| Gradle | 8.13 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / plugin Compose | 2.2.21 |
| Compose BOM | 2025.11.01 |
| Activity Compose | 1.12.0 |
| Lifecycle | 2.9.4 |
| DataStore Preferences | 1.1.7 |
| Coroutines | 1.10.2 |
| JUnit | 4.13.2 |
| minSdk / compileSdk / targetSdk | 29 / 36 / 36 |
| NDK predisposto | 27.2.12479018 |
| CMake predisposto in CI | 3.22.1 |

Il lint ha verificato le API rispetto a minSdk 29: `clearCapabilities()` (API 30) è stata sostituita con rimozione individuale delle capability predefinite. Dipendenze risolte e compilazione Kotlin riuscita. L'APK contiene soltanto librerie ARM64 AndroidX/DataStore, con intestazioni ELF AArch64 verificate; non contiene open62541 e non prova una compilazione JNI propria. NDK e CMake non sono stati necessari alla M1 e restano da validare per M2.

## Build su Windows
1. Installare Android Studio compatibile con AGP 8.13 e JDK 17. Impostare JDK Gradle a 17.
2. SDK Manager: Android SDK Platform 36 e Build Tools 36.0.0; per M2 anche NDK 27.2.12479018 e CMake 3.22.1.
3. Aprire questa cartella in Android Studio, che crea `local.properties` con `sdk.dir`. Non versionare quel file.
4. Il wrapper ufficiale completo è incluso, con checksum SHA-256 della distribuzione Gradle. Gli script `scripts/bootstrap-gradle.*` sono disponibili solo per rigenerarlo, non servono per la build normale.
5. Eseguire `gradlew.bat testDebugUnitTest lintDebug assembleDebug`.
6. Output: `app/build/outputs/apk/debug/app-debug.apk`.

Linux: JDK 17, SDK configurato tramite `ANDROID_HOME` o `local.properties`; `./gradlew assembleDebug`.
La prima build richiede Internet per toolchain e dipendenze. L'app non lo richiede durante l'utilizzo.

## GitHub Actions
Il workflow `.github/workflows/android.yml` installa SDK/NDK/CMake, usa il wrapper incluso, esegue test di struttura, unit test, lint e build M1, poi pubblica l'APK come artifact. Il workflow remoto non è stato eseguito; i medesimi task Gradle sono stati eseguiti localmente con successo. Gli artifact APK vengono pubblicati dal workflow al termine di una build remota riuscita.
**La pipeline non compila ancora open62541**: quel passo deve essere aggiunto e verificato nella M2, insieme a backend crittografico e JNI. Installare NDK non costituisce una verifica ARM64.

## Release privata
Impostare fuori dal repository `SAM_KEYSTORE_PATH`, `SAM_STORE_PASSWORD`, `SAM_KEY_ALIAS`, `SAM_KEY_PASSWORD`, quindi eseguire `gradlew.bat assembleRelease`. Senza queste variabili la release resta non firmata. Non condividere chiavi, password o file keystore. La debug key generata dalla toolchain serve soltanto ai test.

## Installazione Note9 e collegamento CX2062
1. Dopo una build riuscita scaricare l'artifact `SpaceAnalyzerMobile-m1-debug` da Actions, estrarre l'APK e trasferirlo sul Note9.
2. Nelle impostazioni Samsung autorizzare l'installazione da sorgenti sconosciute per l'app usata per aprire il file; installare e aprire Space Analyzer Mobile. Nessun account Play Developer richiesto.
3. Collegare USB-C/Ethernet e cavo RJ45 al CX2062; verificare la comparsa di Ethernet nella pagina Rete.
4. Se l'IPC è **già** 192.168.50.20/24, impostare il telefono a 192.168.50.10/24 dalle impostazioni Ethernet Samsung, ove presenti. Non usare un IP già assegnato. I campi dell'app non applicano la configurazione Android.
5. Se Samsung non permette IP statico, usare una rete di prova separata con DHCP appropriato. Non modificare l'IPC automaticamente e non richiedere root/ADB.
6. Salvare IP/endpoint/porte corrispondenti al dispositivo e selezionare Ethernet; avviare i test TCP 4840 e 22. Un esito positivo non autentica né valida TF6100/SSH.
7. Nessuna connessione OPC UA/SSH completa è disponibile in M1. Seguire `docs/VALIDATION.md` prima di usarla per manutenzione.

## Stato e passi successivi
Consultare `docs/STATUS.md`, `docs/VALIDATION.md` e `docs/ARCHITECTURE.md`. Il requisito originale è in `docs/REQUEST.txt`.

## Risultati build locale
`./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`: BUILD SUCCESSFUL. Sei test JUnit e cinque controlli strutturali passati. Lint: zero errori, quattro warning (versione più recente Coroutines disponibile, ABI ChromeOS assente, regole backup Android 12, icona applicazione assente). Firma APK v2 verificata. Consultare `docs/apk-verification.txt`, `docs/build-attempt.txt`, `docs/junit-results.xml` e `docs/lint-results-debug.txt`.
