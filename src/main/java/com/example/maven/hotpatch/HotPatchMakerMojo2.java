package com.example.maven.hotpatch;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.SelectorUtils;
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
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Maven-Plugin (Mojo), das entweder den Inhalt von target/classes oder ein
 * JAR-Artefakt mit einem zweiten JAR-Artefakt vergleicht, das ueber die
 * Maven-Repository-Aufloesung bezogen wird, und aus den abweichenden Dateien
 * ein ZIP-Archiv (Hotpatch) erstellt.
 *
 * <p>Das Mojo unterstuetzt drei Betriebsmodi:</p>
 * <ol>
 *   <li><b>Modus A – Repository-JAR vs. Repository-JAR:</b> Beide Seiten
 *       werden als Maven-Artefakt aufgeloest. Dazu {@code sourceVersion}
 *       (die "neue" Seite) und {@code compareVersion} (die "alte" Seite)
 *       angeben.</li>
 *   <li><b>Modus B – Lokales JAR vs. Repository-JAR:</b> Ein lokal
 *       vorhandenes JAR ({@code sourceJar}) wird gegen ein Repository-Artefakt
 *       ({@code compareVersion}) verglichen.</li>
 *   <li><b>Modus C – target/classes vs. Repository-JAR (klassisch):</b>
 *       Wird weder {@code sourceVersion} noch {@code sourceJar} angegeben,
 *       dient {@code target/classes} als Quelle.</li>
 * </ol>
 *
 * <p>Dateien, die sich unterscheiden oder nur in der Quelle vorhanden sind,
 * werden in das Hotpatch-ZIP aufgenommen. Dateien, die von Mavens
 * Standard-Excludes abgedeckt werden (z.B. SCM-Metadaten wie .git/,
 * .svn/, CVS/ oder temporaere Dateien), werden beim Vergleich
 * automatisch uebersprungen.</p>
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
 *
 *     <!-- Modus A: Zwei Repository-JARs vergleichen -->
 *     <sourceVersion>2.0.0</sourceVersion>    <!-- "neu" -->
 *     <compareVersion>1.9.0</compareVersion>  <!-- "alt" -->
 *
 *     <!-- Modus B: Lokales JAR vs. Repository-JAR -->
 *     <!-- <sourceJar>${project.basedir}/libs/my-build.jar</sourceJar> -->
 *     <!-- <compareVersion>1.9.0</compareVersion>                      -->
 *
 *     <!-- Modus C: Klassisch – target/classes vs. Repository-JAR -->
 *     <!-- <compareVersion>1.9.0</compareVersion>                  -->
 *
 *   </configuration>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "make-hotpatch", defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class HotPatchMakerMojo2 extends AbstractMojo {

    /**
     * Das aktuelle Maven-Projekt. Wird verwendet, um groupId, artifactId
     * und Version auszulesen.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /**
     * Die Version des Artefakts, gegen das verglichen werden soll
     * (die "alte" / Ziel-Seite des Vergleichs).
     * Wird dieser Parameter nicht angegeben, wird die Projektversion verwendet.
     */
    @Parameter(property = "hotpatch.compareVersion")
    String compareVersion;

    /**
     * Version eines Maven-Artefakts (gleiche groupId/artifactId wie das Projekt),
     * das als Quelle des Vergleichs dient (die "neue" Seite).
     * <p>
     * Wird dieser Parameter gesetzt, wird das entsprechende JAR aus dem
     * Repository aufgeloest und anstelle von {@code target/classes} verwendet.
     * Hat Vorrang vor {@code sourceJar}.
     * </p>
     */
    @Parameter(property = "hotpatch.sourceVersion")
    String sourceVersion;

    /**
     * Pfad zu einem lokalen JAR, das als Vergleichsquelle (die "neue" Seite)
     * verwendet wird.
     * <p>
     * Wird dieser Parameter gesetzt und {@code sourceVersion} ist <em>nicht</em>
     * gesetzt, wird dieses lokale JAR anstelle von {@code target/classes} als
     * Quelle verwendet.
     * </p>
     */
    @Parameter(property = "hotpatch.sourceJar")
    File sourceJar;

    /**
     * Das Build-Ausgabeverzeichnis (typischerweise target/classes).
     * Wird nur im klassischen Modus (Modus C) verwendet.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    File classesDirectory;

    /**
     * Das Build-Verzeichnis (typischerweise target/).
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    File buildDirectory;

    /**
     * Der Name der Ausgabe-ZIP-Datei.
     */
    @Parameter(defaultValue = "hotpatch.zip", property = "hotpatch.outputFileName")
    String outputFileName;

    // -- Aether-Komponenten fuer die Artefakt-Aufloesung --

    @Component
    RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    List<RemoteRepository> remoteRepositories;

    // -----------------------------------------------

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        String groupId    = project.getGroupId();
        String artifactId = project.getArtifactId();
        String targetVersion = (compareVersion != null && !compareVersion.trim().isEmpty())
                ? compareVersion.trim()
                : project.getVersion();

        try {
            // --- Quell-Inhalte bestimmen (neue / linke Seite) ---
            Map<String, byte[]> sourceContents;

            if (sourceVersion != null && !sourceVersion.trim().isEmpty()) {
                // Modus A: JAR aus dem Repository als Quelle
                File srcJar = resolveArtifact(groupId, artifactId, sourceVersion.trim());
                getLog().info("Hotpatch [Modus A]: Repository-JAR vs. Repository-JAR");
                getLog().info("  Quelle (neu) : " + srcJar.getAbsolutePath());
                sourceContents = readJarContents(srcJar);

            } else if (sourceJar != null) {
                // Modus B: Lokale JAR-Datei als Quelle
                if (!sourceJar.isFile()) {
                    throw new MojoFailureException(
                            "sourceJar nicht gefunden: " + sourceJar.getAbsolutePath());
                }
                getLog().info("Hotpatch [Modus B]: Lokales JAR vs. Repository-JAR");
                getLog().info("  Quelle (neu) : " + sourceJar.getAbsolutePath());
                sourceContents = readJarContents(sourceJar);

            } else {
                // Modus C: Klassisch – target/classes als Quelle
                if (!classesDirectory.isDirectory()) {
                    throw new MojoFailureException(
                            "Classes-Verzeichnis existiert nicht: "
                                    + classesDirectory.getAbsolutePath()
                                    + " – wurde 'compile' ausgefuehrt?");
                }
                getLog().info("Hotpatch [Modus C]: target/classes vs. Repository-JAR");
                getLog().info("  Quelle (neu) : " + classesDirectory.getAbsolutePath());
                sourceContents = readDirectoryContents(classesDirectory.toPath());
            }

            // --- Ziel-JAR aufloesen (alte / rechte Seite) ---
            File targetJar = resolveArtifact(groupId, artifactId, targetVersion);
            getLog().info("  Ziel  (alt)  : " + targetJar.getAbsolutePath());

            Map<String, byte[]> targetContents = readJarContents(targetJar);
            Set<String> diffFiles = computeDiffFromMaps(sourceContents, targetContents);

            if (diffFiles.isEmpty()) {
                getLog().info("Keine Unterschiede gefunden – es wird kein Hotpatch erstellt.");
                return;
            }

            getLog().info(diffFiles.size() + " abweichende Datei(en) gefunden.");

            File zipFile = new File(buildDirectory, outputFileName);
            createZipFromMap(sourceContents, diffFiles, zipFile);
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
    File resolveArtifact(String groupId, String artifactId, String version)
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
    //  Inhalte einlesen
    // ------------------------------------------------------------------ //

    /**
     * Liest alle Dateien aus einem Verzeichnis in eine Map
     * (relativer Pfad → Byte-Inhalt).
     * <p>
     * Verwendet {@link DirectoryScanner} mit {@code addDefaultExcludes()},
     * sodass SCM-Metadaten und temporaere Dateien automatisch uebersprungen
     * werden – identisches Verhalten wie beim JAR-Einlesen.
     * </p>
     *
     * @param baseDir das einzulesende Verzeichnis (z.B. target/classes)
     * @return Map mit relativem Pfad als Schluessel und Dateiinhalt als Byte-Array
     * @throws IOException bei Lesefehlern
     */
    Map<String, byte[]> readDirectoryContents(Path baseDir) throws IOException {
        Map<String, byte[]> contents = new HashMap<>();

        DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(baseDir.toFile());
        scanner.setIncludes(new String[]{"**"});
        scanner.addDefaultExcludes();
        scanner.scan();

        for (String file : scanner.getIncludedFiles()) {
            String key = file.replace('\\', '/');
            contents.put(key, Files.readAllBytes(baseDir.resolve(file)));
        }
        return contents;
    }

    /**
     * Liest alle Eintraege aus einer JAR-Datei in eine Map
     * (relativer Pfad → Byte-Inhalt).
     * <p>
     * Eintraege, die von Mavens Standard-Excludes erfasst werden,
     * werden uebersprungen. Dazu wird jeder JAR-Eintrag gegen die
     * Patterns aus {@link DirectoryScanner#DEFAULTEXCLUDES} geprueft.
     * </p>
     *
     * @param jarFile die zu lesende JAR-Datei
     * @return Map mit relativem Pfad als Schluessel und Dateiinhalt als Byte-Array
     * @throws IOException bei Lesefehlern
     */
    Map<String, byte[]> readJarContents(File jarFile) throws IOException {

        Map<String, byte[]> contents = new HashMap<>();

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                // Verzeichnisse ueberspringen
                if (entry.isDirectory()) {
                    continue;
                }

                // Standard-Excludes auf JAR-Eintraege anwenden
                if (isDefaultExcluded(entry.getName())) {
                    getLog().debug("EXCLUDE   : " + entry.getName());
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
    //  Diff-Logik
    // ------------------------------------------------------------------ //

    /**
     * Vergleicht zwei bereits eingelesene Maps (relativer Pfad → Bytes) und
     * gibt die Menge der Pfade zurueck, die sich unterscheiden oder nur in der
     * Quelle vorhanden sind.
     * <p>
     * Dateien, die nur im Ziel (target), aber nicht in der Quelle existieren,
     * werden als geloescht betrachtet und <em>nicht</em> in den Patch
     * aufgenommen.
     * </p>
     *
     * @param source  Inhalte der neuen / linken Seite
     * @param target  Inhalte der alten / rechten Seite
     * @return Menge der relativen Pfade abweichender Dateien
     */
    Set<String> computeDiffFromMaps(Map<String, byte[]> source,
                                    Map<String, byte[]> target) {
        Set<String> diff = new HashSet<>();

        for (Map.Entry<String, byte[]> entry : source.entrySet()) {
            String path     = entry.getKey();
            byte[] srcBytes = entry.getValue();
            byte[] tgtBytes = target.get(path);

            if (tgtBytes == null) {
                getLog().debug("NEU       : " + path);
                diff.add(path);
            } else if (!contentEquals(srcBytes, tgtBytes)) {
                getLog().debug("GEAENDERT : " + path);
                diff.add(path);
            } else {
                getLog().debug("UNVERAEND.: " + path);
            }
        }

        return diff;
    }

    /**
     * Ermittelt, welche Dateien in {@code classesDir} sich von denen im
     * uebergebenen JAR unterscheiden oder nur lokal vorhanden sind.
     * <p>
     * Diese Methode bleibt als kompatibler Einstiegspunkt erhalten und
     * delegiert intern an {@link #readDirectoryContents(Path)} und
     * {@link #computeDiffFromMaps(Map, Map)}.
     * </p>
     *
     * @param classesDir das lokale classes-Verzeichnis (target/classes)
     * @param jarFile    die aufgeloeste JAR-Datei zum Vergleich
     * @return Menge der relativen Pfade abweichender Dateien
     * @throws IOException bei Lese- oder Dateisystemfehlern
     * @deprecated Wird intern nicht mehr direkt aufgerufen; stattdessen werden
     *             {@link #readDirectoryContents(Path)}, {@link #readJarContents(File)}
     *             und {@link #computeDiffFromMaps(Map, Map)} verwendet.
     */
    @Deprecated
    Set<String> computeDiff(File classesDir, File jarFile) throws IOException {
        return computeDiffFromMaps(
                readDirectoryContents(classesDir.toPath()),
                readJarContents(jarFile));
    }

    // ------------------------------------------------------------------ //
    //  Hilfsmethoden
    // ------------------------------------------------------------------ //

    /**
     * Prueft, ob ein relativer Pfad von Mavens Standard-Excludes erfasst wird.
     * <p>
     * Verwendet {@link SelectorUtils#matchPath(String, String)} aus Plexus Utils,
     * um den Pfad gegen jedes Pattern aus {@link DirectoryScanner#DEFAULTEXCLUDES}
     * zu testen.
     * </p>
     *
     * @param relativePath der zu pruefende relative Pfad (mit "/" als Separator)
     * @return {@code true} wenn der Pfad ausgeschlossen werden soll
     */
    boolean isDefaultExcluded(String relativePath) {
        for (String pattern : DirectoryScanner.DEFAULTEXCLUDES) {
            if (SelectorUtils.matchPath(pattern, relativePath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vergleicht zwei Byte-Arrays auf inhaltliche Gleichheit.
     * <p>
     * Nutzt JGits {@link RawText#isBinary(byte[])} zur Erkennung von Binaerdateien:
     * <ul>
     *   <li><b>Binaerdateien</b>: strikter Byte-fuer-Byte-Vergleich
     *       ueber {@link Arrays#equals}</li>
     *   <li><b>Textdateien</b>: zeilenweiser Vergleich mit JGits {@link HistogramDiff}
     *       und {@link RawTextComparator#WS_IGNORE_TRAILING}. Dadurch werden
     *       \r\n und \n als gleichwertig behandelt.</li>
     * </ul>
     * </p>
     *
     * @param localBytes Byte-Inhalt der Quelle
     * @param jarBytes   Byte-Inhalt des Ziels
     * @return {@code true} wenn die Inhalte als gleich gelten, sonst {@code false}
     */
    boolean contentEquals(byte[] localBytes, byte[] jarBytes) {
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
        RawText jarText   = new RawText(jarBytes);
        EditList edits = new HistogramDiff().diff(
                RawTextComparator.WS_IGNORE_TRAILING, localText, jarText);
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
    byte[] toByteArray(InputStream is) throws IOException {
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
     * Erstellt ein ZIP-Archiv (Hotpatch) direkt aus der In-Memory-Map der
     * Quelldateien. Kein Dateisystemzugriff auf {@code target/classes}
     * erforderlich – funktioniert daher fuer alle drei Modi gleichermassen.
     *
     * @param source        Map der Quellinhalte (relativer Pfad → Bytes)
     * @param relativePaths Menge der in das ZIP aufzunehmenden Pfade
     * @param zipFile       die zu erstellende ZIP-Datei
     * @throws IOException bei Schreib- oder Dateisystemfehlern
     */
    void createZipFromMap(Map<String, byte[]> source,
                          Set<String> relativePaths,
                          File zipFile) throws IOException {

        zipFile.getParentFile().mkdirs();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (String relPath : relativePaths) {
                byte[] data = source.get(relPath);
                if (data == null) {
                    getLog().warn("Kein Inhalt fuer Pfad verfuegbar: " + relPath);
                    continue;
                }
                zos.putNextEntry(new ZipEntry(relPath));
                zos.write(data);
                zos.closeEntry();
            }
        }
    }

    /**
     * Erstellt ein ZIP-Archiv aus Dateien im Dateisystem.
     * <p>
     * Diese Methode bleibt als kompatibler Fallback erhalten, wird intern
     * jedoch nicht mehr aufgerufen. Stattdessen wird {@link #createZipFromMap}
     * verwendet, das keine Dateisystemzugriffe benoetigt.
     * </p>
     *
     * @param classesDir    das Quellverzeichnis (target/classes)
     * @param relativePaths Menge der relativen Pfade der zu packenden Dateien
     * @param zipFile       die zu erstellende ZIP-Datei
     * @throws IOException bei Schreib- oder Dateisystemfehlern
     * @deprecated Ersetzt durch {@link #createZipFromMap(Map, Set, File)}.
     */
    @Deprecated
    void createZip(Path classesDir, Set<String> relativePaths, File zipFile) throws IOException {

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
