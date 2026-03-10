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
public class PurgeLocalRepositoryMojo4 extends AbstractMojo {

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
     * Startet einen separaten Java-Prozess, der die gesammelten Pfade löscht.
     * So können auch von der aktuellen JVM gesperrte Dateien entfernt werden.
     */
    private void deleteInSeparateProcess(List<Path> paths) throws MojoExecutionException {
        try {
            String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";

            // Inline-Programm als -e-Argument (über Umweg mit Quelldatei, da Java 8)
            Path script = Files.createTempFile("purge-", ".java");
            Files.write(script, buildDeleteScript(paths).getBytes("UTF-8"));

            // javac + java in einem Prozess
            Path classDir = Files.createTempDirectory("purge-classes-");
            run(new String[]{java + "c", "-d", classDir.toString(), script.toString()});
            run(new String[]{java, "-cp", classDir.toString(), "PurgeScript"});

            getLog().info("Löschvorgang abgeschlossen.");
        } catch (IOException | InterruptedException e) {
            throw new MojoExecutionException("Fehler beim Starten des Lösch-Prozesses", e);
        }
    }

    /**
     * Führt einen externen Prozess aus und wartet auf dessen Beendigung.
     */
    private void run(String[] command) throws IOException, InterruptedException, MojoExecutionException {
        Process process = new ProcessBuilder(command)
                .inheritIO()
                .start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new MojoExecutionException("Externer Prozess beendet mit Exit-Code " + exitCode);
        }
    }

    /**
     * Erzeugt den Quelltext eines kleinen Java-Programms, das die übergebenen
     * Pfade rekursiv löscht.
     */
    private String buildDeleteScript(List<Path> paths) {
        StringBuilder sb = new StringBuilder();
        sb.append("import java.io.*;\n");
        sb.append("import java.nio.file.*;\n");
        sb.append("import java.nio.file.attribute.*;\n\n");
        sb.append("public class PurgeScript {\n");
        sb.append("    public static void main(String[] args) throws Exception {\n");
        sb.append("        String[] targets = {\n");

        for (int i = 0; i < paths.size(); i++) {
            // Pfade mit doppeltem Backslash escapen (Windows-Kompatibilität)
            String escaped = paths.get(i).toAbsolutePath().toString().replace("\\", "\\\\");
            sb.append("            \"").append(escaped).append("\"");
            if (i < paths.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append("        };\n");
        sb.append("        for (String target : targets) {\n");
        sb.append("            Path path = Paths.get(target);\n");
        sb.append("            if (Files.isDirectory(path)) {\n");
        sb.append("                deleteRecursively(path);\n");
        sb.append("            } else if (Files.exists(path)) {\n");
        sb.append("                System.out.println(\"Loesche Datei: \" + path);\n");
        sb.append("                Files.delete(path);\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
        sb.append("    private static void deleteRecursively(Path dir) throws Exception {\n");
        sb.append("        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {\n");
        sb.append("            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {\n");
        sb.append("                System.out.println(\"Loesche Datei: \" + f);\n");
        sb.append("                Files.delete(f);\n");
        sb.append("                return FileVisitResult.CONTINUE;\n");
        sb.append("            }\n");
        sb.append("            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {\n");
        sb.append("                System.out.println(\"Loesche Verzeichnis: \" + d);\n");
        sb.append("                Files.delete(d);\n");
        sb.append("                return FileVisitResult.CONTINUE;\n");
        sb.append("            }\n");
        sb.append("        });\n");
        sb.append("    }\n");
        sb.append("}\n");

        return sb.toString();
    }
}
