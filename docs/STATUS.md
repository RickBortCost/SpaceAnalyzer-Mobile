# Stato consegna — 25 ottobre 2026

| Milestone | Stato effettivo |
|---|---|
| 1 Android + Ethernet | APK Debug compilato e firmato; test automatici passati; prova Note9/Ethernet da eseguire |
| 2 OPC UA reale | Non implementata; compilazione open62541/JNI ARM64 da eseguire |
| 3 SSH reale | Non implementata |
| 4 Dashboard e grafici | Solo Overview di rete |
| 5 Stabilità e distribuzione | Wrapper ufficiale e CI predisposti; build locale verificata, nessun workflow remoto eseguito |

## Build riuscita
Comando: `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`.
Temurin JDK 17.0.21+9, Gradle 8.13, AGP 8.13.2, Kotlin 2.2.21, Android SDK 36, Build Tools 36.0.0. Versioni altre dipendenze nel README.
APK: `/workspace/SpaceAnalyzer-Mobile/app/build/outputs/apk/debug/app-debug.apk`.
Firma v2 verificata; minSdk 29, targetSdk 36, ABI arm64-v8a. Le librerie native AndroidX/DataStore sono ELF AArch64. Nessuna libreria open62541.

6 test JUnit, 5 controlli strutturali passati. Lint senza errori, 4 warning dettagliati in `lint-results-debug.txt`. Il lint ha rilevato una chiamata API 30 non valida su Android 10, corretta prima della build finale. Avvisi compilatore su allNetworks deprecato; ancora disponibile sul target. NDK assente: librerie precompilate AndroidX impacchettate senza stripping; non impedisce la firma o installabilità del pacchetto.

## Problema ambiente risolto
Il proxy non era raggiungibile dai comandi senza accesso di rete. Usando i permessi di rete dell'ambiente, download Gradle/SDK/JDK riusciti. Java 21 iniziale era privo di javac: installato JDK 17 completo in work/. Cache Android e Gradle spostate in cartelle scrivibili. Wrapper ufficiale generato, distribuzione Gradle e JDK verificati con SHA-256. Nessuna credenziale salvata nel repository.

## Limiti residui
- Nessuna prova su Samsung Note9, adattatore USB, CX2062 o emulatori.
- Nessun OPC UA, subscription, certificato, SSH, diagnostica FreeBSD, monitor o grafici.
- Un profilo e tema di sistema; nessuna credenziale raccolta.
- Una richiesta TCP cancellata termina entro il timeout; non è acquisizione continua.
- Presenza Ethernet non garantisce raggiungibilità IP.
- Build non equivale a collaudo runtime o approvazione per manutenzione industriale.
- La cartella output della chat sotto /codex non esiste e non è creabile in questo executor. L'APK è disponibile nel percorso build del repository sopra indicato, non è stato pubblicato online.

## Prossimi passi
Installare l'APK sul Note9 ed eseguire il piano `VALIDATION.md`. Poi compilare open62541 cifrata per Android ARM64 e validare una variabile TF6100 reale. Proseguire con SSH a whitelist, monitor/trend, credenziali Keystore e prove di durata.
