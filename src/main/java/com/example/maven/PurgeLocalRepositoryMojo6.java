package com.example.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
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
 * Entfernt lokal installierte SNAPSHOT-Versionen aus dem lokalen Maven-Repository.
 *
 * <p>Als Erkennungsmerkmal für lokal installierte Artefakte dient die Datei
 * {@code maven-metadata-local.xml}. Gelöscht werden nur Verzeichnisse unterhalb
 * des konfigurierten Gruppen-Präfixes, deren Versionsordner auf {@code -SNAPSHOT}
 * enden und eine solche Metadaten-Datei enthalten.</p>
 *
 * <p>Das eigentliche Löschen erfolgt in einem separaten Prozess, damit auch
 * Artefakte entfernt werden können, die von der aktuellen JVM gesperrt sind
 * (z.&nbsp;B. das Plugin selbst).</p>
 */
@Mojo(name = "purge-local-repository", requiresProject = false)
public class PurgeLocalRepositoryMojo6 extends AbstractMojo {

    private static final String METADATA_FILE = "maven-metadata-local.xml";

    /**
     * Pfad zum lokalen Maven-Repository.
     * Standard: Wert aus {@code settings.xml} ({@code ${settings.localRepository}}).
     */
    @Parameter(property = "localRepository", defaultValue = "${settings.localRepository}", required = true)
    private File localRepository;

    /**
     * Gruppen-Präfix, unterhalb dessen gesucht wird (Punkt-Notation).
     * Wird intern in einen Verzeichnispfad umgewandelt.
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
        Path basePath = localRepository.toPath().resolve(groupPrefix.replace('.', File.separatorChar));

        if (!Files.isDirectory(basePath)) {
            getLog().info("Verzeichnis existiert nicht, nichts zu tun: " + basePath);
            return;
        }

        getLog().info("Durchsuche: " + basePath);
        List<Path> pathsToDelete = collectPathsToDelete(basePath);

        if (pathsToDelete.isEmpty()) {
            getLog().info("Keine lokal installierten SNAPSHOT-Artefakte gefunden.");
            return;
        }

        if (dryRun) {
            getLog().info("[TROCKENLAUF] Folgende Pfade würden gelöscht:");
            pathsToDelete.forEach(p -> getLog().info("  " + p));
        } else {
            deleteInSeparateProcess(pathsToDelete);
        }
    }

    /**
     * Sammelt alle zu löschenden Pfade: SNAPSHOT-Versionsordner mit
     * {@code maven-metadata-local.xml} sowie die gleichnamige Datei
     * auf Artefakt-Ebene.
     */
    private List<Path> collectPathsToDelete(Path basePath) throws MojoExecutionException {
        List<Path> result = new ArrayList<>();

        try {
            // Artefakt-Ebene = zwei Stufen unter basePath (groupId-Pfad/artifactId)
            // Versions-Ebene = drei Stufen (groupId-Pfad/artifactId/version)
            Files.walkFileTree(basePath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (isSnapshotVersionDir(dir) && hasLocalMetadata(dir)) {
                        result.add(dir);
                        addArtifactLevelMetadata(dir.getParent(), result);
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

    /**
     * Prüft, ob der Ordnername auf {@code -SNAPSHOT} endet.
     */
    private boolean isSnapshotVersionDir(Path dir) {
        return dir.getFileName().toString().endsWith("-SNAPSHOT");
    }

    /**
     * Prüft, ob im Verzeichnis eine {@code maven-metadata-local.xml} liegt.
     */
    private boolean hasLocalMetadata(Path dir) {
        return Files.exists(dir.resolve(METADATA_FILE));
    }

    /**
     * Fügt die {@code maven-metadata-local.xml} auf Artefakt-Ebene zur
     * Löschliste hinzu, sofern vorhanden und noch nicht enthalten.
     */
    private void addArtifactLevelMetadata(Path artifactDir, List<Path> paths) {
        Path metaFile = artifactDir.resolve(METADATA_FILE);
        if (Files.exists(metaFile) && !paths.contains(metaFile)) {
            paths.add(metaFile);
        }
    }

    /**
     * Startet einen entkoppelten Betriebssystem-Prozess, der zunächst einige Sekunden
     * wartet (damit die JVM sich beenden kann) und anschließend die gesammelten Pfade
     * löscht. Unter Windows wird zusätzlich ein Retry eingebaut, da Dateisperren
     * erst mit dem JVM-Ende freigegeben werden.
     *
     * <p>Der Prozess läuft im Hintergrund (Fire-and-Forget) – es wird bewusst
     * nicht auf dessen Beendigung gewartet, da die aktuelle JVM die Dateien
     * sonst noch sperrt.</p>
     */
    private void deleteInSeparateProcess(List<Path> paths) throws MojoExecutionException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        StringBuilder script = new StringBuilder();

        if (windows) {
            // Warte 3 Sekunden, damit die JVM sich beenden kann
            script.append("timeout /t 3 /nobreak >nul & ");
            for (Path path : paths) {
                String absolute = path.toAbsolutePath().toString();
                script.append(Files.isDirectory(path)
                        ? "rmdir /s /q \"" + absolute + "\" & "
                        : "del /f /q \"" + absolute + "\" & ");
            }
        } else {
            script.append("sleep 3 ; ");
            for (Path path : paths) {
                String absolute = path.toAbsolutePath().toString();
                script.append(Files.isDirectory(path)
                        ? "rm -rf '" + absolute + "' ; "
                        : "rm -f '" + absolute + "' ; ");
            }
        }

        paths.forEach(p -> getLog().info("  Wird gelöscht (nach JVM-Ende): " + p));

        String[] command = windows
                ? new String[]{"cmd", "/c", "start /b " + script.toString()}
                : new String[]{"sh", "-c", script.toString() + " &"};

        try {
            new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            getLog().info("Lösch-Prozess gestartet (läuft im Hintergrund nach JVM-Ende).");
        } catch (IOException e) {
            throw new MojoExecutionException("Fehler beim Starten des Lösch-Prozesses", e);
        }
    }
}

