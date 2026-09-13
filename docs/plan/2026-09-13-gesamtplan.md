# Chat-App — Gesamtplan

**Ziel dieses Dokuments:** Die Umsetzung des ganzen Projekts in eine feste Reihenfolge bringen —
von der Infrastruktur bis zum Desktop-Client. Jede Phase steht für sich, baut aber auf der
vorherigen auf. Für Phasen, die schon bis auf Code-Ebene ausgearbeitet sind, verweist dieser Plan
auf den jeweiligen Detailplan statt den Inhalt zu wiederholen. Für die übrigen Phasen steht hier
das, was zu bauen ist, warum an dieser Stelle, und woran man erkennt, dass die Phase fertig ist —
die Detailpläne dazu (im Stil von `2026-09-04-chat-service-bootstrap.md`, testgetrieben, mit
Code) entstehen jeweils kurz bevor die Phase drankommt.

**Spec:** `PLANUNG.md` — jede Phase verweist auf den passenden Abschnitt.

**Globale Vorgaben** (gelten für den ganzen Verlauf, Details siehe `CLAUDE.md` und den
Bootstrap-Plan): Java 21 · Spring Boot 3.5.16 · Code auf Englisch, Kommentare/Logs/Fehlermeldungen
auf Deutsch · eine Anweisung pro Zeile, keine Stream-Ketten, kein Lombok, keine Annotation-Magie ·
kein Eltern-POM, jeder Dienst eigenständig baubar.

---

## Reihenfolge auf einen Blick

| # | Phase | Abschnitt in PLANUNG.md | Detailplan |
|---|---|---|---|
| 1 | Infrastruktur: PostgreSQL + RabbitMQ in docker-compose | 2.6, 3 | `2026-09-04-chat-service-bootstrap.md`, Task 2 |
| 2 | Keycloak-Realm anlegen und exportieren | 2.5 | noch zu erstellen |
| 3 | `chat-service` Grundgerüst: senden + lesen | 2.1, 2.2, 3 | `2026-09-04-chat-service-bootstrap.md`, Task 1, 3, 4 |
| 4 | `batch-service`: zuerst einzeln schreiben | 2.1, 2.2 | noch zu erstellen |
| 5 | `batch-service`: auf Bündeln umstellen und messen | 2.3 | noch zu erstellen |
| 6 | Raumverwaltung | 2.1, 3 | noch zu erstellen |
| 7 | Keycloak-Integration im `chat-service` | 2.5 | noch zu erstellen |
| 8 | SSE-Endpunkt und React-Oberfläche | 2.1, 2.2 | noch zu erstellen |
| 9 | Queue-Regeln: Quorum-Queue, DLQ, TTL/Längenbegrenzung | 2.4 | noch zu erstellen |
| 10 | Zeitgesteuerte Aufgaben im `batch-service` | 2.1 | noch zu erstellen |
| 11 | Gateway (nginx) und vollständiges docker-compose | 2.1, 2.6 | noch zu erstellen |
| 12 | JavaFX-Client | 1, 2.5 | noch zu erstellen |

Jede Phase endet mit einem Commit. Am Ende jeder Phase steht ausserdem, welche **offenen Punkte**
aus Abschnitt 4 der Planung dort mit erledigt werden — so bleibt nachvollziehbar, wann welcher
offene Punkt geschlossen wurde.

---

## Phase 1: Infrastruktur — PostgreSQL und RabbitMQ in docker-compose

**Ziel:** Eine erreichbare Datenbank `chat` mit den Tabellen `room`, `room_member`, `message`
samt Demo-Daten, und ein laufender RabbitMQ-Broker.

**Warum zuerst:** Ohne laufende Datenbank und Broker lässt sich kein Java-Code sinnvoll gegen
etwas Reales testen. Alles Weitere baut auf diesen beiden Containern auf.

**Was entsteht:**
- `docker-compose.yml` mit den Services `postgres` und `rabbitmq`, Netzwerk `chat-net`
- `db/01-schema.sql`, `db/02-demo-data.sql`

