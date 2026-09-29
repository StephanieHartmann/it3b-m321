# Spezifikation: batch-writer

Modul M321 · Bewertung 1. Der batch-writer ist der einzige Dienst, der in die
Tabelle `message` schreibt.

## 1. Zweck und Abgrenzung

**Zweck:** Der batch-writer holt Nachrichten aus der Queue `chat.persist` und
schreibt sie dauerhaft und gebündelt in die PostgreSQL-Tabelle `message`. Er ist
der einzige Schreiber in diese Tabelle. Ziel: auch bei hoher Last (laut PLANUNG.md
bis 1'667 Nachrichten/Sekunde) keine Nachricht verlieren und keine doppelt speichern.

**Nicht Teil dieses Dienstes:** keine REST-/Web-Schnittstelle, kein Lesen des
Verlaufs, keine Raumverwaltung, kein Keycloak, kein SSE, kein Gateway. Das gehört
zum chat-service bzw. zu späteren Phasen.

## 2. Vertrag

**Eingang — was ankommt:**
- Quelle: die dauerhafte (durable) Queue `chat.persist`, gebunden an den
  Fanout-Exchange `chat.messages`.
- Format: JSON, gekennzeichnet durch den Header `content_type: application/json`.
- Felder (identisch zum Message-Record des chat-service):
    - `id` (UUID) — vom chat-service vergeben, nicht von der Datenbank
    - `roomId` (UUID)
    - `sender` (String)
    - `text` (String)
    - `sentAt` (Zeitpunkt, ISO-8601, z. B. `2026-09-04T08:05:00Z`)
- Der batch-writer wandelt anhand des `content_type` in sein eigenes Message-Objekt
  um. Er verlässt sich NICHT auf einen Java-Typ-Header des Absenders — so kann auch
  eine Nachricht verarbeitet werden, die (wie in Szenario S5) direkt und nur mit
  `content_type: application/json` in die Queue gelegt wurde.

**Ausgang — was geschrieben wird:**
- Eine Zeile pro Nachricht in `message` (`id`, `room_id`, `sender`, `text`, `sent_at`).
- Das Einfügen ist `INSERT ... ON CONFLICT (id) DO NOTHING`: kommt dieselbe `id` ein
  zweites Mal an, entsteht keine zweite Zeile. Genau dafür vergibt der chat-service
  die `id` selbst.

## 3. Verhalten

**Normalfall:**
- Gebündeltes Lesen: bis zu 500 Nachrichten pro Paket, oder nach spätestens 200 ms
  auch ein kleineres Paket (damit einzelne Nachrichten bei wenig Verkehr nicht ewig
  warten).
- Pro Paket EIN `batchUpdate`-INSERT (mit `reWriteBatchedInserts=true`, sonst schickt
  der Treiber die Zeilen doch einzeln).
- Bestätigt (ACK) wird erst NACH erfolgreichem Datenbank-Commit. Vorher gilt die
  Nachricht als nicht verarbeitet und bleibt in der Queue.
- Prefetch mindestens so gross wie die Paketgrösse, sonst wird ein volles Paket nie
  erreicht.

**Fehlerfälle:**
- **Datenbank kurz weg (S7):** Der INSERT scheitert, es wird KEIN ACK gesendet, die
  Nachrichten bleiben in `chat.persist`. Der batch-writer versucht es weiter, bis die
  Datenbank zurück ist — ohne manuellen Neustart. Weil erst nach dem Commit bestätigt
  wird, geht nichts verloren.
- **batch-writer gestoppt (S4):** Die Nachrichten sammeln sich sichtbar in
  `chat.persist`. Nach dem Start werden sie gebündelt geschrieben — 1'000 Nachrichten
  ergeben ~2 Transaktionen, weit unter der Obergrenze von 100.
- **Doppelte Zustellung (S5, S6):** `ON CONFLICT (id) DO NOTHING` sorgt für genau eine
  Zeile. Eine gültige Nachricht landet nie in `chat.dlq`.
- **Einzelne kaputte Nachricht im Paket:** Schlägt ein ganzes Paket fehl, wird es
  einmalig Zeile für Zeile geschrieben. So werden die gesunden Nachrichten gespeichert
  und nur die tatsächlich fehlerhafte landet in `chat.dlq`.

**Zwei Instanzen (S6):** Mehrere batch-writer hören auf dieselbe Queue `chat.persist`
(competing consumers). RabbitMQ gibt jede Nachricht an genau eine Instanz — keine
Doppelten. `ON CONFLICT` bleibt als zusätzliche Absicherung.

## 4. Datenmodell und Konfiguration

**Tabelle `message`** (liegt in `db/01-schema.sql`):
- `id UUID PRIMARY KEY` — Grundlage für `ON CONFLICT (id)`.
- `room_id UUID`, `sender VARCHAR(100)`, `text TEXT`, `sent_at TIMESTAMPTZ`.
- Index `idx_message_room_time (room_id, sent_at DESC)` — fürs Lesen; der
  batch-writer selbst schreibt nur.

**Queues:**
- `chat.persist` — durable, an `chat.messages` gebunden (Deklaration unverändert wie
  im chat-service; kein `x-delivery-limit`, keine Quorum-Queue).
- `chat.dlq` — einfache durable Queue. Nachrichten, die sich wegen ihrer Daten nie
  speichern lassen, legt der batch-writer über den Einzelweg-Fallback im Code selbst
  dorthin. Ist die Datenbank nicht erreichbar, landet nichts in `chat.dlq`.

**Konfiguration (alle Werte aus Umgebungsvariablen; `.env.example` im Repo, `.env`
NICHT):**
- `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` — Datenbankzugang.
- Datenbank intern: `postgres:5432` (Netz `chat-net`).
- RabbitMQ: Host `rabbitmq`, Benutzer/Passwort aus Umgebungsvariablen.
- Paketgrösse (500) und Zeitlimit (200 ms) als Startwerte, unter Last nachmessbar.

## 5. Abnahmekriterien (messbar)

- **S1:** `mvn clean test` im Wurzelverzeichnis läuft grün durch.
- **S2:** Frischer Klon, `.env` aus `.env.example`, `docker compose up -d --build` —
  alle Dienste laufen, kein Dienst veröffentlicht einen Port.
- **S3:** 1'000 über `POST /api/messages` gesendete Nachrichten stehen nach spätestens
  60 s alle in `message`; `chat.persist` ist danach leer.
- **S4:** batch-writer gestoppt, 1'000 gesendet, wieder gestartet — nichts verloren,
  höchstens 100 Transaktionen.
- **S5:** Eine Nachricht direkt in `chat.persist` (nur `content_type: application/json`)
  erzeugt genau eine Zeile; `chat.dlq` bleibt leer.
- **S6:** Zwei Instanzen (`--scale batch-writer=2`), 1'000 Nachrichten — alle da, keine
  doppelt.
- **S7:** Postgres 15 s gestoppt, 300 gesendet, wieder gestartet — nach spätestens 90 s
  sind alle 300 gespeichert, ohne den batch-writer neu zu starten.
- **S8:** Der Quelltext folgt CLAUDE.md, `.env` ist nicht im Repo.