# Resource Diff Maven Plugin

Ein Maven-Plugin (Mojo), das die Ressourcen im `target/classes`-Verzeichnis eines Projekts mit denen aus einem JAR-Artefakt vergleicht und ein ZIP-Archiv der abweichenden Dateien erstellt.

## Features

- **Automatische Koordinaten**: `groupId` und `artifactId` werden aus dem aktuellen Projekt gelesen
- **Versionsparameter**: Die zu vergleichende Version kann als Parameter angegeben werden; standardmäßig wird die Projektversion verwendet
- **Artifactory / Maven-Repos**: Das JAR wird über die Standard-Maven-Repository-Auflösung heruntergeladen (lokales Repo, Remote-Repos, Artifactory etc.)
- **Diff-Erkennung**: Findet sowohl inhaltlich veränderte als auch neu hinzugekommene Dateien
- **.class-Ausschluss**: `.class`-Dateien werden beim Vergleich ignoriert
- **ZIP-Ausgabe**: Alle Unterschiede werden in `target/resource-diff.zip` gepackt

## Installation

```bash
cd resource-diff-maven-plugin
mvn clean install
```

## Verwendung

### Im Projekt-POM einbinden

```xml
<build>
    <plugins>
        <plugin>
            <groupId>com.example.maven</groupId>
            <artifactId>resource-diff-maven-plugin</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <executions>
                <execution>
                    <goals>
                        <goal>diff</goal>
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
mvn com.example.maven:resource-diff-maven-plugin:1.0.0-SNAPSHOT:diff

# Mit einer bestimmten Version vergleichen
mvn com.example.maven:resource-diff-maven-plugin:1.0.0-SNAPSHOT:diff -Dresourcediff.compareVersion=1.2.0
```

### Voraussetzungen

Das Plugin muss **nach** der Kompilierung laufen, da es `target/classes` benötigt. Die Default-Phase ist `process-classes`, daher reicht:

```bash
mvn process-classes    # wenn das Plugin per <execution> eingebunden ist
```

Oder einfach:

```bash
mvn compile com.example.maven:resource-diff-maven-plugin:1.0.0-SNAPSHOT:diff
```

## Konfigurationsparameter

| Parameter         | Property                        | Default            | Beschreibung                                                    |
|-------------------|---------------------------------|--------------------|-----------------------------------------------------------------|
| `compareVersion`  | `resourcediff.compareVersion`   | Projektversion     | Die Version des Artefakts, gegen das verglichen wird             |
| `outputFileName`  | `resourcediff.outputFileName`   | `resource-diff.zip`| Name der Ausgabe-ZIP-Datei                                       |

## Funktionsweise

1. **Koordinaten ermitteln**: `groupId` und `artifactId` aus dem aktuellen `MavenProject`, Version aus Parameter oder POM
2. **JAR auflösen**: Das Artefakt wird über das Eclipse-Aether-Repository-System heruntergeladen (nutzt lokales + konfigurierte Remote-Repos)
3. **Vergleich**: Alle Dateien in `target/classes` (außer `.class`) werden byteweise mit den Einträgen im JAR verglichen
4. **ZIP erstellen**: Dateien, die sich unterscheiden oder nur lokal existieren, werden in `target/resource-diff.zip` gepackt