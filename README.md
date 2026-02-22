# Hotpatch Maven Plugin

Ein Maven-Plugin, das den Inhalt von `target/classes` mit einem JAR-Artefakt aus dem Maven-Repository vergleicht und aus den abweichenden Dateien ein ZIP-Archiv (Hotpatch) erstellt.

## Features

- **Automatische Koordinaten**: `groupId` und `artifactId` werden aus dem aktuellen Projekt gelesen
- **Versionsparameter**: Die zu vergleichende Version kann als Parameter angegeben werden; standardmäßig wird die Projektversion verwendet
- **Maven-Repository-Auflösung**: Das JAR wird über die Standard-Maven-Auflösung heruntergeladen (lokales Repo, Remote-Repos, Artifactory etc.)
- **Diff-Erkennung**: Findet inhaltlich veränderte und neu hinzugekommene Dateien
- **Intelligente Binärerkennung**: Nutzt JGit zur automatischen Unterscheidung von Text- und Binärdateien – keine manuelle Pflege von Dateiendungen nötig
- **Zeilenende-tolerant**: Bei Textdateien werden `\r\n` und `\n` als gleichwertig behandelt
- **ZIP-Ausgabe**: Alle Unterschiede werden in `target/hotpatch.zip` gepackt

## Installation

```bash
cd hotpatch-maven-plugin
mvn clean install
```

## Verwendung

### Im Projekt-POM einbinden

```xml
<build>
    <plugins>
        <plugin>
            <groupId>com.example.maven</groupId>
            <artifactId>hotpatch-maven-plugin</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <executions>
                <execution>
                    <goals>
                        <goal>make-hotpatch</goal>
                    </goals>
                </execution>
            </executions>
            <configuration>
                <!-- Optional: Version zum Vergleich angeben -->
                <compareVersion>1.0.0</compareVersion>
            </configuration>
        </plugin>
    </plugins>
</build>
```

### Direkt per Kommandozeile aufrufen

```bash
# Mit der Projektversion vergleichen (Default)
mvn com.example.maven:hotpatch-maven-plugin:make-hotpatch

# Mit einer bestimmten Version vergleichen
mvn com.example.maven:hotpatch-maven-plugin:make-hotpatch -Dhotpatch.compareVersion=1.2.0
```

### Voraussetzungen

Das Plugin muss **nach** der Kompilierung laufen, da es `target/classes` benötigt. Die Default-Phase ist `process-classes`, daher reicht:

```bash
mvn process-classes    # wenn das Plugin per <execution> eingebunden ist
```

Oder einfach:

```bash
mvn compile com.example.maven:hotpatch-maven-plugin:make-hotpatch
```

## Konfigurationsparameter

| Parameter         | Property                     | Default          | Beschreibung                                                    |
|-------------------|------------------------------|------------------|-----------------------------------------------------------------|
| `compareVersion`  | `hotpatch.compareVersion`    | Projektversion   | Die Version des Artefakts, gegen das verglichen wird             |
| `outputFileName`  | `hotpatch.outputFileName`    | `hotpatch.zip`   | Name der Ausgabe-ZIP-Datei                                       |

## Funktionsweise

1. **Koordinaten ermitteln**: `groupId` und `artifactId` aus dem aktuellen `MavenProject`, Version aus Parameter oder POM
2. **JAR auflösen**: Das Artefakt wird über das Eclipse-Aether-Repository-System heruntergeladen (nutzt lokales + konfigurierte Remote-Repos)
3. **Vergleich**: Alle Dateien in `target/classes` werden mit den Einträgen im JAR verglichen. Binärdateien werden byte-genau verglichen; bei Textdateien werden Zeilenende-Unterschiede (`\r\n` vs. `\n`) ignoriert
4. **Hotpatch erstellen**: Dateien, die sich unterscheiden oder nur lokal existieren, werden in `target/hotpatch.zip` gepackt
