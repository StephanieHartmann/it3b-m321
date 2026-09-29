# Baut den batch-writer in zwei Stufen (Multi-Stage-Build).
# Stufe 1 enthaelt Maven und das volle JDK - das braucht es nur zum Bauen.
# Stufe 2 enthaelt nur noch die Java-Laufzeit (JRE) und das fertige JAR. So bleibt das
# Image, das am Ende laeuft, klein und enthaelt keine Build-Werkzeuge.

# ---------- Stufe 1: bauen ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Zuerst NUR die pom.xml kopieren und alle Abhaengigkeiten herunterladen. Docker merkt sich
# jeden Schritt als "Schicht". Solange sich die pom.xml nicht aendert, nimmt Docker beim
# naechsten Bauen diese Schicht aus dem Zwischenspeicher - der lange Download entfaellt,
# auch wenn sich der Java-Code geaendert hat.
COPY pom.xml .
RUN mvn -q dependency:go-offline

# Jetzt den Quellcode kopieren und das JAR bauen. Die Tests laufen hier nicht (-DskipTests),
# weil sie ausserhalb von Docker mit "mvn test" laufen.
COPY src ./src
RUN mvn -q clean package -DskipTests

# ---------- Stufe 2: ausfuehren ----------
FROM eclipse-temurin:21-jre
WORKDIR /app

# Aus Stufe 1 nur das fertige JAR uebernehmen - Maven, Quellcode und Zwischendateien
# bleiben zurueck.
COPY --from=build /build/target/batch-writer-0.1.0.jar app.jar

# Startbefehl des Containers. Die Verbindungsdaten (Datenbank, RabbitMQ) kommen als
# Umgebungsvariablen aus docker-compose.yml und ueberschreiben die localhost-Werte aus
# application.yml.
ENTRYPOINT ["java", "-jar", "app.jar"]
