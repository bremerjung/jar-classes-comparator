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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
 * JAR-Artefakt mit einem oder mehreren JAR-Artefakten vergleicht und aus den
 * abweichenden Dateien ein ZIP-Archiv (Hotpatch) erstellt.
 *
 * <p>Das Mojo unterstuetzt drei Betriebsmodi:</p>
 * <ol>
 *   <li><b>Modus A – Repository-JAR vs. Repository-JAR(s):</b> Beide Seiten
 *       werden als Maven-Artefakt aufgeloest. Dazu {@code sourceVersion}
 *       (die "neue" Seite) und {@code compareVersions} (eine oder mehrere
 *       "alte" Seiten) angeben.</li>
 *   <li><b>Modus B – Lokales JAR vs. Repository-JAR(s):</b> Ein lokal
 *       vorhandenes JAR ({@code sourceJar}) wird gegen ein oder mehrere
 *       Repository-Artefakte ({@code compareVersions}) verglichen.</li>
 *   <li><b>Modus C – target/classes vs. Repository-JAR(s) (klassisch):</b>
 *       Wird weder {@code sourceVersion} noch {@code sourceJar} angegeben,
 *       dient {@code target/classes} als Quelle.</li>
 * </ol>
 *
 * <p>Bei mehreren Vergleichs-JARs gilt <b>UND-Logik</b>: Eine Datei wird nur
 * dann in den Hotpatch aufgenommen, wenn sie sich von <em>jeder</em> der
 * angegebenen Versionen unterscheidet oder in <em>keiner</em> davon vorhanden
 * ist. Stimmt sie mit auch nur einer Version ueberein, wird sie nicht in den
 * Patch aufgenommen.</p>
 *
 * <p>Dateien, die von Mavens Standard-Excludes abgedeckt werden (z.B.
 * SCM-Metadaten wie .git/, .svn/, CVS/ oder temporaere Dateien), werden beim
 * Vergleich automatisch uebersprungen.</p>
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
 *     <!-- Modus C: target/classes gegen mehrere Repository-JARs (UND-Logik) -->
 *     <compareVersions>
 *       <compareVersion>1.8.0</compareVersion>
 *       <compareVersion>1.9.0</compareVersion>
 *       <compareVersion>2.0.0</compareVersion>
 *     </compareVersions>
 *
 *     <!-- Modus A: Repository-JAR als Quelle -->
 *     <!-- <sourceVersion>2.1.0</sourceVersion> -->
 *
 *     <!-- Modus B: Lokales JAR als Quelle -->
 *     <!-- <sourceJar>${project.basedir}/libs/my-build.jar</sourceJar> -->
 *
 *   </configuration>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "make-hotpatch", defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class HotPatchMakerMojo3 extends AbstractMojo {

    /**
     * Das aktuelle Maven-Projekt. Wird verwendet, um groupId, artifactId
     * und Version auszulesen.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /**
     * Eine oder mehrere Versionen der Artefakte, gegen die verglichen werden soll
     * (die "alten" / Ziel-Seiten des Vergleichs).
     * <p>
     * Bei mehreren Versionen gilt UND-Logik: Eine Datei wird nur dann in den
     * Hotpatch aufgenommen, wenn sie sich von <em>jeder</em> angegebenen Version
     * unterscheidet oder in <em>keiner</em> davon vorhanden ist.
     * </p>
     * <p>
     * Wird dieser Parameter nicht angegeben, wird die Projektversion als einzige
     * Vergleichsversion verwendet.
     * </p>
     *
     * <p>Konfigurationsbeispiel:</p>
     * <pre>{@code
     * <compareVersions>
     *   <compareVersion>1.8.0</compareVersion>
     *   <compareVersion>1.9.0</compareVersion>
     * </compareVersions>
     * }</pre>
     */
    @Parameter(property = "hotpatch.compareVersions")
    List<String> compareVersions;

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

        // Vergleichsversionen bestimmen; Fallback auf Projektversion
        List<String> versionsToCompare = resolveCompareVersions();

        if (versionsToCompare.isEmpty()) {
            throw new MojoFailureException(
                    "Keine Vergleichsversionen konfiguriert. Bitte mindestens eine "
                            + "Version ueber <compareVersions> angeben oder die "
                            + "Projektversion setzen.");
        }

        try {
            // --- Quell-Inhalte bestimmen (neue / linke Seite) ---
            Map<String, byte[]> sourceContents;

            if (sourceVersion != null && !sourceVersion.trim().isEmpty()) {
                // Modus A: JAR aus dem Repository als Quelle
                File srcJar = resolveArtifact(groupId, artifactId, sourceVersion.trim());
                getLog().info("Hotpatch [Modus A]: Repository-JAR vs. Repository-JAR(s)");
                getLog().info("  Quelle (neu) : " + srcJar.getAbsolutePath());
                sourceContents = readJarContents(srcJar);

            } else if (sourceJar != null) {
                // Modus B: Lokale JAR-Datei als Quelle
                if (!sourceJar.isFile()) {
                    throw new MojoFailureException(
                            "sourceJar nicht gefunden: " + sourceJar.getAbsolutePath());
                }
                getLog().info("Hotpatch [Modus B]: Lokales JAR vs. Repository-JAR(s)");
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
                getLog().info("Hotpatch [Modus C]: target/classes vs. Repository-JAR(s)");
                getLog().info("  Quelle (neu) : " + classesDirectory.getAbsolutePath());
                sourceContents = readDirectoryContents(classesDirectory.toPath());
            }

            // --- Alle Ziel-JARs aufloesen und einlesen (alte / rechte Seite) ---
            List<Map<String, byte[]>> allTargetContents = new ArrayList<>();
            for (String version : versionsToCompare) {
                File targetJar = resolveArtifact(groupId, artifactId, version);
                getLog().info("  Ziel  (alt)  : " + targetJar.getAbsolutePath()
                        + "  [" + version + "]");
                allTargetContents.add(readJarContents(targetJar));
            }

            // --- Diff mit UND-Logik ueber alle Ziel-JARs ---
            Set<String> diffFiles = computeDiffAndLogic(sourceContents, allTargetContents);

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
    //  Konfigurationsauswertung
    // ------------------------------------------------------------------ //

    /**
     * Liefert die Liste der effektiven Vergleichsversionen.
     * <p>
     * Ist {@code compareVersions} konfiguriert, werden alle nicht-leeren Eintraege
     * verwendet. Andernfalls wird die Projektversion als einzige Vergleichsversion
     * zurueckgegeben.
     * </p>
     *
     * @return unveraenderliche Liste der Vergleichsversionen; niemals {@code null}
     */
    List<String> resolveCompareVersions() {
        if (compareVersions != null && !compareVersions.isEmpty()) {
            List<String> result = new ArrayList<>();
            for (String v : compareVersions) {
                if (v != null && !v.trim().isEmpty()) {
                    result.add(v.trim());
                }
            }
            if (!result.isEmpty()) {
                return Collections.unmodifiableList(result);
            }
        }
        // Fallback: Projektversion
        return Collections.singletonList(project.getVersion());
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
     * sodass SCM-Metadaten und temporaere Dateien automatisch uebersprungen werden.
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
     * werden uebersprungen.
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

                if (entry.isDirectory()) {
                    continue;
                }

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
     * Vergleicht eine Quell-Map gegen eine Liste von Ziel-Maps nach UND-Logik.
     * <p>
     * Eine Datei aus der Quelle wird in den Diff aufgenommen, wenn sie sich von
     * <em>jeder</em> Ziel-Map unterscheidet oder in <em>keiner</em> davon
     * vorhanden ist. Stimmt sie mit auch nur einer Ziel-Map ueberein, wird sie
     * <em>nicht</em> in den Patch aufgenommen.
     * </p>
     * <p>
     * Dateien, die nur in Ziel-Maps vorhanden sind (geloeschte Dateien aus
     * Quell-Sicht), werden nicht beruecksichtigt.
     * </p>
     *
     * @param source     Inhalte der neuen / linken Seite
     * @param allTargets Liste der Inhalte aller Ziel-JARs (alte / rechte Seite)
     * @return Menge der relativen Pfade, die in den Patch aufgenommen werden sollen
     */
    Set<String> computeDiffAndLogic(Map<String, byte[]> source,
                                    List<Map<String, byte[]>> allTargets) {
        Set<String> diff = new HashSet<>();

        for (Map.Entry<String, byte[]> entry : source.entrySet()) {
            String path     = entry.getKey();
            byte[] srcBytes = entry.getValue();

            // UND-Logik: Datei muss sich von JEDEM Ziel-JAR unterscheiden
            boolean differFromAll = true;

            for (Map<String, byte[]> target : allTargets) {
                byte[] tgtBytes = target.get(path);

                if (tgtBytes != null && contentEquals(srcBytes, tgtBytes)) {
                    // Datei stimmt mit diesem Ziel-JAR ueberein →
                    // UND-Bedingung nicht erfuellt, Datei kommt nicht in den Patch
                    differFromAll = false;
                    getLog().debug("GLEICH in mind. 1 JAR, uebersprungen: " + path);
                    break;
                }
            }

            if (differFromAll) {
                getLog().debug("IN ALLEN JARS ABWEICHEND: " + path);
                diff.add(path);
            }
        }

        return diff;
    }

    /**
     * Vergleicht zwei bereits eingelesene Maps (relativer Pfad → Bytes).
     * Delegiert intern an {@link #computeDiffAndLogic(Map, List)}.
     *
     * @param source  Inhalte der neuen / linken Seite
     * @param target  Inhalte der alten / rechten Seite
     * @return Menge der relativen Pfade abweichender Dateien
     * @deprecated Verwende {@link #computeDiffAndLogic(Map, List)} direkt,
     *             um mehrere Ziel-JARs zu unterstuetzen.
     */
    @Deprecated
    Set<String> computeDiffFromMaps(Map<String, byte[]> source,
                                    Map<String, byte[]> target) {
        return computeDiffAndLogic(source, Collections.singletonList(target));
    }

    /**
     * Ermittelt, welche Dateien in {@code classesDir} sich von denen im
     * uebergebenen JAR unterscheiden oder nur lokal vorhanden sind.
     *
     * @param classesDir das lokale classes-Verzeichnis (target/classes)
     * @param jarFile    die aufgeloeste JAR-Datei zum Vergleich
     * @return Menge der relativen Pfade abweichender Dateien
     * @throws IOException bei Lese- oder Dateisystemfehlern
     * @deprecated Verwende {@link #computeDiffAndLogic(Map, List)} direkt.
     */
    @Deprecated
    Set<String> computeDiff(File classesDir, File jarFile) throws IOException {
        return computeDiffAndLogic(
                readDirectoryContents(classesDir.toPath()),
                Collections.singletonList(readJarContents(jarFile)));
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
        if (Arrays.equals(localBytes, jarBytes)) {
            return true;
        }

        if (RawText.isBinary(localBytes) || RawText.isBinary(jarBytes)) {
            return false;
        }

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
