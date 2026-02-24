# Integrationstests – hotpatch-maven-plugin
## Überblick

Die Integrationstests nutzen das **maven-invoker-plugin**, das für jedes
Testszenario ein eigenständiges Maven-Projekt in einem isolierten lokalen
Repository ausführt. Nach jedem Build wertet ein `verify.groovy`-Skript
das Ergebnis aus.

```
src/it/
├── settings.xml              ← Leitet alle Repo-Zugriffe auf das IT-lokale Repo um
│
├── 01-changed-file/          ← Szenario: Datei wurde geändert
│   ├── pom.xml
│   ├── fixture.jar           ← wird von antrun erzeugt (pre-integration-test)
│   ├── fixture-jar-src/      ← Quellen für das Fixture-JAR (alte Version)
│   │   └── com/example/Foo.java
│   ├── src/main/java/        ← Neue Version (weicht vom JAR ab)
│   │   └── com/example/Foo.java
│   ├── invoker.properties
│   └── verify.groovy         ← Prüft: hotpatch.zip enthält Foo.class ✓
│
├── 02-new-file/              ← Szenario: Neue Datei, fehlt im JAR
│   ├── ...                   ← leeres Fixture-JAR, Bar.java nur lokal
│   └── verify.groovy         ← Prüft: hotpatch.zip enthält Bar.class ✓
│
├── 03-no-changes/            ← Szenario: Keine Unterschiede
│   ├── ...                   ← Fixture-JAR == target/classes (version.properties)
│   └── verify.groovy         ← Prüft: hotpatch.zip existiert NICHT ✓
│
└── 04-crlf-ignored/          ← Szenario: CRLF vs. LF – kein echter Unterschied
    ├── ...                   ← antrun erzeugt CRLF-JAR / LF-Ressource
    └── verify.groovy         ← Prüft: hotpatch.zip existiert NICHT ✓
```

## Szenarien im Detail

| # | Szenario | Fixture-JAR | target/classes | Erwartetes Ergebnis |
|---|----------|-------------|----------------|---------------------|
| 01 | Geänderte Datei | `Foo.class` (alt) | `Foo.class` (neu) | ZIP mit `Foo.class` |
| 02 | Neue Datei | leer | `Bar.class` | ZIP mit `Bar.class` |
| 03 | Keine Änderungen | `version.properties` | identisch | **Kein ZIP** |
| 04 | Nur CRLF/LF | `app.properties` `\r\n` | `app.properties` `\n` | **Kein ZIP** |

## Ausführung

### Alle ITs
```bash
mvn verify
```

### Nur einzelne Szenarien (nützlich während der Entwicklung)
```bash
# Nur Szenario 01 ausführen
mvn verify -Dinvoker.test=01-changed-file

# Mehrere Szenarien
mvn verify -Dinvoker.test=01-changed-file,03-no-changes
```

### Fixture-JARs manuell neu bauen
Die Fixture-JARs werden automatisch in der `pre-integration-test`-Phase
durch `maven-antrun-plugin` erzeugt. Falls nötig:
```bash
mvn antrun:run@create-fixture-jars
```

### Ausgaben nach dem Lauf
```
target/
├── it/                        ← geklonte IT-Projekte (zum Debuggen)
│   ├── 01-changed-file/
│   │   └── target/
│   │       ├── hotpatch.zip   ← das erzeugte ZIP
│   │       └── invoker.log    ← vollständiges Maven-Log
│   └── ...
└── invoker-reports/           ← XML-Reports (werden von maven-invoker:verify ausgewertet)
```

## Fehlersuche

Wenn ein IT-Lauf fehlschlägt:
1. `target/it/<szenario>/target/invoker.log` öffnen – enthält das komplette Maven-Log
2. `target/invoker-reports/` enthält XML-Berichte je Szenario
3. Einzelnen Lauf mit erhöhter Verbosität: `mvn verify -Dinvoker.test=01-changed-file -X`

## Hinweis zu Szenario 03 (keine Änderungen)

Da der Java-Compiler bei jedem Build leicht unterschiedliche Bytecodes erzeugen
kann (z.B. durch Timestamp-Metadaten), wird für den Gleichheitstest eine
**Textdatei** (`META-INF/version.properties`) verwendet, die garantiert
byte-identisch ist. Für reproduzierbaren `.class`-Vergleich empfiehlt sich
`maven.build.timestamp` in Verbindung mit `-Dproject.build.outputTimestamp`.
