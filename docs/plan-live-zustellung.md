# Umsetzungsplan: Live-Zustellung

Modul M321 · Bewertung 2. Baut auf docs/spec-live-zustellung.md auf. Reihenfolge:
erst der einfachste lauffähige Anzeigeweg, dann Stück für Stück Reconnect-Korrektheit
und Härte. Jede Aufgabe endet mit einem Commit mit kurzer Begründung.

1. **SSE-Endpunkt** — `StreamController` mit `GET /stream?roomId=…`
   (`produces = text/event-stream`), der einen `SseEmitter` ohne Zeitlimit offen hält;
   `SseConnectionRegistry` merkt sich die offenen Verbindungen je Raum
   (`add` / `remove` / `findConnections`). Beim Verbinden ein SSE-Kommentar
   `:verbunden`, `onCompletion` trägt die Verbindung wieder aus.
   *Warum zuerst:* ohne eine Verbindung, die sich öffnet und offen bleibt, lässt sich
   keine einzige Nachricht zustellen oder prüfen (Content-Type, `:verbunden`).

2. **Instanz-Queue und Consumer** — im `RabbitConfiguration` eine `AnonymousQueue`
   (exklusiv, auto-delete) an den Fanout-Exchange `chat.messages` binden, mit
   `x-max-length` 1000 und `x-overflow: drop-head`; `Jackson2JsonMessageConverter`,
   damit die Nachricht als JSON ankommt. `LiveMessageConsumer` mit
   `@RabbitListener(queues = "#{liveQueue.name}")` empfängt jede Nachricht als
   `Message`-Objekt.
   *Warum danach:* jetzt kommt jede Nachricht genau einmal bei der Instanz an – der
   Weg Exchange → eigene Queue → Consumer ist nachweisbar, bevor die Zustellung
   dazukommt. Die Grenze gehört zur Queue selbst: sie schützt die flüchtige Anzeige,
   ohne den über die Datenbank gesicherten Verlauf zu gefährden (S4: Queue verschwindet
   mit der Instanz; S6: höchstens 1000, älteste zuerst weg).

3. **Zustellung an die Clients des Raums** — `MessageEventSender` baut das SSE-Ereignis
   (`id:` = Nachrichten-ID, `data:` = JSON) und sendet es; der `LiveMessageConsumer`
   holt sich `findConnections(roomId)` und sendet an jede Verbindung. Scheitert ein
   Senden, wird die Verbindung ausgetragen, ohne die anderen aufzuhalten.
   *Warum hier:* damit steht der ganze Anzeigeweg Ende-zu-Ende (senden → Queue →
   Consumer → Browser) – der Normalfall der Spezifikation, prüfbar mit zwei Clients im
   selben Raum und einem in einem anderen (S3).

4. **Nachholen bei Neuverbindung** — den Header `Last-Event-ID` im `StreamController`
   auswerten; ist er gesetzt, holt `MessageCatchUp` die verpassten Nachrichten aus der
   Datenbank (`MessageRepository.findAfter`, sortiert nach `sent_at`, dann `id`) und
   schaltet erst danach auf live um. Live-Nachrichten, die während des Nachholens
   ankommen, sammelt die Registry zwischen, damit nichts überholt oder doppelt kommt.
   Fällt die Datenbank aus, endet die Antwort normal, damit der Client neu verbindet.
   *Warum danach:* Reconnect ist die schwierigste Korrektheitsfrage (Reihenfolge, keine
   Lücke, keine Dublette) und baut auf dem funktionierenden Live-Weg auf (S7).

5. **Tests** — mit Testcontainers (echtes RabbitMQ) mindestens ein Test für die
   Verteilung an zwei Instanz-Queues (S3) und einen für den Reconnect mit
   `Last-Event-ID` (S7); dazu Unit-Tests für Registry und Nachhol-Logik.
   `mvn clean test` grün (S1).
   *Warum jetzt:* getestet wird, was es schon gibt.

6. **Abgabe feinschleifen** — `chat-service` ohne `container_name` (damit
   `docker compose up -d --scale chat-service=2` läuft), neue Variablen in
   `.env.example`, README-Abschnitt „Stand" ergänzen; danach S1 bis S8 der Reihe nach
   durchspielen und Abweichungen beheben.
   *Warum am Ende:* prüft das Ganze gegen die Abnahmekriterien der Spezifikation und
   macht den frischen Klon-Start (S2) sauber.