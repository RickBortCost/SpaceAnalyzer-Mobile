# Architettura 0.2.0

```
Space (PC)  ──USB tethering / TCP──  gRPC + TLS (pin)  ──►  SfaLinkClient ─► LinkSession ─► Ring buffer (grafici)
                                                                                       ├─► StateFlow ultimi valori (UI)
                                                                                       └─► SfarWriter (registrazione)
```
- `link/`: `SfaLinkClient` (canale okhttp, TLS con `PinnedTrustManager`, ping keepalive, codice di accesso come header), `LinkSession` (riconnessione con backoff, ripresa per `seq`, stato, statistiche, registrazione), `PinnedTls` (impronta, abbinamento), `Discovery` (beacon UDP).
- `record/`: formato SFAR e conversione CSV.
- `settings/`: profilo in DataStore; codice di accesso cifrato con chiave AES-GCM nell'Android Keystore.
- `service/LinkService`: servizio in primo piano che tiene vivo il processo; non possiede la connessione (vive in `SfaApp`, scope applicativo).
- `ui/`, `viewmodel/`: Compose + MVVM; il grafico decima a min/max per colonna di pixel.
- `networking/`: elenco reti Android (diagnostica del tethering).

## Confini di sicurezza
- Il servizio gRPC (`sfa_link.proto`) espone solo `Hello`, `ListSignals`, `Subscribe`; un test strutturale lo impone.
- La fiducia nel PC deriva dalla conferma esplicita dell'impronta; nessuna validazione CA, nessun «accetta tutto» nel canale dati. L'unico trust manager permissivo è la sonda di abbinamento, che legge il certificato e chiude senza inviare dati.
- Il codice di accesso non è in chiaro su disco, non è nei messaggi e viaggia solo dentro TLS.
- I nomi dei segnali provengono dal PC: in CSV vengono neutralizzati (prefisso formule, separatori, virgolette).
- Nessun permesso di archiviazione: l'esportazione usa il selettore file di sistema.
