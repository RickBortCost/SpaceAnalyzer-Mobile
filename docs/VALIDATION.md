# Piano di verifica

## Automatico (eseguito, vedere STATUS.md)
- `python3 -m unittest discover -s tests`
- `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease`
- Interoperabilità: `tools/sfa_dev_server.py` + `InteropTest` (passo del CI).
- `scripts/test-android10-install.sh` (CI, emulatore API 29): installa l'APK distribuito, lo avvia e controlla la schermata principale. Non eseguito qui.

## Collaudo su telefono (da eseguire)
Preparazione: `tools/sfa_dev_server.py` sul PC (o il server di Space), tethering USB attivo.
1. Installazione e avvio su Android 10 e su un Android recente (13+); richiesta permesso notifiche.
2. Beacon: il PC compare in «PC trovati» entro ~5 s. Digitando l'IPv4 a mano funziona ugualmente.
3. Abbinamento: l'impronta mostrata coincide con quella del server; con impronta diversa o certificato rigenerato il collegamento è rifiutato con messaggio chiaro. Cambiando indirizzo l'impronta confermata viene azzerata.
4. Codice errato → errore fisso, nessun tentativo ripetuto. Codice corretto → segnali e valori.
5. Live: valori che si aggiornano, grafico fluido con 1–4 segnali, finestre 10 s–5 min, pausa/ripresa. Spegnere il server: dopo ≤ ~15 s i valori diventano «obsoleti» e lo stato passa a «Riconnessione».
6. Scollegare e ricollegare il cavo durante la ricezione: riconnessione automatica (previa riattivazione del tethering) e ripresa senza gap entro il buffer del server; con buffer superato, l'interruzione viene contata.
7. Registrazione: avviare, bloccare lo schermo per qualche minuto, riaprire: la registrazione prosegue (notifica visibile). Stop; esportare SFAR e CSV; aprire il CSV in un foglio di calcolo (nessuna formula eseguita).
8. Terminare l'app a forza durante una registrazione: il file si legge fino all'ultimo secondo circa.
9. Rotazione, tema chiaro/scuro, font grandi, app in background e ritorno.
10. APK release minificato: ripetere 3–7 (verifica che R8 non rompa protobuf/gRPC).
11. Prova di durata (≥ 1 h a regime, stesso carico previsto) con controllo di memoria e batteria.
