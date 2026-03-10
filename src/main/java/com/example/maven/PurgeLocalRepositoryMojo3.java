package com.example.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Maven-Goal zum Bereinigen des lokalen Maven Repositories.
 *
 * <p>Entfernt alle lokal installierten SNAPSHOT-Versionen unterhalb der
 * Gruppenkennung {@code com.company}. Als Erkennungsmerkmal fuer eine
 * lokale Installation dient die Existenz der Datei
 * {@code maven-metadata-local.xml} im jeweiligen Versionsverzeichnis.</p>
 *
 * <p>Da die JAR-Datei dieses Plugins selbst von der laufenden Maven-JVM
 * gesperrt ist (insbesondere unter Windows), werden die eigenen Artefakte
 * ueber einen separaten Hintergrundprozess geloescht, der nach Beendigung
 * des Maven-Builds aufrauemt.</p>
 *
 * <p>Verwendung:</p>
 * <pre>
 *   mvn com.company.maven.plugins:purge-local-repo-maven-plugin:purge-local-repository
 * </pre>
 *
 * @since 1.0.0
 */
@Mojo(name = "purge-local-repository", requiresProject = false)
public class PurgeLocalRepositoryMojo3 extends AbstractMojo {

    /**
     * Pfad zum lokalen Maven Repository.
     * Standardmaessig wird {@code ${user.home}/.m2/repository} verwendet.
     */
    @Parameter(property = "localRepository",
            defaultValue = "${settings.localRepository}")
    private File localRepository;

    /**
     * Gruppen-Praefix, unterhalb dessen SNAPSHOT-Versionen gesucht werden.
     * Der Punkt-separierte Gruppenname wird automatisch in einen Verzeichnispfad
     * umgewandelt (z.B. {@code com.company} wird zu {@code com/company}).
     */
    @Parameter(property = "groupPrefix", defaultValue = "com.company")
    private String groupPrefix;

    /**
     * Wenn {@code true}, wird nur protokolliert, welche Verzeichnisse
     * geloescht wuerden, ohne sie tatsaechlich zu entfernen (Trockenlauf).
     */
    @Parameter(property = "dryRun", defaultValue = "false")
    private boolean dryRun;

    /**
     * Der Plugin-Deskriptor wird von Maven automatisch injiziert und enthaelt
     * die GroupId und ArtifactId dieses Plugins. Damit kann das Plugin sein
     * eigenes Artefakt im lokalen Repository identifizieren und die Loesch-
     * Strategie entsprechend anpassen.
     */
    @Parameter(defaultValue = "${plugin}", readonly = true)
    private PluginDescriptor pluginDescriptor;

    /**
     * Name der Metadaten-Datei, die als Erkennungsmerkmal fuer eine
     * lokale Installation verwendet wird.
     */
    private static final String LOCAL_METADATA_FILE = "maven-metadata-local.xml";

    /**
     * Suffix fuer SNAPSHOT-Versionsverzeichnisse.
     */
    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    /**
     * Wartezeit in Millisekunden zwischen Loeschversuchen.
     * Gibt dem Betriebssystem Zeit, Dateisperren freizugeben.
     */
    private static final long WARTEZEIT_ZWISCHEN_VERSUCHEN_MS = 200;

    /**
     * Maximale Anzahl an Loeschversuchen pro Datei oder Verzeichnis.
     * Bei gesperrten Dateien (z.B. unter Windows) wird zwischen den
     * Versuchen eine kurze Pause eingelegt und der Garbage Collector
     * aufgerufen, um offene Datei-Handles freizugeben.
     */
    @Parameter(property = "maxRetries", defaultValue = "5")
    private int maxRetries;

    /**
     * Wartezeit in Sekunden, die der Hintergrundprozess nach dem Start
     * abwartet, bevor er versucht die eigenen Plugin-Artefakte zu loeschen.
     * Diese Verzoegerung gibt der Maven-JVM Zeit, sich vollstaendig zu
     * beenden und die Dateisperren freizugeben.
     */
    @Parameter(property = "cleanupDelaySeconds", defaultValue = "3")
    private int cleanupDelaySeconds;

