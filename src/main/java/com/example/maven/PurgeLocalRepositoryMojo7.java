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
 * Ermittelt lokal installierte SNAPSHOT-Versionen im lokalen Maven-Repository
 * und schreibt die zu löschenden Pfade in eine Textdatei (ein Pfad pro Zeile).
 *
 * <p>Als Erkennungsmerkmal dient die Datei {@code maven-metadata-local.xml}.
 * Erfasst werden nur Verzeichnisse unterhalb des konfigurierten Gruppen-Präfixes,
 * deren Versionsordner auf {@code -SNAPSHOT} enden und diese Metadaten-Datei
 * enthalten.</p>
 *
 * <p>Das eigentliche Löschen übernimmt ein separates Skript
 * ({@code purge-local-repository.cmd} / {@code .sh}), das die Pfadliste
 * einliest. So werden JVM-Locks umgangen.</p>
 */
@Mojo(name = "purge-local-repository", requiresProject = false)
public class PurgeLocalRepositoryMojo7 extends AbstractMojo {

    private static final String METADATA_FILE = "maven-metadata-local.xml";
    private static final String DEFAULT_PURGE_LIST = "purge-paths.txt";

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
     * Zielpfad für die Textdatei mit den zu löschenden Pfaden.
     * Standard: {@value DEFAULT_PURGE_LIST} im aktuellen Verzeichnis.
     */
    @Parameter(property = "purgeList", defaultValue = "${project.basedir}/" + DEFAULT_PURGE_LIST)
    private File purgeList;

    /**
     * Trockenlauf – wenn {@code true}, wird die Pfadliste nicht geschrieben,
     * sondern nur geloggt, was gelöscht werden würde.
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
            getLog().info("[TROCKENLAUF] Pfadliste wird nicht geschrieben.");
            return;
        }

        writePurgeList(targets);
    }

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

    /**
     * Schreibt die gesammelten Pfade in die Textdatei (ein Pfad pro Zeile).
     */
    private void writePurgeList(List<Path> targets) throws MojoExecutionException {
        try {
            List<String> lines = new ArrayList<>();
            for (Path p : targets) {
                lines.add(p.toAbsolutePath().toString());
            }
            Files.write(purgeList.toPath(), lines, StandardCharsets.UTF_8);
            getLog().info("Pfadliste geschrieben: " + purgeList.getAbsolutePath());
        } catch (IOException e) {
            throw new MojoExecutionException("Fehler beim Schreiben der Pfadliste", e);
        }
    }
}