**Bewusste Abweichung von der Vorgabe an dieser Stelle:** `chat-service` läuft in dieser Phase
noch aus der IDE, nicht im Compose. Die Ports von Postgres und RabbitMQ sind deshalb vorübergehend
auf den Host veröffentlicht (`ports:` statt `expose:`). Das wird in Phase 11 zurückgebaut, sobald
`chat-service` selbst im Compose läuft — dann gilt die Regel „nur Port 8080 offen" wieder
vollständig.

**Detailplan:** vollständig ausgearbeitet in `2026-09-04-chat-service-bootstrap.md`, Task 2.

**Fertig, wenn:**
- [ ] `docker compose up -d` startet beide Container fehlerfrei
- [ ] `\dt` in `psql` zeigt `room`, `room_member`, `message`
- [ ] Die drei Demo-Nachrichten stehen in der Tabelle `message`
- [ ] RabbitMQ Management-UI unter `localhost:15672` erreichbar

---

## Phase 2: Keycloak-Realm anlegen und exportieren

**Ziel:** Ein Realm `chat` mit mindestens einem Testnutzer, als JSON exportiert, damit er beim
Container-Start automatisch importiert wird.

**Warum an dieser Stelle:** Der Realm muss existieren, bevor irgendein Client oder Service echte
Tokens verwenden kann. Die tatsächliche Verdrahtung (Token prüfen, `sender` aus dem Token) kommt
erst in Phase 7 — bis dahin entwickelt `chat-service` bewusst ohne Login-Zwang, mit einem
Platzhalter-Feld für den Absender.

**Was entsteht:**
- Neuer Service `keycloak` in `docker-compose.yml` (`expose`, kein `ports` — Zugriff später nur
  über das Gateway, siehe Phase 11; für die manuelle Einrichtung in dieser Phase vorübergehend mit
  `ports` erreichbar)
- Realm-Export (JSON) unter `keycloak/import/` oder ähnlich, der beim Start automatisch
  eingelesen wird
- Mindestens ein Testnutzer (z. B. `lernende1`, Passwort zum Ausprobieren)

**Bezug zu Abschnitt 4 (offene Punkte), der hier vorbereitet, aber noch nicht abgeschlossen wird:**
- **Punkt 1 — Issuer hinter dem Proxy.** `KC_HOSTNAME` muss auf die später öffentlich sichtbare
  URL (`localhost:8080/auth`) gesetzt werden, nicht auf den internen Servicenamen. Vollständig
  **testbar** ist das erst, wenn das Gateway in Phase 11 steht — hier wird die Umgebungsvariable
  aber schon korrekt gesetzt, damit es später nicht vergessen geht.
- **Punkt 11 — Rollen** (`user`, `moderator`): im Realm anlegen, auch wenn sie erst in Phase 6/7
  wirklich benutzt werden.

**Fertig, wenn:**
- [ ] Keycloak-Admin-Konsole erreichbar, Realm `chat` ist vorhanden
- [ ] Login mit dem Testnutzer funktioniert im Browser
- [ ] Ein Access-Token lässt sich manuell holen (z. B. über die Token-Endpunkt-URL mit `curl`)
- [ ] Der Realm-Export liegt im Repo und importiert sich bei `docker compose up -d` auf einem
      leeren Volume automatisch

---

## Phase 3: `chat-service` Grundgerüst — Nachrichten senden und lesen

**Ziel:** `POST /api/messages` publiziert eine Nachricht auf den Fanout-Exchange `chat.messages`,
`GET /api/messages` liest den Verlauf aus PostgreSQL. Beides dokumentiert in Swagger.

**Warum ohne Consumer:** So wird sichtbar, dass Senden und Lesen zwei getrennte Wege sind — eine
gesendete Nachricht landet im Broker, aber noch nicht in der Datenbank. Genau diese Lücke schliesst
später der `batch-service` (Phase 4/5). Der Absender kommt in dieser Phase noch als Feld im
Request-Body — ausdrücklich als Platzhalter in der Swagger-Doku markiert, bis Keycloak angebunden
ist (Phase 7).

