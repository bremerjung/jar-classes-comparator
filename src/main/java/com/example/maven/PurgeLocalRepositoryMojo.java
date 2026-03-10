package com.example.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Maven-Goal zum Bereinigen des lokalen Maven Repositories.
 *
 * <p>Entfernt alle lokal installierten SNAPSHOT-Versionen unterhalb der
 * Gruppenkennung {@code com.company}. Als Erkennungsmerkmal fuer eine
 * lokale Installation dient die Existenz der Datei
 * {@code maven-metadata-local.xml} im jeweiligen Versionsverzeichnis.</p>
 *
 * <p>Verwendung:</p>
 * <pre>
 *   mvn com.company.maven.plugins:purge-local-repo-maven-plugin:purge-local-repository
 * </pre>
 *
 * @since 1.0.0
 */
@Mojo(name = "purge-local-repository", requiresProject = false)
public class PurgeLocalRepositoryMojo extends AbstractMojo {

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
     * Name der Metadaten-Datei, die als Erkennungsmerkmal fuer eine
     * lokale Installation verwendet wird.
     */
    private static final String LOCAL_METADATA_FILE = "maven-metadata-local.xml";

    /**
     * Suffix fuer SNAPSHOT-Versionsverzeichnisse.
     */
    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    /**
     * Fuehrt das Goal aus.
     *
     * <p>Durchsucht das lokale Maven Repository unterhalb des konfigurierten
     * Gruppen-Praefixes nach SNAPSHOT-Versionsverzeichnissen, die eine
     * {@code maven-metadata-local.xml} enthalten, und loescht diese
     * vollstaendig.</p>
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

        int erfolgreich = 0;
        int fehlgeschlagen = 0;

        for (File verzeichnis : zuLoeschendeVerzeichnisse) {
            if (dryRun) {
                getLog().info("[TROCKENLAUF] Wuerde loeschen: " + verzeichnis.getAbsolutePath());
            } else {
                try {
                    loescheVerzeichnisRekursiv(verzeichnis.toPath());
                    getLog().info("Geloescht: " + verzeichnis.getAbsolutePath());
                    erfolgreich++;
                    bereinigeLeeresElternverzeichnis(verzeichnis.getParentFile());
                } catch (IOException e) {
                    getLog().warn("Fehler beim Loeschen von "
                            + verzeichnis.getAbsolutePath() + ": " + e.getMessage());
                    fehlgeschlagen++;
                }
            }
        }

        if (!dryRun) {
            getLog().info("Bereinigung abgeschlossen: " + erfolgreich
                    + " geloescht, " + fehlgeschlagen + " fehlgeschlagen.");
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
     * <p>Verwendet {@link SimpleFileVisitor} um alle Dateien und
     * Unterverzeichnisse in der korrekten Reihenfolge zu entfernen
     * (Dateien vor Verzeichnissen).</p>
     *
     * @param verzeichnis der Pfad des zu loeschenden Verzeichnisses
     * @throws IOException wenn ein Fehler beim Loeschen auftritt
     */
    private void loescheVerzeichnisRekursiv(Path verzeichnis) throws IOException {
        Files.walkFileTree(verzeichnis, new SimpleFileVisitor<Path>() {

            /**
             * {@inheritDoc}
             *
             * <p>Loescht die besuchte Datei.</p>
             */
            @Override
            public FileVisitResult visitFile(Path datei, BasicFileAttributes attribute)
                    throws IOException {
                Files.delete(datei);
                return FileVisitResult.CONTINUE;
            }

            /**
             * {@inheritDoc}
             *
             * <p>Loescht das Verzeichnis nachdem alle Inhalte entfernt wurden.</p>
             */
            @Override
            public FileVisitResult postVisitDirectory(Path verz, IOException ausnahme)
                    throws IOException {
                if (ausnahme != null) {
                    throw ausnahme;
                }
                Files.delete(verz);
                return FileVisitResult.CONTINUE;
            }
        });
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