    /**
     * Fuehrt das Goal aus.
     *
     * <p>Durchsucht das lokale Maven Repository unterhalb des konfigurierten
     * Gruppen-Praefixes nach SNAPSHOT-Versionsverzeichnissen, die eine
     * {@code maven-metadata-local.xml} enthalten. Die gefundenen Verzeichnisse
     * werden in zwei Gruppen aufgeteilt:</p>
     * <ol>
     *   <li><b>Fremde Artefakte:</b> werden direkt geloescht (mit Retry-Logik)</li>
     *   <li><b>Eigene Plugin-Artefakte:</b> werden ueber einen separaten
     *       Hintergrundprozess geloescht, da die JAR-Dateien von der laufenden
     *       Maven-JVM gesperrt sind</li>
     * </ol>
     *
     * @throws MojoExecutionException bei unerwartetem Fehler waehrend der Ausfuehrung
     * @throws MojoFailureException   wenn das Goal erwartungsgemaess fehlschlaegt
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        File baseDir = ermittleBasisverzeichnis();

        if (!baseDir.exists() || !baseDir.isDirectory()) {
            getLog().info("Basisverzeichnis existiert nicht oder ist kein Verzeichnis: "
                    + baseDir.getAbsolutePath());
            getLog().info("Es gibt nichts zu bereinigen.");
            return;
        }

        getLog().info("Durchsuche lokales Repository: " + localRepository.getAbsolutePath());
        getLog().info("Gruppen-Praefix: " + groupPrefix);
        getLog().info("Basisverzeichnis: " + baseDir.getAbsolutePath());

        if (dryRun) {
            getLog().info("*** TROCKENLAUF - es werden keine Dateien geloescht ***");
        }

        List<File> zuLoeschendeVerzeichnisse = findeLokalInstallierteSnapshots(baseDir);

        if (zuLoeschendeVerzeichnisse.isEmpty()) {
            getLog().info("Keine lokal installierten SNAPSHOT-Versionen gefunden.");
            return;
        }

        getLog().info(zuLoeschendeVerzeichnisse.size()
                + " lokal installierte SNAPSHOT-Version(en) gefunden.");

        // Eigene Artefakte von fremden trennen
        String eigenerPfad = ermittleEigenenArtefaktPfad();
        List<File> fremdeArtefakte = new ArrayList<File>();
        List<File> eigeneArtefakte = new ArrayList<File>();

        for (File verzeichnis : zuLoeschendeVerzeichnisse) {
            if (eigenerPfad != null && istEigenesArtefakt(verzeichnis, eigenerPfad)) {
                eigeneArtefakte.add(verzeichnis);
            } else {
                fremdeArtefakte.add(verzeichnis);
            }
        }

        if (!eigeneArtefakte.isEmpty()) {
            getLog().info(eigeneArtefakte.size()
                    + " davon sind eigene Plugin-Artefakte (werden verzoegert geloescht).");
        }

        // Phase 1: Fremde Artefakte direkt loeschen
        int erfolgreich = 0;
        int fehlgeschlagen = 0;

        for (File verzeichnis : fremdeArtefakte) {
            if (dryRun) {
                getLog().info("[TROCKENLAUF] Wuerde loeschen: " + verzeichnis.getAbsolutePath());
                File artefaktMetadata = new File(verzeichnis.getParentFile(), LOCAL_METADATA_FILE);
                if (artefaktMetadata.exists()) {
                    getLog().info("[TROCKENLAUF] Wuerde loeschen: "
                            + artefaktMetadata.getAbsolutePath());
                }
            } else {
                try {
                    loescheVerzeichnisRekursiv(verzeichnis.toPath());
                    getLog().info("Geloescht: " + verzeichnis.getAbsolutePath());
                    erfolgreich++;
                    entferneArtefaktMetadata(verzeichnis.getParentFile());
                    bereinigeLeeresElternverzeichnis(verzeichnis.getParentFile());
                } catch (IOException e) {
                    getLog().warn("Fehler beim Loeschen von "
                            + verzeichnis.getAbsolutePath() + ": " + e.getMessage());
                    getLog().warn("Moeglicherweise sperrt ein anderer Prozess (IDE, Daemon) "
                            + "die Dateien. Bitte pruefen und das Goal erneut ausfuehren.");
                    fehlgeschlagen++;
                }
            }
        }

        // Phase 2: Eigene Artefakte per Hintergrundprozess loeschen
        if (!eigeneArtefakte.isEmpty() && !dryRun) {
            try {
                starteHintergrundbereinigung(eigeneArtefakte);
                erfolgreich += eigeneArtefakte.size();
            } catch (IOException e) {
                getLog().warn("Konnte Hintergrundprozess zur Bereinigung der "
                        + "eigenen Artefakte nicht starten: " + e.getMessage());
                fehlgeschlagen += eigeneArtefakte.size();
            }
        } else if (!eigeneArtefakte.isEmpty()) {
            for (File verzeichnis : eigeneArtefakte) {
                getLog().info("[TROCKENLAUF] Wuerde verzoegert loeschen: "
                        + verzeichnis.getAbsolutePath());
                File artefaktMetadata = new File(verzeichnis.getParentFile(), LOCAL_METADATA_FILE);
                if (artefaktMetadata.exists()) {
                    getLog().info("[TROCKENLAUF] Wuerde verzoegert loeschen: "
                            + artefaktMetadata.getAbsolutePath());
                }
            }
        }

        if (!dryRun) {
            getLog().info("Bereinigung abgeschlossen: " + erfolgreich
                    + " geloescht, " + fehlgeschlagen + " fehlgeschlagen.");
        }
    }

    /**
     * Ermittelt den relativen Pfad des eigenen Plugin-Artefakts im
     * lokalen Repository.
     *
     * <p>Wird aus der GroupId und ArtifactId des Plugin-Deskriptors
     * berechnet, z.B. {@code com/company/maven/plugins/purge-local-repo-maven-plugin}.</p>
     *
     * @return der relative Pfad oder {@code null} wenn der Plugin-Deskriptor
     *         nicht verfuegbar ist
     */
    private String ermittleEigenenArtefaktPfad() {
        if (pluginDescriptor == null) {
            getLog().debug("Plugin-Deskriptor nicht verfuegbar, "
                    + "Eigenartefakt-Erkennung deaktiviert.");
            return null;
        }

        String gruppenPfad = pluginDescriptor.getGroupId().replace('.', File.separatorChar);
        return gruppenPfad + File.separator + pluginDescriptor.getArtifactId();
    }

