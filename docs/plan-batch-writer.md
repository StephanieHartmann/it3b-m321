# Umsetzungsplan: batch-writer

Modul M321 · Bewertung 1. Baut auf docs/spec-batch-writer.md auf. Reihenfolge:
erst der einfachste lauffähige Weg, dann Stück für Stück Härte und Effizienz.
Jede Aufgabe endet mit einem Commit.

1. **Grundgerüst** — eigenständiges Maven-Modul (Spring Boot ohne Web-Starter),
   verbindet sich mit PostgreSQL und RabbitMQ, startet sauber.
   *Warum zuerst:* ohne startenden Dienst mit beiden Verbindungen kann nichts
   Weiteres getestet werden.

2. **Einzeln schreiben** — `@RabbitListener` auf `chat.persist`, jede Nachricht per
   `INSERT ... ON CONFLICT (id) DO NOTHING` einzeln in `message`. ACK erst nach dem
   Commit.
   *Warum vor dem Bündeln:* so ist der ganze Weg (senden → Queue → schreiben) einmal
   Ende-zu-Ende beweisbar, bevor die Bündel-Logik dazukommt. Ein Fehler lässt sich
   dann eindeutig einer Stufe zuordnen.

3. **Auf Bündeln umstellen** — gebündeltes Lesen (500 / 200 ms),
   `JdbcTemplate.batchUpdate`, `reWriteBatchedInserts=true`, prefetch ≥ Paketgrösse.
   *Warum danach:* der Vorher-Nachher-Vergleich ist der Kern der Aufgabe (S4:
   höchstens 100 Transaktionen).

4. **chat.dlq und Fehlerbehandlung** — Dead-Letter-Queue `chat.dlq`, `chat.persist`
   mit `x-delivery-limit: 3`; schlägt ein Paket fehl, einmalig Zeile für Zeile
   schreiben (Einzelweg-Fallback).
   *Warum hier:* Fehlerwege lassen sich erst sinnvoll bauen, wenn der Normalfall steht.

5. **Tests** — mindestens ein Test für das Duplikat (S5) und einen für den
   Datenbankausfall (S7); `mvn test` grün.
   *Warum jetzt:* getestet wird, was es schon gibt.

6. **Containerisierung** — Dockerfile für batch-writer und chat-service, vollständige
   `docker-compose.yml` (alle Dienste in `chat-net`, kein veröffentlichter Port,
   Zugangsdaten aus `.env`).
   *Warum spät:* während der Entwicklung ist der Start aus der IDE schneller (kein
   Rebuild pro Änderung); die Regel „kein offener Port" wird zum Schluss festgezogen.

7. **Alle acht Szenarien durchspielen und feinschleifen** — S1 bis S8 der Reihe nach
   ausführen und Abweichungen beheben.
   *Warum am Ende:* prüft das Ganze gegen die Abnahmekriterien der Spezifikation.