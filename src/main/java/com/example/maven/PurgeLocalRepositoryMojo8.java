package com.example.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * Entfernt lokal installierte SNAPSHOT-Versionen aus dem lokalen Maven-Repository.
 *
 * <p>Als Erkennungsmerkmal dient die Datei {@code maven-metadata-local.xml}.
 * Erfasst werden nur Verzeichnisse unterhalb des konfigurierten Gruppen-Präfixes,
 * deren Versionsordner auf {@code -SNAPSHOT} enden und diese Metadaten-Datei
 * enthalten.</p>
 *
 * <p>Die ermittelten Pfade werden in eine Textdatei geschrieben. Anschliessend
 * wird ein separater Java-Prozess ({@link DeferredDeleter}) gestartet, der die
 * Pfade mit Retry-Logik löscht. So werden auch Dateien erfasst, die von der
 * aktuellen JVM noch kurzzeitig gesperrt sind.</p>
 */
@Mojo(name = "purge-local-repository", requiresProject = false)
public class PurgeLocalRepositoryMojo8 extends AbstractMojo {

    private static final String METADATA_FILE = "maven-metadata-local.xml";

    /**
     * Pfad zum lokalen Maven-Repository.
     * Standard: Wert aus {@code settings.xml}.
     */
    @Parameter(property = "localRepository", defaultValue = "${settings.localRepository}", required = true)
    private File localRepository;

    /**
     * Gruppen-Präfix, unterhalb dessen gesucht wird (Punkt-Notation).
     */
    @Parameter(property = "groupPrefix", defaultValue = "com.company")
    private String groupPrefix;

    /**
     * Trockenlauf – wenn {@code true}, wird nichts gelöscht, sondern nur geloggt.
     */
    @Parameter(property = "dryRun", defaultValue = "false")
    private boolean dryRun;

    @Override
    public void execute() throws MojoExecutionException {
        Path basePath = localRepository.toPath()
                .resolve(groupPrefix.replace('.', File.separatorChar));

        if (!Files.isDirectory(basePath)) {
            getLog().info("Verzeichnis existiert nicht, nichts zu tun: " + basePath);
            return;
        }

        getLog().info("Durchsuche: " + basePath);
        List<Path> targets = collectTargets(basePath);

        if (targets.isEmpty()) {
            getLog().info("Keine lokal installierten SNAPSHOT-Artefakte gefunden.");
            return;
        }

        for (Path p : targets) {
            getLog().info("  Gefunden: " + p);
        }
        getLog().info(targets.size() + " Pfad(e) zum Löschen ermittelt.");

        if (dryRun) {
            getLog().info("[TROCKENLAUF] Es wird nichts gelöscht.");
            return;
        }

        startDeferredDeleter(targets);
    }

    // -----------------------------------------------------------------------
    // Sammeln
    // -----------------------------------------------------------------------

