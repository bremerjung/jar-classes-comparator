package com.example.maven.hotpatch;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

/**
 * Maven-Plugin (Mojo), das den Inhalt von target/classes mit einem
 * JAR-Artefakt vergleicht, das ueber die Maven-Repository-Aufloesung
 * bezogen wird, und aus den abweichenden Dateien ein ZIP-Archiv
 * (Hotpatch) erstellt.
 * <p>
 * Dateien, die sich unterscheiden oder nur lokal vorhanden sind,
 * werden in das Hotpatch-ZIP aufgenommen.
 * </p>
 * <p>
 * Die groupId und artifactId des zu vergleichenden Artefakts werden
 * aus dem aktuellen Projekt ausgelesen. Die Version kann per Parameter
 * ueberschrieben werden; standardmaessig wird die Projektversion verwendet.
 * </p>
 *
 * <p>Verwendung in einer POM:</p>
 * <pre>{@code
 * <plugin>
 *   <groupId>com.example.maven</groupId>
 *   <artifactId>hotpatch-maven-plugin</artifactId>
 *   <version>1.0.0-SNAPSHOT</version>
 *   <executions>
 *     <execution>
 *       <goals>
 *         <goal>make-hotpatch</goal>
 *       </goals>
 *     </execution>
 *   </executions>
 *   <configuration>
 *     <!-- Optional: Version zum Vergleich angeben -->
 *     <compareVersion>1.0.0</compareVersion>
 *   </configuration>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "make-hotpatch", defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class HotPatchMakerMojo extends AbstractMojo {

    /**
     * Das aktuelle Maven-Projekt. Wird verwendet, um groupId, artifactId
     * und Version auszulesen.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Die Version des Artefakts, gegen das verglichen werden soll.
     * Wird dieser Parameter nicht angegeben, wird die Projektversion verwendet.
     */
    @Parameter(property = "hotpatch.compareVersion")
    private String compareVersion;

    /**
     * Das Build-Ausgabeverzeichnis (typischerweise target/classes).
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    private File classesDirectory;

    /**
     * Das Build-Verzeichnis (typischerweise target/).
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDirectory;

    /**
     * Der Name der Ausgabe-ZIP-Datei.
     */
    @Parameter(defaultValue = "hotpatch.zip", property = "hotpatch.outputFileName")
    private String outputFileName;

    // -- Aether-Komponenten fuer die Artefakt-Aufloesung --

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> remoteRepositories;

    // -----------------------------------------------

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        // 1. Koordinaten ermitteln
        String groupId = project.getGroupId();
        String artifactId = project.getArtifactId();
        String version = (compareVersion != null && !compareVersion.trim().isEmpty())
                ? compareVersion.trim()
                : project.getVersion();

        getLog().info("Hotpatch: Vergleiche target/classes mit Artefakt "
                + groupId + ":" + artifactId + ":" + version);

        // 2. JAR aus dem Repository aufloesen
        File jarFile = resolveArtifact(groupId, artifactId, version);
        getLog().info("Artefakt-JAR aufgeloest: " + jarFile.getAbsolutePath());

        // 3. Pruefen, ob target/classes existiert
        if (!classesDirectory.isDirectory()) {
            throw new MojoFailureException(
                    "Classes-Verzeichnis existiert nicht: " + classesDirectory.getAbsolutePath()
                            + " – wurde 'compile' ausgefuehrt?");
        }

        // 4. Vergleich durchfuehren
        try {
            Set<String> diffFiles = computeDiff(classesDirectory.toPath(), jarFile);

            if (diffFiles.isEmpty()) {
                getLog().info("Keine Unterschiede gefunden – es wird kein Hotpatch erstellt.");
                return;
            }

            getLog().info(diffFiles.size() + " abweichende Datei(en) gefunden.");

            // 5. Hotpatch-ZIP erstellen
            File zipFile = new File(buildDirectory, outputFileName);
            createZip(classesDirectory.toPath(), diffFiles, zipFile);

            getLog().info("Hotpatch erstellt: " + zipFile.getAbsolutePath());

        } catch (IOException e) {
            throw new MojoExecutionException("Fehler bei der Hotpatch-Erstellung", e);
        }
    }

    // ------------------------------------------------------------------ //
    //  Artefakt-Aufloesung ueber Aether / Maven Repository System
    // ------------------------------------------------------------------ //

    /**
     * Loest ein JAR-Artefakt ueber die konfigurierten Maven-Repositories auf.
     *
     * @param groupId    die GroupId des Artefakts
     * @param artifactId die ArtifactId des Artefakts
     * @param version    die Version des Artefakts
     * @return die aufgeloeste JAR-Datei im lokalen Repository
     * @throws MojoExecutionException falls das Artefakt nicht aufgeloest werden kann
     */
    private File resolveArtifact(String groupId, String artifactId, String version)
            throws MojoExecutionException {

        Artifact artifact = new DefaultArtifact(groupId, artifactId, "jar", version);
        ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(artifact);
        request.setRepositories(remoteRepositories);

        try {
            ArtifactResult result = repoSystem.resolveArtifact(repoSession, request);
            return result.getArtifact().getFile();
        } catch (ArtifactResolutionException e) {
            throw new MojoExecutionException(
                    "Artefakt konnte nicht aufgeloest werden: "
                            + groupId + ":" + artifactId + ":" + version, e);
        }
    }

    // ------------------------------------------------------------------ //
    //  Diff-Logik
    // ------------------------------------------------------------------ //

    /**
     * Ermittelt, welche Dateien in {@code classesDir} sich von denen im
     * uebergebenen JAR unterscheiden oder nur lokal vorhanden sind.
     *
     * @param classesDir das lokale classes-Verzeichnis (target/classes)
     * @param jarFile    die aufgeloeste JAR-Datei zum Vergleich
     * @return Menge der relativen Pfade (mit "/" als Separator) abweichender Dateien
     * @throws IOException bei Lese- oder Dateisystemfehlern
     */
    private Set<String> computeDiff(Path classesDir, File jarFile) throws IOException {

        Set<String> diffPaths = new HashSet<>();

        // Alle Eintraege aus dem JAR in den Speicher lesen (Byte-Arrays)
        Map<String, byte[]> jarContents = readJarContents(jarFile);

        // target/classes durchlaufen und jede Datei vergleichen
        Files.walkFileTree(classesDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relativePath = classesDir.relativize(file).toString().replace('\\', '/');

                byte[] localBytes = Files.readAllBytes(file);
                byte[] jarBytes = jarContents.get(relativePath);

                if (jarBytes == null) {
                    // Datei existiert nur in target/classes (fehlt im JAR)
                    getLog().debug("NEU       : " + relativePath);
                    diffPaths.add(relativePath);
                } else if (!contentEquals(localBytes, jarBytes)) {
                    // Dateiinhalt unterscheidet sich
                    getLog().debug("GEAENDERT : " + relativePath);
                    diffPaths.add(relativePath);
                } else {
                    getLog().debug("UNVERAEND.: " + relativePath);
                }

                return FileVisitResult.CONTINUE;
            }
        });

        return diffPaths;
    }

    /**
     * Liest alle Eintraege aus einer JAR-Datei in eine Map
     * (relativer Pfad → Byte-Inhalt).
     *
     * @param jarFile die zu lesende JAR-Datei
     * @return Map mit relativem Pfad als Schluessel und Dateiinhalt als Byte-Array
     * @throws IOException bei Lesefehlern
     */
    private Map<String, byte[]> readJarContents(File jarFile) throws IOException {

        Map<String, byte[]> contents = new HashMap<>();

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                // Verzeichnisse ueberspringen
                if (entry.isDirectory()) {
                    continue;
                }

                try (InputStream is = jar.getInputStream(entry)) {
                    contents.put(entry.getName(), toByteArray(is));
                }
            }
        }

        return contents;
    }

    // ------------------------------------------------------------------ //
    //  Hilfsmethoden
    // ------------------------------------------------------------------ //

    /**
     * Vergleicht zwei Byte-Arrays auf inhaltliche Gleichheit.
     * <p>
     * Nutzt JGits {@link RawText#isBinary(byte[])} zur Erkennung von Binaerdateien:
     * <ul>
     *   <li><b>Binaerdateien</b>: strikter Byte-fuer-Byte-Vergleich
     *       ueber {@link Arrays#equals}</li>
     *   <li><b>Textdateien</b>: zeilenweiser Vergleich mit JGits {@link HistogramDiff}
     *       und {@link RawTextComparator#WS_IGNORE_TRAILING}. Dadurch werden
     *       \r\n und \n als gleichwertig behandelt (nachgestellte Whitespace-Zeichen
     *       inkl. \r werden ignoriert)</li>
     * </ul>
     * Damit entfaellt die Pflege einer manuellen Liste von Textdatei-Endungen.
     * </p>
     *
     * @param localBytes Byte-Inhalt der lokalen Datei (target/classes)
     * @param jarBytes   Byte-Inhalt der Datei aus dem JAR
     * @return {@code true} wenn die Inhalte als gleich gelten, sonst {@code false}
     */
    private boolean contentEquals(byte[] localBytes, byte[] jarBytes) {
        // Schneller Pfad: byte-identische Dateien
        if (Arrays.equals(localBytes, jarBytes)) {
            return true;
        }

        // Falls eine Seite binaer ist: exakter Byte-Vergleich erforderlich
        // (der oben bereits fehlgeschlagen ist)
        if (RawText.isBinary(localBytes) || RawText.isBinary(jarBytes)) {
            return false;
        }

        // Text-Vergleich: Zeilenende-Unterschiede (\r\n vs. \n) ignorieren
        RawText localText = new RawText(localBytes);
        RawText jarText = new RawText(jarBytes);
        EditList edits = new HistogramDiff().diff(RawTextComparator.WS_IGNORE_TRAILING, localText, jarText);
        return edits.isEmpty();
    }

    /**
     * Liest alle Bytes aus einem InputStream in ein Byte-Array.
     * Kompatibel mit Java 8+ (Ersatz fuer InputStream.readAllBytes()).
     *
     * @param is der zu lesende InputStream
     * @return der vollstaendige Inhalt als Byte-Array
     * @throws IOException bei Lesefehlern
     */
    private byte[] toByteArray(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int bytesRead;
        while ((bytesRead = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, bytesRead);
        }
        return buffer.toByteArray();
    }

    // ------------------------------------------------------------------ //
    //  ZIP-Erstellung
    // ------------------------------------------------------------------ //

    /**
     * Erstellt ein ZIP-Archiv (Hotpatch), das nur die Dateien enthaelt,
     * deren relative Pfade in {@code relativePaths} aufgefuehrt sind.
     * Die Dateien werden aus {@code classesDir} gelesen.
     *
     * @param classesDir    das Quellverzeichnis (target/classes)
     * @param relativePaths Menge der relativen Pfade der zu packenden Dateien
     * @param zipFile       die zu erstellende ZIP-Datei
     * @throws IOException bei Schreib- oder Dateisystemfehlern
     */
    private void createZip(Path classesDir, Set<String> relativePaths, File zipFile) throws IOException {

        // Sicherstellen, dass das Elternverzeichnis existiert
        zipFile.getParentFile().mkdirs();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (String relPath : relativePaths) {
                Path sourceFile = classesDir.resolve(relPath);
                if (!Files.exists(sourceFile)) {
                    getLog().warn("Datei vor dem Packen verschwunden: " + relPath);
                    continue;
                }

                zos.putNextEntry(new ZipEntry(relPath));
                Files.copy(sourceFile, zos);
                zos.closeEntry();
            }
        }
    }
}