    /**
     * Prueft, ob das angegebene Verzeichnis zum eigenen Plugin-Artefakt gehoert.
     *
     * <p>Die Pruefung erfolgt ueber den Dateipfad: Wenn der absolute Pfad
     * des Verzeichnisses den berechneten Artefaktpfad des Plugins enthaelt,
     * handelt es sich um ein eigenes Artefakt.</p>
     *
     * @param verzeichnis  das zu pruefende Verzeichnis
     * @param eigenerPfad  der relative Pfad des eigenen Plugin-Artefakts
     * @return {@code true} wenn es sich um ein eigenes Artefakt handelt
     */
    private boolean istEigenesArtefakt(File verzeichnis, String eigenerPfad) {
        return verzeichnis.getAbsolutePath().contains(eigenerPfad);
    }

    /**
     * Startet einen Hintergrundprozess, der nach einer Verzoegerung die
     * eigenen Plugin-Artefakte loescht.
     *
     * <p>Der Hintergrundprozess wird als plattformspezifisches Skript
     * erzeugt und gestartet:</p>
     * <ul>
     *   <li><b>Windows:</b> Ein CMD-Skript mit {@code ping -n} als
     *       Verzoegerung und {@code rmdir /s /q} zum Loeschen</li>
     *   <li><b>Unix/Linux/Mac:</b> Ein Shell-Skript mit {@code sleep}
     *       als Verzoegerung und {@code rm -rf} zum Loeschen</li>
     * </ul>
     *
     * <p>Das Skript wartet zunaechst {@link #cleanupDelaySeconds} Sekunden,
     * damit die Maven-JVM sich beenden und die Dateisperren freigeben kann.
     * Anschliessend werden die Verzeichnisse geloescht und das Skript
     * entfernt sich selbst.</p>
     *
     * @param verzeichnisse die Liste der zu loeschenden eigenen Artefakt-Verzeichnisse
     * @throws IOException wenn das Skript nicht erstellt oder der Prozess
     *                     nicht gestartet werden konnte
     */
    private void starteHintergrundbereinigung(List<File> verzeichnisse) throws IOException {
        boolean istWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

        File skriptDatei;
        ProcessBuilder processBuilder;

        if (istWindows) {
            skriptDatei = erstelleWindowsSkript(verzeichnisse);
            processBuilder = new ProcessBuilder("cmd.exe", "/c",
                    "start", "/min", "\"\"", skriptDatei.getAbsolutePath());
        } else {
            skriptDatei = erstelleUnixSkript(verzeichnisse);
            skriptDatei.setExecutable(true);
            processBuilder = new ProcessBuilder("nohup", "/bin/sh",
                    skriptDatei.getAbsolutePath());
        }

        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.to(
                new File(System.getProperty("java.io.tmpdir"),
                        "purge-local-repo-cleanup.log")));
        processBuilder.start();

