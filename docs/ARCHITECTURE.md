# Architettura e confini di sola lettura

M1: Compose UI → MainViewModel → NetworkRepository / ProfileStore.
StateFlow con collectAsStateWithLifecycle; callbackFlow per cambi rete; DataStore per il solo profilo non sensibile. Il repository TCP riceve esclusivamente rete selezionata, IPv4 validato, porta e timeout. Non invia payload applicativo. Nessuna dipendenza di protocollo remota inclusa.

## Gate M2 (non implementato)
Il documento richiede prova di compilazione nativa Android ARM64 prima di procedere. Non introdurre JNI non verificato come funzionalità operativa. Aggiungere open62541 mantenuta con versione/commit e hash verificati, backend crittografico mantenuto e build CMake riproducibile. Esporre solo discovery, connect/disconnect, browse/BrowseNext, read e subscription. Non esporre write, call, modifica PLC o generici servizi UA.

Una singola coroutine/dispatcher deve possedere il client nativo; non condividere puntatori con Compose. Definire ownership di buffer/callback, rilascio continuation point, chiusura deterministica, conversione UInt64 senza perdita e tipizzazione array. Validare certificati e hostname/ApplicationURI; trust esplicito dell'impronta senza accept-all. SignAndEncrypt obbligatorio. Non spedire password su canali non cifrati. La strategia Ethernet JNI richiede verifica specifica: il binding dei socket Java di M1 non si trasferisce automaticamente a open62541.

## Gate M3 (non implementato)
Scegliere una libreria SSH mantenuta e provare Android 10. L'app deve offrire soltanto operazioni diagnostiche tipizzate mappate internamente a costanti, mai un metodo pubblico `execute(String)` o una shell. Host key sconosciuta: verifica esplicita prima di inviare credenziali. Host key cambiata: rifiuto e nuova verifica. Niente sudo/root, forwarding, SFTP o comandi composti con input utente. Verificare comandi e unità sulla specifica versione FreeBSD/TwinCAT/BSD; marcare indisponibile ciò che non è esposto.

## M4/M5
Repository protocolli indipendenti; subscription manager con reference counting per NodeId, interval richiesto/revisionato distinti, buffer limitati, timestamp e qualità. Render grafici indipendente dall'acquisizione. Le future credenziali devono essere cifrate con chiave Android Keystore e non conservate nel DataStore in chiaro. Stato TwinCAT Unknown finché non determinato da fonte diagnostica valida.