**Was entsteht:**
- `chat-service/` als eigenständiges Maven-Projekt
- Paket `message`: `Message`, `NewMessage`, `MessageRepository`, `MessageService`,
  `MessageController`
- Paket `rabbit`: `RabbitConfiguration` (Exchange-Bean, JSON-Konverter)
- Tests: Kontext-Test, `MessageControllerTest` (Web-Schicht isoliert, ohne DB/Broker)

**Detailplan:** vollständig ausgearbeitet in `2026-09-04-chat-service-bootstrap.md`, Task 1, 3
und 4 — testgetrieben, inklusive einer kleinen Übungsaufgabe für die Klasse.

**Fertig, wenn:** siehe „Fertig, wenn …" am Ende von `2026-09-04-chat-service-bootstrap.md`
(u. a. vier grüne Tests, beide Endpunkte in Swagger, Nachricht sichtbar im Broker).

---

## Phase 4: `batch-service` — zuerst einzeln schreiben

**Ziel:** Ein neuer, eigenständiger Dienst ohne Web-Oberfläche, der über `@RabbitListener` auf
`chat.persist` hört und jede ankommende Nachricht **einzeln** per `INSERT` in die Tabelle
`message` schreibt.

**Warum zuerst einzeln, nicht gleich gebündelt:** Damit ist der komplette Weg vom Senden bis zur
Datenbank ein erstes Mal Ende-zu-Ende sichtbar und beweisbar, bevor die kompliziertere
Bündel-Logik dazukommt (PLANUNG.md, „Nächste Schritte", Punkt 4/5 — diese Reihenfolge ist dort
ausdrücklich als Absicht festgehalten). Ein Fehler lässt sich damit eindeutig einer von zwei
Ausbaustufen zuordnen.

**Was entsteht:**
- `batch-service/` als eigenständiges Maven-Projekt (Spring Boot ohne Web-Starter)
- Ein `@RabbitListener` auf Queue `chat.persist` (Queue muss zu diesem Zeitpunkt neu angelegt und
  an `chat.messages` gebunden werden — bisher hing dort noch keine dauerhafte Queue)
- Ein Repository mit einer einfachen `INSERT ... ON CONFLICT (id) DO NOTHING`-Methode

**Bezug zu Abschnitt 3 (Datenmodell):** Hier zeigt sich zum ersten Mal praktisch, warum die UUID
vom `chat-service` und nicht von der Datenbank vergeben wird — nur so ist `ON CONFLICT DO NOTHING`
bei einer erneuten Zustellung überhaupt möglich.

**Fertig, wenn:**
- [ ] Eine über `POST /api/messages` gesendete Nachricht erscheint kurz danach in `GET
      /api/messages`
- [ ] Erneutes Zustellen derselben Nachricht (z. B. manuell über die Management-UI wiederholt)
      erzeugt **keine** doppelte Zeile in der Datenbank

---

## Phase 5: `batch-service` — auf Bündeln umstellen und messen

**Ziel:** Aus Einzel-Inserts wird ein Bündel-Schreiber: `setConsumerBatchEnabled(true)`,
`setBatchSize(500)`, `setReceiveTimeout(200)`, Schreiben über `JdbcTemplate.batchUpdate(...)`.

**Warum als eigener Schritt danach:** Der Vorher-Nachher-Vergleich zu Phase 4 ist die Lehrstunde
aus Abschnitt 2.3 — ohne die Einzel-Fassung vorher gäbe es nichts zum Vergleichen.

**Was sich ändert:**
- Listener-Methode nimmt `List<Message>` statt einer einzelnen Nachricht
- `reWriteBatchedInserts=true` in der JDBC-URL (sonst schickt der Treiber die Zeilen trotzdem
  einzeln)