        getLog().info("Hintergrundprozess gestartet. Die eigenen Plugin-Artefakte werden "
                + "in ca. " + cleanupDelaySeconds + " Sekunden nach Ende des Builds geloescht.");

        for (File verzeichnis : verzeichnisse) {
            getLog().info("  Verzoegert: " + verzeichnis.getAbsolutePath());
        }
    }

    /**
     * Erstellt ein Windows-CMD-Skript zur verzoegerten Loeschung der
     * angegebenen Verzeichnisse.
     *
     * <p>Das Skript fuehrt folgende Schritte aus:</p>
     * <ol>
     *   <li>Wartet die konfigurierte Verzoegerung ab (via {@code ping -n}
     *       an localhost, da {@code timeout} in minimierten Fenstern
     *       nicht zuverlaessig funktioniert)</li>
     *   <li>Loescht jedes angegebene Verzeichnis mit {@code rmdir /s /q}</li>
     *   <li>Entfernt leere Elternverzeichnisse aufwaerts</li>
     *   <li>Loescht sich selbst</li>
     * </ol>
     *
     * @param verzeichnisse die zu loeschenden Verzeichnisse
     * @return die erzeugte Skript-Datei
     * @throws IOException wenn die Datei nicht geschrieben werden konnte
     */
    private File erstelleWindowsSkript(List<File> verzeichnisse) throws IOException {
        File skriptDatei = File.createTempFile("purge-local-repo-cleanup-", ".cmd");

        PrintWriter writer = null;
        try {
            writer = new PrintWriter(skriptDatei, "UTF-8");
            writer.println("@echo off");
            // ping -n wartet (n-1) Sekunden; +1 damit die Verzoegerung stimmt
            writer.println("ping -n " + (cleanupDelaySeconds + 1) + " 127.0.0.1 > nul");

            for (File verzeichnis : verzeichnisse) {
                writer.println("rmdir /s /q \"" + verzeichnis.getAbsolutePath() + "\" 2>nul");
                // maven-metadata-local.xml auf Artefakt-Ebene (Elternverzeichnis) entfernen
                File artefaktMetadata = new File(verzeichnis.getParentFile(), LOCAL_METADATA_FILE);
                writer.println("del /f /q \"" + artefaktMetadata.getAbsolutePath() + "\" 2>nul");
                // Elternverzeichnisse aufwaerts bereinigen, bis eines nicht leer ist
                schreibeElternbereinigungWindows(writer, verzeichnis);
            }

            // Skript loescht sich selbst
            writer.println("del /f /q \"%~f0\"");
        } finally {
            if (writer != null) {
                writer.close();
            }
        }

        return skriptDatei;
    }

    /**
     * Schreibt Windows-CMD-Befehle zum Entfernen leerer Elternverzeichnisse
     * oberhalb des angegebenen Verzeichnisses.
     *
     * <p>Fuer jedes Elternverzeichnis zwischen dem geloeschten SNAPSHOT-
     * Verzeichnis und dem Basisverzeichnis des Gruppen-Praefixes wird
     * ein bedingter {@code rmdir}-Befehl geschrieben, der nur leere
     * Verzeichnisse entfernt.</p>
     *
     * @param writer      der PrintWriter fuer das Skript
     * @param verzeichnis das Ausgangsverzeichnis, dessen Eltern bereinigt werden
     */
    private void schreibeElternbereinigungWindows(PrintWriter writer, File verzeichnis) {
        File basisVerzeichnis = ermittleBasisverzeichnis();
        File aktuell = verzeichnis.getParentFile();

        while (aktuell != null
                && !aktuell.equals(basisVerzeichnis)
                && aktuell.getAbsolutePath().startsWith(basisVerzeichnis.getAbsolutePath())) {
            // rmdir ohne /s entfernt nur leere Verzeichnisse
            writer.println("rmdir \"" + aktuell.getAbsolutePath() + "\" 2>nul");
            aktuell = aktuell.getParentFile();
        }
    }

    /**
     * Erstellt ein Unix-Shell-Skript zur verzoegerten Loeschung der
     * angegebenen Verzeichnisse.
     *
     * <p>Das Skript fuehrt folgende Schritte aus:</p>
     * <ol>
     *   <li>Wartet die konfigurierte Verzoegerung ab (via {@code sleep})</li>
     *   <li>Loescht jedes angegebene Verzeichnis mit {@code rm -rf}</li>
     *   <li>Entfernt leere Elternverzeichnisse mit {@code rmdir}</li>
     *   <li>Loescht sich selbst</li>
     * </ol>
     *
     * @param verzeichnisse die zu loeschenden Verzeichnisse
     * @return die erzeugte Skript-Datei
     * @throws IOException wenn die Datei nicht geschrieben werden konnte
     */
    private File erstelleUnixSkript(List<File> verzeichnisse) throws IOException {
        File skriptDatei = File.createTempFile("purge-local-repo-cleanup-", ".sh");

        PrintWriter writer = null;
        try {
            writer = new PrintWriter(skriptDatei, "UTF-8");
            writer.println("#!/bin/sh");
            writer.println("sleep " + cleanupDelaySeconds);

            for (File verzeichnis : verzeichnisse) {
                writer.println("rm -rf '"
                        + verzeichnis.getAbsolutePath().replace("'", "'\\''") + "'");
                // maven-metadata-local.xml auf Artefakt-Ebene (Elternverzeichnis) entfernen
                File artefaktMetadata = new File(verzeichnis.getParentFile(), LOCAL_METADATA_FILE);
                writer.println("rm -f '"
                        + artefaktMetadata.getAbsolutePath().replace("'", "'\\''") + "'");
                schreibeElternbereinigungUnix(writer, verzeichnis);
            }

            // Skript loescht sich selbst
            writer.println("rm -f \"$0\"");
        } finally {
            if (writer != null) {
                writer.close();
            }
        }

        return skriptDatei;
    }

    /**
     * Schreibt Unix-Shell-Befehle zum Entfernen leerer Elternverzeichnisse
     * oberhalb des angegebenen Verzeichnisses.
     *
     * <p>Verwendet {@code rmdir} ohne Optionen, das nur leere
     * Verzeichnisse entfernt und bei nicht-leeren Verzeichnissen
     * fehlschlaegt (Fehlerausgabe wird unterdrueckt).</p>
     *
     * @param writer      der PrintWriter fuer das Skript
     * @param verzeichnis das Ausgangsverzeichnis, dessen Eltern bereinigt werden
     */
    private void schreibeElternbereinigungUnix(PrintWriter writer, File verzeichnis) {
        File basisVerzeichnis = ermittleBasisverzeichnis();
        File aktuell = verzeichnis.getParentFile();

        while (aktuell != null
                && !aktuell.equals(basisVerzeichnis)
                && aktuell.getAbsolutePath().startsWith(basisVerzeichnis.getAbsolutePath())) {
            writer.println("rmdir '"
                    + aktuell.getAbsolutePath().replace("'", "'\\''") + "' 2>/dev/null");
            aktuell = aktuell.getParentFile();
        }
    }

    /**
     * Ermittelt das Basisverzeichnis im lokalen Repository anhand des
     * konfigurierten Gruppen-Praefixes.
     *
     * <p>Der Punkt-separierte Gruppenname wird in einen Verzeichnispfad
     * umgewandelt, z.B. wird {@code com.company} zu
     * {@code <localRepository>/com/company}.</p>
     *
     * @return das Basisverzeichnis als {@link File}
     */
    private File ermittleBasisverzeichnis() {
        String gruppenPfad = groupPrefix.replace('.', File.separatorChar);
        return new File(localRepository, gruppenPfad);
    }

    /**
     * Durchsucht das angegebene Verzeichnis rekursiv nach
     * SNAPSHOT-Versionsverzeichnissen, die eine
     * {@code maven-metadata-local.xml} enthalten.
     *
     * <p>Ein Verzeichnis wird als zu loeschend eingestuft, wenn:</p>
     * <ul>
     *   <li>der Verzeichnisname auf {@code -SNAPSHOT} endet</li>
     *   <li>eine Datei namens {@code maven-metadata-local.xml} darin existiert</li>
     * </ul>
     *
     * @param basisVerzeichnis das Startverzeichnis fuer die Suche
     * @return eine Liste der zu loeschenden Verzeichnisse
     */
    private List<File> findeLokalInstallierteSnapshots(File basisVerzeichnis) {
        List<File> ergebnis = new ArrayList<File>();
        durchsucheVerzeichnis(basisVerzeichnis, ergebnis);
        return ergebnis;
    }

    /**
     * Rekursive Hilfsmethode zum Durchsuchen eines Verzeichnisses
     * nach lokal installierten SNAPSHOT-Artefakten.
     *
     * @param verzeichnis das aktuell zu durchsuchende Verzeichnis
     * @param ergebnis    die Sammelliste fuer gefundene Treffer
     */
    private void durchsucheVerzeichnis(File verzeichnis, List<File> ergebnis) {
        File[] kinder = verzeichnis.listFiles();
        if (kinder == null) {
            return;
        }

        for (File kind : kinder) {
            if (!kind.isDirectory()) {
                continue;
            }

            if (istLokalerSnapshot(kind)) {
                ergebnis.add(kind);
            } else {
                durchsucheVerzeichnis(kind, ergebnis);
            }
        }
    }

    /**
     * Prueft, ob das angegebene Verzeichnis ein lokal installiertes
     * SNAPSHOT-Artefakt darstellt.
     *
     * <p>Die Pruefung basiert auf zwei Kriterien:</p>
     * <ol>
     *   <li>Der Verzeichnisname endet auf {@code -SNAPSHOT}</li>
     *   <li>Im Verzeichnis existiert eine {@code maven-metadata-local.xml}</li>
     * </ol>
     *
     * @param verzeichnis das zu pruefende Verzeichnis
     * @return {@code true} wenn beide Kriterien erfuellt sind
     */
    private boolean istLokalerSnapshot(File verzeichnis) {
        if (!verzeichnis.getName().endsWith(SNAPSHOT_SUFFIX)) {
            return false;
        }

        File metadataDatei = new File(verzeichnis, LOCAL_METADATA_FILE);
        return metadataDatei.exists() && metadataDatei.isFile();
    }

    /**
     * Loescht ein Verzeichnis und seinen gesamten Inhalt rekursiv.
     *
     * <p>Sammelt zunaechst alle Dateien und Verzeichnisse, sortiert sie
     * nach Pfadtiefe (tiefste zuerst) und loescht sie einzeln. Fuer jede
     * Datei bzw. jedes Verzeichnis werden bei Fehlschlag mehrere
     * Loeschversuche unternommen, um unter Windows typische
     * Dateisperren-Probleme zu umgehen.</p>
     *
     * <p>Zwischen den Versuchen wird {@link System#gc()} aufgerufen, um
     * den Garbage Collector anzustossen und so offene
     * {@link java.io.FileInputStream}-Handles oder aehnliche Ressourcen
     * freizugeben, die eine Dateisperre verursachen koennen.</p>
     *
     * @param verzeichnis der Pfad des zu loeschenden Verzeichnisses
     * @throws IOException wenn ein Fehler beim Loeschen auftritt, der auch
     *                     nach mehreren Versuchen nicht behoben werden konnte
     */
    private void loescheVerzeichnisRekursiv(Path verzeichnis) throws IOException {
        final List<Path> allePfade = new ArrayList<Path>();

        Files.walkFileTree(verzeichnis, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path datei, BasicFileAttributes attribute) {
                allePfade.add(datei);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path verz, IOException ausnahme)
                    throws IOException {
                if (ausnahme != null) {
                    throw ausnahme;
                }
                allePfade.add(verz);
                return FileVisitResult.CONTINUE;
            }
        });

        // Sortiere nach Pfadtiefe absteigend, damit Dateien und tiefere
        // Verzeichnisse vor ihren Elternverzeichnissen geloescht werden
        Collections.sort(allePfade, new Comparator<Path>() {
            @Override
            public int compare(Path a, Path b) {
                return Integer.compare(b.getNameCount(), a.getNameCount());
            }
        });

        for (Path pfad : allePfade) {
            loescheMitWiederholung(pfad);
        }
    }

    /**
     * Versucht eine einzelne Datei oder ein leeres Verzeichnis zu loeschen.
     *
     * <p>Falls der Loeschvorgang fehlschlaegt (z.B. weil die Datei unter
     * Windows noch von einem anderen Prozess gesperrt ist), werden bis zu
     * {@link #maxRetries} weitere Versuche unternommen. Zwischen den
     * Versuchen wird:</p>
     * <ol>
     *   <li>{@link System#gc()} aufgerufen, um offene Datei-Handles freizugeben</li>
     *   <li>eine kurze Wartezeit von {@value #WARTEZEIT_ZWISCHEN_VERSUCHEN_MS} ms eingelegt</li>
     * </ol>
     *
     * <p>Dieses Vorgehen ist notwendig, da auf Windows-Systemen Dateien
     * nicht geloescht werden koennen, solange sie von einem Prozess geoeffnet
     * sind. Der JVM Garbage Collector kann solche Handles schliessen, wenn
     * die zugehoerigen Stream-Objekte nicht mehr referenziert werden.</p>
     *
     * @param pfad der Pfad der zu loeschenden Datei oder des Verzeichnisses
     * @throws IOException wenn das Loeschen auch nach allen Versuchen fehlschlaegt
     */
    private void loescheMitWiederholung(Path pfad) throws IOException {
        IOException letzterFehler = null;

        for (int versuch = 1; versuch <= maxRetries; versuch++) {
            try {
                Files.deleteIfExists(pfad);
                return;
            } catch (IOException e) {
                letzterFehler = e;
                getLog().debug("Loeschversuch " + versuch + "/" + maxRetries
                        + " fehlgeschlagen fuer: " + pfad + " (" + e.getMessage() + ")");

                // Garbage Collector anstossen, um offene Datei-Handles freizugeben
                System.gc();

                try {
                    TimeUnit.MILLISECONDS.sleep(WARTEZEIT_ZWISCHEN_VERSUCHEN_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Loeschvorgang unterbrochen fuer: " + pfad, ie);
                }
            }
        }

        throw new IOException("Konnte Datei nach " + maxRetries
                + " Versuchen nicht loeschen: " + pfad
                + " (Ursache: " + letzterFehler.getMessage() + ")", letzterFehler);
    }

    /**
     * Entfernt die {@code maven-metadata-local.xml} auf Artefakt-Verzeichnis-Ebene.
     *
     * <p>Neben der Versionsverzeichnis-Ebene (z.B.
     * {@code com/company/mylib/1.0-SNAPSHOT/maven-metadata-local.xml})
     * legt Maven auch eine {@code maven-metadata-local.xml} auf der
     * uebergeordneten Artefakt-Ebene ab (z.B.
     * {@code com/company/mylib/maven-metadata-local.xml}). Diese Methode
     * entfernt diese Datei, sofern sie existiert.</p>
     *
     * @param artefaktVerzeichnis das Artefakt-Verzeichnis (Elternverzeichnis
     *                            des SNAPSHOT-Versionsverzeichnisses)
     */
    private void entferneArtefaktMetadata(File artefaktVerzeichnis) {
        File metadataDatei = new File(artefaktVerzeichnis, LOCAL_METADATA_FILE);
        if (metadataDatei.exists() && metadataDatei.isFile()) {
            if (metadataDatei.delete()) {
                getLog().info("Artefakt-Metadaten entfernt: " + metadataDatei.getAbsolutePath());
            } else {
                getLog().warn("Konnte Artefakt-Metadaten nicht entfernen: "
                        + metadataDatei.getAbsolutePath());
            }
        }
    }

    /**
     * Bereinigt leere Elternverzeichnisse nach dem Loeschen eines
     * SNAPSHOT-Verzeichnisses.
     *
     * <p>Wenn nach dem Loeschen eines SNAPSHOT-Versionsverzeichnisses
     * das uebergeordnete Artefakt-Verzeichnis leer ist, wird dieses
     * ebenfalls entfernt. Dieser Vorgang wird bis zum Basisverzeichnis
     * des Gruppen-Praefixes fortgesetzt.</p>
     *
     * @param elternVerzeichnis das zu pruefende Elternverzeichnis
     */
    private void bereinigeLeeresElternverzeichnis(File elternVerzeichnis) {
        File basisVerzeichnis = ermittleBasisverzeichnis();

        File aktuell = elternVerzeichnis;
        while (aktuell != null
                && !aktuell.equals(basisVerzeichnis)
                && aktuell.getAbsolutePath().startsWith(basisVerzeichnis.getAbsolutePath())) {

            String[] inhalt = aktuell.list();
            if (inhalt != null && inhalt.length == 0) {
                if (aktuell.delete()) {
                    getLog().info("Leeres Verzeichnis entfernt: " + aktuell.getAbsolutePath());
                }
                aktuell = aktuell.getParentFile();
            } else {
                break;
            }
        }
    }
}
