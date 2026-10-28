# SFA Link v1 — specifica per il lato PC (Windows)

Il PC è il **server**, il telefono il **client**. Sola lettura: il servizio non ha operazioni che modificano il PC, i segnali o l'acquisizione. Definizione normativa: `app/src/main/proto/sfa_link.proto`.

## Trasporto
- **gRPC su HTTP/2 con TLS**, porta predefinita **47800**. Nessun testo in chiaro.
- Percorso fisico: USB tethering (RNDIS/NCM). Il telefono è il gateway della rete di tethering (tipicamente 192.168.42.x), il PC ha un indirizzo in quella subnet. Lo stesso protocollo funziona su Wi-Fi.
- **Certificato**: il server usa un certificato proprio (self-signed va bene, validità consigliata ≥ 1 anno). Il client **non** usa catene CA: si fida solo del certificato la cui impronta SHA-256 (DER, esadecimale maiuscolo separato da `:`) è stata confermata dall'utente. L'app mostra l'impronta durante l'abbinamento; Space deve mostrare la stessa all'utente. Cambiare certificato richiede un nuovo abbinamento. Il nome host non è verificato (si usa l'IPv4 letterale).
- **Keepalive**: il client invia ping HTTP/2 ogni 10 s (timeout 5 s) anche senza chiamate attive, per rilevare un cavo scollegato. Il server deve accettarli (.NET Kestrel: `KeepAliveInterval`/`KeepAliveTimeout` e impostazioni HTTP/2; grpc-core: `grpc.http2.min_ping_interval_without_data_ms ≤ 5000`, `grpc.keepalive_permit_without_calls=1`), altrimenti risponderebbe `GOAWAY too_many_pings`.

## Autenticazione
Il server genera un **codice di accesso** (consigliato ≥ 6 cifre casuali, rinnovabile) e lo mostra con l'impronta. Il client lo invia nell'header gRPC `x-sfa-access-code` di ogni chiamata. Codice errato → `UNAUTHENTICATED` (il client non riprova). Il server deve limitare i tentativi falliti.

## Servizi
| RPC | Descrizione |
|---|---|
| `Hello` | Il client invia `protocol_version = 1`. Il server risponde con la propria versione (`1`), nome del PC e nome dell'acquisizione. Versione diversa: il client si ferma con errore. |
| `ListSignals` | Elenco dei segnali: `id` (uint32, stabile per tutta la sessione), nome, unità, tipo (informativo; i valori sono sempre `double`). |
| `Subscribe` | Stream di `SampleBatch`. `signal_ids` vuoto = tutti. `resume_after_seq` = 0 per solo live. |

### Batch, sequenza e ripresa
- Ogni `SampleBatch` ha `seq` strettamente crescente di 1 (per sottoscrizione; il contatore è del server e sopravvive alle riconnessioni del client).
- Alla riconnessione il client invia `resume_after_seq = ultimo seq ricevuto`. Il server rispedisce da `seq + 1` se ha ancora i batch (buffer circolare consigliato: almeno 5 minuti di dati).
- Se non li ha più (buffer superato o riavvio del server con contatore azzerato) deve impostare **`gap = true`** sul primo batch inviato. Il client conta l'interruzione e prosegue dal nuovo `seq`.
- Raggruppare più campioni per batch (consigliato 20–100 ms di dati) per ridurre il carico; nessun limite rigido, ma restare sotto ~1 MB per batch.
- `Sample.timestamp_us`: microsecondi dall'epoca Unix, **assegnato dal PC** (il telefono non lo sostituisce mai). `quality`: 0 = buono; altro = codice del server (lo stato OPC UA, se disponibile).
- Controllo di flusso: gRPC lo fornisce; un client lento non deve bloccare l'acquisizione sul PC (usare buffer limitati per sottoscrizione e scartare/segnalare con `gap`).

## Rilevamento del PC (opzionale)
Beacon **UDP broadcast** ogni ~2 s sulla porta **47810** verso `255.255.255.255` con payload JSON UTF-8 ≤ 512 byte:
`{"sfa":1,"name":"PC-Laboratorio","port":47800}`.
È solo un suggerimento per precompilare indirizzo e porta (l'indirizzo è quello sorgente del pacchetto): la fiducia deriva dall'abbinamento. Il client accetta solo sorgenti IPv4 private o link-local.

## Schema .NET (indicativo)
Pacchetti `Grpc.AspNetCore`, `Google.Protobuf`, `Grpc.Tools`; il progetto include `sfa_link.proto` con `GrpcServices="Server"`. Kestrel con `ListenAnyIP(47800, o => { o.Protocols = Http2; o.UseHttps(cert); })`. Un `ServerInterceptor` verifica `x-sfa-access-code`. L'impronta si calcola con `Convert.ToHexString(SHA256.HashData(cert.RawData))`.

## File di registrazione SFAR v1
Prodotto dal telefono (big-endian): `"SFAR"` | `u8 versione=1` | `u32 lunghezza intestazione` | intestazione JSON UTF-8 (`acquisition`, `startedAtMs`, `signals[{id,name,unit}]`) | record da 24 byte: `u32 signalId | i64 timestampUs | f64 valore | u32 qualità`. L'ultimo record parziale di un file interrotto va ignorato. Il formato nativo dei file Space non è stato fornito: SFAR è un formato provvisorio e un convertitore verso il formato Space è uno sviluppo futuro.
