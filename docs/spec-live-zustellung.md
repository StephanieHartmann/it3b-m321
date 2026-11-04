# Spezifikation — Live-Zustellung

## Zweck

Der Anzeigeweg der Chat-App: Jede Nachricht erscheint sofort bei allen, die im selben Raum verbunden sind. Jede `chat-service`-Instanz holt sich ihre eigene Kopie jeder Nachricht vom Fanout-Exchange und schiebt sie per Server-Sent Events (SSE) an ihre verbundenen Clients. Der Speicherweg aus Bewertung 1 (in die Datenbank) bleibt unverändert.

## 1. Vertrag

**Was am Exchange ankommt:** Jede neue Nachricht kommt als JSON an, mit fünf Feldern: `id` (UUID), `roomId` (UUID), `sender` (Text), `text` (Text), `sentAt` (Zeitpunkt).

**Was am Stream hinausgeht:** Jede Nachricht geht als SSE-Ereignis hinaus: `id:` trägt die Nachrichten-ID, `data:` trägt die ganze Nachricht als JSON — dasselbe Format wie in `chat.persist`.

**Woher ich das weiss:** Der `chat-service` bietet den Endpunkt `GET /stream?roomId=…` mit `Content-Type: text/event-stream` an; jedes Ereignis trägt die Nachrichten-ID als `id:` und die Nachricht als JSON in `data:`.

## 2. Verhalten

**Normalfall:** Der Client sendet eine Nachricht per `POST`. Der `chat-service` publiziert sie auf den Fanout-Exchange, der sie in die Queue **jeder** Instanz kopiert. Jede Instanz liest ihre Queue und schiebt die Nachricht per SSE an **alle Clients im selben Raum** (`roomId`) — sie erscheint sofort. Parallel wird die Nachricht wie bisher in der Datenbank gespeichert.

- **S1** — `mvn clean test` läuft in einem Lauf grün, mit echtem RabbitMQ (Testcontainers).
- **S2** — Frischer Klon mit `.env` aus `.env.example`, `docker compose up -d --build`: alle Dienste laufen, kein Dienst veröffentlicht einen Port.
- **S3** — Bei zwei Instanzen erhält jeder Client in Raum A alle 400 Nachrichten, jede genau einmal und in Absender-Reihenfolge; der Client in Raum B erhält keine. *Grund:* jede Instanz hat ihre eigene Queue (bekommt alles) und liefert nur an Clients des passenden Raums (`roomId`).
- **S4** — Von zwei auf eine Instanz: vorher zwei Queues am Fanout (je ein Consumer), danach nach spätestens 30 s nur noch eine; `chat.persist` und `chat.dlq` bleiben. *Grund:* die Instanz-Queues sind temporär (exklusiv, auto-delete) und verschwinden mit der Instanz.
- **S5** — `batch-writer` und `postgres` gestoppt, 100 Nachrichten gesendet: der Client erhält alle 100 innert 5 s; nach dem Neustart beider stehen alle 100 in der Tabelle. *Grund:* der Anzeigeweg ist unabhängig vom Speicherweg; die Nachrichten warten durable in `chat.persist`.
- **S6** — Eine von zwei Instanzen pausiert (`docker compose pause`), 5000 Nachrichten gesendet, danach fortgesetzt: ihre Queue hält nie mehr als 1000; der Client an der anderen Instanz erhält alle 5000; nach dem Fortsetzen stellt die Instanz neue Nachrichten wieder zu. *Grund:* die Queue hat ein Längenlimit (`x-max-length` 1000) und verwirft die ältesten (drop-head).
- **S7** — Client trennt nach einer Nachricht, 50 Nachrichten gesendet, nach 10 s Neuverbindung mit `Last-Event-ID`: er erhält genau die 50 fehlenden, in Reihenfolge, keine doppelt, danach wieder live. *Grund:* der Server sendet ab der letzten bestätigten ID alles Spätere nach, in Reihenfolge.
- **S8** — Der Quelltext von `chat-service` hält sich an `CLAUDE.md`: keine Streams, ein Kommentar über jeder Klasse und Methode; `.env` ist nicht im Repo.

## 3. Queues

**Instanz-Queue** (eine pro `chat-service`-Instanz):
- **Name:** anonym (vom Broker vergeben), an den Fanout-Exchange gebunden
- **Lebensdauer:** exklusiv und auto-delete — entsteht beim Verbinden der Instanz, verschwindet, wenn die Instanz weg ist
- **Grenze:** `x-max-length` 1000
- **Beim Erreichen der Grenze:** die älteste Nachricht wird verworfen (drop-head)

**`chat.persist`** (aus Bewertung 1): durable, überlebt einen Broker-Neustart; der Speicherweg in die Datenbank. Keine Längengrenze.

**`chat.dlq`** (aus Bewertung 1): durable; für Nachrichten, die sich nie speichern lassen.

*Entwurfsgedanke:* Die Instanz-Queues sind **temporär und begrenzt** (die Anzeige ist flüchtig — fehlt etwas, holt es der Client per Reconnect oder aus der Datenbank nach). Die Queues aus Bewertung 1 sind **durable** (hier darf nichts verloren gehen).

## 4. Abnahmekriterien

| Nr | Kriterium (bestanden, wenn …) | Gemessen mit |
|----|-------------------------------|--------------|
| S1 | `mvn clean test` ist grün (ein Lauf) | `mvn clean test` im Wurzelverzeichnis |
| S2 | alle Dienste laufen, kein Port veröffentlicht | `cp .env.example .env && docker compose up -d --build`, dann `docker compose ps` |
| S3 | beide Clients in Raum A erhalten alle 400 (je 1×, in Reihenfolge), Raum B keine | `--scale chat-service=2`; je ein SSE-Client pro Instanz auf Raum A, einer auf Raum B; je 200 senden; zählen |
| S4 | vorher 2 Instanz-Queues, nach ≤30 s nur 1; `chat.persist`/`chat.dlq` bleiben | `--scale chat-service=2`, `rabbitmqctl list_queues`; dann `--scale chat-service=1`, nach ≤30 s erneut prüfen |
| S5 | Client erhält 100 in ≤5 s; nach Neustart 100 in der Tabelle | `docker compose stop postgres batch-writer`, 100 senden, Zeit messen; `start`, `SELECT count(*)` |
| S6 | Instanz-Queue ≤1000, anderer Client erhält 5000, nach Fortsetzen neue zugestellt | `docker compose pause <instanz>`, 5000 senden, `list_queues`; `unpause`, erneut senden |
| S7 | nach Reconnect genau die 50 fehlenden, in Reihenfolge, keine doppelt | verbinden, 1 empfangen, trennen, 50 senden, 10 s warten, mit `Last-Event-ID` neu verbinden |
| S8 | keine Streams, Kommentar über jeder Klasse/Methode, `.env` nicht im Repo | Code-Review; `grep -r ".stream(" chat-service/src`; `git ls-files \| grep .env` |