- `prefetch` auf mindestens 500 setzen (sonst wird die Paketgrösse nie erreicht — der in
  Abschnitt 2.3 genannte „klassische Anfängerfehler")
- ACK erst nach dem Datenbank-Commit

**Bezug zu Abschnitt 4 (offene Punkte), die hier zu bearbeiten sind:**
- **Punkt 3 — Paketgrösse und Zeitlimit sind geraten.** 500/200 ms als Startwerte übernehmen,
  dann unter künstlicher Last (z. B. ein kleines Lastskript) Queue-Länge und Schreibdauer
  gegeneinander auftragen. Ergebnis der Messung im Detailplan dieser Phase festhalten.
- **Punkt 4 — Einzelweg nach fehlgeschlagenem Paket.** Schlägt ein Batch fehl, das Paket einmalig
  Zeile für Zeile schreiben, damit nur die tatsächlich kaputte Nachricht in der DLQ landet
  (die DLQ selbst kommt erst in Phase 9 — hier reicht die Fallback-Logik im Code, testbar mit
  einer bewusst fehlerhaften Nachricht).

**Fertig, wenn:**
- [ ] Bei mehreren schnell gesendeten Nachrichten zeigt das Log genau **einen** `batchUpdate`-Aufruf
      statt vieler einzelner
- [ ] Die Messung aus Punkt 3 ist durchgeführt und dokumentiert
- [ ] Eine absichtlich fehlerhafte Nachricht in einem Paket verhindert nicht das Speichern der
      übrigen Nachrichten des Pakets

---

## Phase 6: Raumverwaltung

**Ziel:** `POST /api/rooms` legt einen Raum an, `POST /api/rooms/{id}/members` lädt jemanden per
Benutzername ein, `GET /api/rooms` listet die eigenen Räume. `POST`/`GET /api/messages` prüfen
zusätzlich, ob der Absender bzw. Leser Mitglied des Raums ist.

**Warum an dieser Stelle:** Raumverwaltung braucht weder Keycloak noch SSE und kann deshalb direkt
nach dem `batch-service` gebaut werden. Sie muss aber vor SSE (Phase 8) stehen, weil ein
Live-Stream sinnvollerweise nur an Mitglieder eines Raums geht.

**Was entsteht:**
- Neues Paket `room` im `chat-service`: `Room`, `RoomMember`, `RoomRepository`, `RoomService`,
  `RoomController`
- Erweiterung von `MessageService`/`MessageController` um die Mitgliedsprüfung (eine zusätzliche
  Abfrage auf `room_member` pro Aufruf)

**Bezug zu Abschnitt 4 (offene Punkte):**
- **Punkt 5 — existiert der eingeladene Benutzername überhaupt?** Bewusst **nicht** in dieser
  Phase gegen Keycloak prüfen (Vorgabe in PLANUNG.md) — als bekannte Einschränkung in der
  Swagger-Doku vermerken.
- **Punkt 10 — Raum verlassen, Mitglied entfernen, Raum löschen.** In dieser Phase entscheiden und
  umsetzen, was mit den Nachrichten eines gelöschten Raums passiert (z. B. `ON DELETE CASCADE`
  oder explizites Löschen im Service).

**Fertig, wenn:**
- [ ] Ein Nutzer, der einen Raum anlegt, ist automatisch Mitglied
- [ ] Ein eingeladener Benutzername ist sofort Mitglied, ohne Bestätigungsschritt
- [ ] Senden/Lesen aus einem Raum, in dem man nicht Mitglied ist, wird mit einem passenden
      Fehlercode abgelehnt

---

## Phase 7: Keycloak-Integration im `chat-service`

**Ziel:** OAuth2-Resource-Server-Konfiguration im `chat-service`, JWT-Prüfung gegen den internen
JWKS-Endpunkt (`http://keycloak:8080`), `sender` kommt aus dem Claim `preferred_username` statt aus
dem Request-Body.

**Warum erst jetzt:** Bis hierhin liess sich ohne Login-Overhead schneller iterieren. Jetzt, wo
Nachrichtenfluss, Bündeln und Räume stehen, lohnt sich der Aufwand, echte Benutzer durchzuziehen —
und es gibt schon eine Mitgliedsprüfung (Phase 6), die vom echten Benutzernamen abhängt.

**Was sich ändert:**
- `spring-boot-starter-oauth2-resource-server` als neue Abhängigkeit
- `NewMessage.sender` entfällt ersatzlos; der Wert kommt aus dem Security-Context
- Sicherheitskonfiguration: welche Endpunkte ein gültiges Token brauchen (alle unter `/api/...`)

**Bezug zu Abschnitt 4 (offener Punkt):**
- **Punkt 1 — Issuer hinter dem Proxy**, hier erstmals wirklich **testbar**: `chat-service` prüft
  das `iss`-Feld gegen die in Phase 2 gesetzte `KC_HOSTNAME`-URL. Laut PLANUNG.md „muss getestet
  werden" — genau das passiert in dieser Phase.
- **Punkt 11 — Rollen.** Falls in Phase 2 schon angelegt: hier entscheiden, ob `moderator` das
  Einladen einschränkt, und die Rollenprüfung im `RoomController` ergänzen.

**Fertig, wenn:**
- [ ] Ein Aufruf ohne Token wird mit `401` abgelehnt
- [ ] Ein Aufruf mit gültigem Token liefert als `sender` den `preferred_username` aus dem Token,
      unabhängig davon, was der Client sonst schickt
- [ ] Ein Token mit falschem Issuer wird abgelehnt (Nachweis für Punkt 1)

---

## Phase 8: SSE-Endpunkt und React-Oberfläche

**Ziel:** `GET /stream` liefert Nachrichten per Server-Sent Events live an angemeldete Clients; die
React-App zeigt Räume, Verlauf und Live-Nachrichten an.

**Warum erst jetzt:** SSE hängt von allem Vorherigen ab (Auth, Räume, Nachrichtenfluss) und ist am
wenigsten wiederverwendbar, wenn sich darunter noch etwas ändert — deshalb bewusst spät.

**Was entsteht:**
- Im `chat-service`: instanzeigene, flüchtige Live-Queue `chat.live.<instanz>`, gebunden an
  `chat.messages`; `@RabbitListener` darauf schiebt jede Nachricht in alle offenen
  `SseEmitter`-Verbindungen der jeweiligen Instanz
- Neues React-Projekt (eigenständig deploybar, wie in PLANUNG.md Abschnitt 1 begründet): Login
  gegen Keycloak (Authorization Code + PKCE), Raumliste, Verlauf laden, `EventSource`-Verbindung
  zu `/stream`

**Bezug zu Abschnitt 4 (offene Punkte), zwingend hier zu klären:**
- **Punkt 2 — SSE und der Authorization-Header.** `EventSource` kann keine eigenen Header senden.
  Entscheidung in dieser Phase treffen: Token als Query-Parameter, kurzlebiges Ticket vor
  Verbindungsaufbau, oder `fetch`-basiertes SSE — und die Entscheidung hier im Detailplan
  begründen.
- **Punkt 8 — Nachrichtenverlust beim Reconnect.** Nach einem Verbindungsabbruch den Verlauf per
  `GET /api/messages` nachladen, bevor der Live-Stream weiterläuft.

**Fertig, wenn:**
- [ ] Zwei Browser-Tabs im selben Raum sehen dieselbe neue Nachricht ohne Neuladen
- [ ] Nach einem kurzen Verbindungsabbruch (z. B. WLAN kurz aus) fehlen keine Nachrichten im
      angezeigten Verlauf

---

## Phase 9: Queue-Regeln aus Abschnitt 2.4 setzen

**Ziel:** `chat.persist` als Quorum-Queue mit `x-delivery-limit: 3` und Dead-Letter-Queue
`chat.persist.dlq`; auf jeder `chat.live.<instanz>`-Queue `x-max-length: 1000` und
`x-message-ttl: 30000`.

**Warum erst jetzt:** Erst wenn echte Last erzeugt werden kann (mehrere Clients, viele
Nachrichten, siehe Phase 8), lässt sich der Rückstau in der RabbitMQ-Management-UI auch wirklich
provozieren und zeigen — das ist der didaktische Kern dieser Phase (Abschnitt 2.4: „dieselbe
Nachricht, zwei Queues, gegensätzliche Regeln").

**Was entsteht:**
- Queue-Deklarationen mit den genannten Argumenten (in `RabbitConfiguration` bzw. einer eigenen
  Konfigurationsklasse je Dienst)
- Ein kleines Lastskript oder ein manueller Testablauf, der `chat-service` künstlich stoppt und
  zeigt, wie `chat.persist` wächst

**Fertig, wenn:**
- [ ] Wird `batch-service` gestoppt, wächst `chat.persist` sichtbar in der Management-UI, statt
      Nachrichten zu verlieren
- [ ] Eine Nachricht, die dreimal nicht verarbeitet werden kann, landet in `chat.persist.dlq`
- [ ] Eine `chat.live.<instanz>`-Queue verwirft alte Nachrichten nach 1000 Einträgen bzw. 30
      Sekunden, statt zu blockieren

---

## Phase 10: Zeitgesteuerte Aufgaben im `batch-service`

**Ziel:** `@Scheduled`-Jobs im `batch-service`: Archivierung/Löschung von Nachrichten älter als
30 Tage, nächtliche Statistik (Nachrichten pro Raum, aktive Nutzer), Statistik-Meldung über
RabbitMQ als Systemmeldung im Chat.

**Warum am Ende:** Rein additiv — betrifft keine der vorherigen Phasen und braucht echte Daten
über einen längeren Zeitraum, um sinnvoll testbar zu sein (in der Praxis mit künstlich
zurückdatierten Testdaten).

**Was entsteht:**
- Eine Statistik-Tabelle (Migration analog zu `db/01-schema.sql`)
- Zwei `@Scheduled`-Methoden im `batch-service`
- Publizieren des Statistik-Ergebnisses auf den Fanout-Exchange, damit es im Chat als
  Systemmeldung ankommt (nutzt denselben Weg wie normale Nachrichten)

**Fertig, wenn:**
- [ ] Nachrichten mit künstlich zurückdatiertem `sent_at` werden vom Archivierungs-Job erfasst
- [ ] Die Statistik-Tabelle enthält nach einem manuellen Testlauf plausible Werte
- [ ] Die Statistik-Meldung erscheint im Chat als Systemnachricht

---

## Phase 11: Gateway (nginx) und vollständiges docker-compose

**Ziel:** nginx als einziger nach aussen offener Port. `chat-service`, `batch-service` und
`keycloak` laufen jetzt vollständig im Compose; die in Phase 1 und 2 provisorisch veröffentlichten
Ports werden zu `expose:`.

**Warum ganz am Schluss:** Das schliesst die Netzwerk-Regel aus der Vorgabe endgültig ab, die
während der ganzen Entwicklung bewusst gelockert war, damit aus der IDE heraus entwickelt und
debuggt werden konnte. Ein früherer Umbau hätte bei jeder Änderung an einem Dienst einen
Docker-Rebuild statt eines einfachen Neustarts aus der IDE bedeutet.

**Was entsteht:**
- `gateway/nginx.conf` mit den drei Routen aus Abschnitt 2.1 (`/`, `/api` + `/stream`, `/auth`)
- `Dockerfile` für `chat-service` und `batch-service` (bisher liefen beide nur aus der IDE)
- Angepasste `docker-compose.yml`: alle Dienste im Netzwerk `chat-net`, nur `gateway` mit
  `ports: ["8080:80"]`

**Bezug zu Abschnitt 4 (offene Punkte):**
- **Punkt 6 — RabbitMQ Management-UI im Unterricht.** Hier entscheiden: über das Gateway unter
  `/rabbit` proxien, oder für Demos bewusst einen zusätzlichen Port freigeben.
- **Punkt 1 — Issuer hinter dem Proxy** endgültig verifizieren: von aussen über
  `localhost:8080/auth/...` einloggen und prüfen, dass das Token vom `chat-service` akzeptiert
  wird.

**Fertig, wenn:**
- [ ] `docker compose ps` zeigt: nur `gateway` hat einen veröffentlichten Port
- [ ] Login, Nachrichten senden/lesen und SSE funktionieren vollständig über `localhost:8080`
- [ ] Kein Container ausser `gateway` ist von aussen erreichbar (z. B. `curl localhost:5432`
      schlägt fehl)

---

## Phase 12: JavaFX-Client

**Ziel:** Ein zweiter, unabhängiger Client gegen dieselbe API, läuft auf dem Host (nicht im
Compose) und spricht `localhost:8080` an — genau wie der Browser.

**Warum als letzter Schritt:** Zeigt die Client-Unabhängigkeit des Backends am überzeugendsten,
wenn API, Auth, Räume und SSE bereits stabil sind. Ein früherer Bau hätte jede spätere
Backend-Änderung doppelt nachgezogen.

**Was entsteht:**
- Neues Maven-Modul `javafx-client/`
- Login per eingebettetem Browserfenster oder Device-Authorization-Flow (Entscheidung hier
  treffen und begründen)
- Einfache Oberfläche: Raumliste, Verlauf, Nachricht senden, Live-Empfang (Polling oder
  SSE-Client, je nachdem was mit JavaFX praktikabel ist)

**Bezug zu Abschnitt 4 (offener Punkt):**
- **Punkt 9 — JavaFX-Client und Docker.** Hier bestätigen: der Client bleibt bewusst ausserhalb
  von `docker-compose.yml`, weil er auf dem Host läuft wie der Browser auch — kein zusätzlicher
  offener Port nötig.

**Fertig, wenn:**
- [ ] Login funktioniert aus dem JavaFX-Fenster heraus
- [ ] Eine im JavaFX-Client gesendete Nachricht erscheint live im Browser und umgekehrt
- [ ] Der Client braucht keinen eigenen Eintrag in `docker-compose.yml`

---

## Gesamt-Fertig-Kriterien

- [ ] Alle 12 Phasen sind einzeln abgehakt und committet
- [ ] Alle 11 offenen Punkte aus `PLANUNG.md`, Abschnitt 4, sind einer Phase zugeordnet und dort
      geklärt (siehe Tabelle unten)
- [ ] `docker compose up -d` startet das gesamte System aus einem sauberen Checkout heraus
- [ ] Von aussen ist ausschliesslich Port 8080 erreichbar
- [ ] Beide Clients (React, JavaFX) funktionieren nebeneinander gegen dasselbe Backend

### Zuordnung der offenen Punkte aus Abschnitt 4

| # | Offener Punkt | Geklärt in Phase |
|---|---|---|
| 1 | Keycloak-Issuer hinter dem Proxy | 2 (vorbereitet), 7 (getestet), 11 (final verifiziert) |
| 2 | SSE und der Authorization-Header | 8 |
| 3 | Paketgrösse und Zeitlimit sind geraten | 5 |
| 4 | Einzelweg nach fehlgeschlagenem Paket | 5 |
| 5 | Existiert der eingeladene Benutzername überhaupt? | 6 (bewusst offen gelassen, siehe dort) |
| 6 | RabbitMQ Management-UI im Unterricht | 11 |
| 7 | Zweite `chat-service`-Instanz | nicht eingeplant — siehe Hinweis unten |
| 8 | Nachrichtenverlust beim Reconnect | 8 |
| 9 | JavaFX-Client und Docker | 12 |
| 10 | Raum verlassen, Mitglied entfernen, Raum löschen | 6 |
| 11 | Rollen in Keycloak | 2 (angelegt), 7 (angewendet) |

**Hinweis zu Punkt 7 (zweite `chat-service`-Instanz):** In PLANUNG.md als „geplant, aber noch nicht
entschieden, ob Teil der Abgabe" markiert. Dieser Gesamtplan geht von **einer** Instanz aus. Soll
eine zweite Instanz Teil der Abgabe werden, ist das eine zusätzliche Phase nach Phase 11 (jede
Instanz braucht eine eigene, exklusive Live-Queue am Fanout-Exchange — technisch bereits durch das
Namensschema `chat.live.<instanz>` vorbereitet).