    /**
     * Sammelt alle zu löschenden Pfade: SNAPSHOT-Versionsordner mit
     * {@code maven-metadata-local.xml} sowie die gleichnamige Datei
     * auf Artefakt-Ebene (Elternverzeichnis).
     */
    private List<Path> collectTargets(Path basePath) throws MojoExecutionException {
        List<Path> result = new ArrayList<>();

        try {
            Files.walkFileTree(basePath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (isSnapshotVersionDir(dir) && hasLocalMetadata(dir)) {
                        result.add(dir);
                        collectArtifactLevelMetadata(dir.getParent(), result);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new MojoExecutionException("Fehler beim Durchsuchen von " + basePath, e);
        }

        return result;
    }

    private boolean isSnapshotVersionDir(Path dir) {
        return dir.getFileName().toString().endsWith("-SNAPSHOT");
    }

    private boolean hasLocalMetadata(Path dir) {
        return Files.exists(dir.resolve(METADATA_FILE));
    }

    private void collectArtifactLevelMetadata(Path artifactDir, List<Path> paths) {
        Path metaFile = artifactDir.resolve(METADATA_FILE);
        if (Files.exists(metaFile) && !paths.contains(metaFile)) {
            paths.add(metaFile);
        }
    }

    // -----------------------------------------------------------------------
    // Lösch-Prozess starten
    // -----------------------------------------------------------------------

    /**
     * Schreibt die Pfadliste in eine temporäre Datei und startet den
     * {@link DeferredDeleter} als separaten Java-Prozess.
     */
    private void startDeferredDeleter(List<Path> targets) throws MojoExecutionException {
        try {
            Path purgeList = writePurgeList(targets);

            String java = System.getProperty("java.home")
                    + File.separator + "bin" + File.separator + "java";
            String classpath = getClass().getProtectionDomain()
                    .getCodeSource().getLocation().getPath();

            new ProcessBuilder(java, "-cp", classpath,
                    DeferredDeleter.class.getName(),
                    purgeList.toAbsolutePath().toString())
                    .inheritIO()
                    .start();

            getLog().info("Lösch-Prozess gestartet.");
        } catch (IOException e) {
            throw new MojoExecutionException("Fehler beim Starten des Lösch-Prozesses", e);
        }
    }

    /**
     * Schreibt die Pfade in eine temporäre Textdatei (ein Pfad pro Zeile).
     */
    private Path writePurgeList(List<Path> targets) throws IOException {
        Path purgeList = Files.createTempFile("purge-paths-", ".txt");
        List<String> lines = new ArrayList<>();
        for (Path p : targets) {
            lines.add(p.toAbsolutePath().toString());
        }
        Files.write(purgeList, lines, StandardCharsets.UTF_8);
        return purgeList;
    }

    // -----------------------------------------------------------------------
    // Eigenständiger Lösch-Prozess
    // -----------------------------------------------------------------------

    /**
     * Eigenständiges Programm, das als separater Java-Prozess gestartet wird.
     * Liest eine Textdatei mit zu löschenden Pfaden (einer pro Zeile) und
     * löscht diese mit Retry-Logik. Gesperrte Dateien werden in mehreren
     * Versuchen mit Pause erneut probiert. Am Ende wird die Pfadliste
     * selbst gelöscht.
     */
    public static class DeferredDeleter {

        private static final int MAX_RETRIES = 10;
        private static final long RETRY_PAUSE_MS = 1000L;

        /**
         * Einstiegspunkt – erwartet den Pfad zur Pfadliste als Argument.
         */
        public static void main(String[] args) throws Exception {
            if (args.length != 1) {
                System.err.println("Verwendung: DeferredDeleter <pfadliste>");
                System.exit(1);
            }

            Path purgeList = new File(args[0]).toPath();
            List<String> lines = Files.readAllLines(purgeList, StandardCharsets.UTF_8);
            List<String> failed = new ArrayList<>();

            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    if (!deleteWithRetry(new File(trimmed).toPath())) {
                        failed.add(trimmed);
                    }
                }
            }

            Files.deleteIfExists(purgeList);

            if (failed.isEmpty()) {
                System.out.println("Alle Pfade erfolgreich gelöscht.");
            } else {
                System.err.println(failed.size() + " Pfad(e) konnten nicht gelöscht werden:");
                for (String f : failed) {
                    System.err.println("  " + f);
                }
                System.exit(1);
            }
        }

        /**
         * Löscht einen Pfad (Datei oder Verzeichnis) mit bis zu
         * {@value MAX_RETRIES} Versuchen und {@value RETRY_PAUSE_MS}ms Pause.
         *
         * @return {@code true} bei Erfolg, {@code false} nach Ausschöpfen aller Versuche.
         */
        private static boolean deleteWithRetry(Path path) throws InterruptedException {
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    if (Files.isDirectory(path)) {
                        deleteRecursively(path);
                    } else {
                        Files.deleteIfExists(path);
                    }
                    System.out.println("Gelöscht: " + path);
                    return true;
                } catch (IOException e) {
                    System.out.println("Versuch " + attempt + "/" + MAX_RETRIES
                            + " fehlgeschlagen: " + path + " (" + e.getMessage() + ")");
                    if (attempt < MAX_RETRIES) {
                        Thread.sleep(RETRY_PAUSE_MS);
                    }
                }
            }
            return false;
        }

        /**
         * Löscht ein Verzeichnis rekursiv (Dateien zuerst, dann Ordner).
         */
        private static void deleteRecursively(Path dir) throws IOException {
            Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path d, IOException exc)
                        throws IOException {
                    Files.delete(d);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}

