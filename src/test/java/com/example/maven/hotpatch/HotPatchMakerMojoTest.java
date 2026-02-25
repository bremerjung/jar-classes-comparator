package com.example.maven.hotpatch;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * JUnit-5-Tests fuer {@link HotPatchMakerMojo}.
 * <p>
 * Die Tests decken folgende Bereiche ab:
 * <ul>
 *   <li>Inhaltlicher Vergleich (Text, Binaer, Zeilenende-Toleranz)</li>
 *   <li>Standard-Excludes (SCM-Metadaten, temporaere Dateien)</li>
 *   <li>JAR-Einlesen mit Exclude-Filterung</li>
 *   <li>Diff-Erkennung (neu, geaendert, unveraendert)</li>
 *   <li>ZIP-Erstellung</li>
 *   <li>Fehlerbehandlung (fehlendes classes-Verzeichnis, nicht aufloesbares Artefakt)</li>
 *   <li>Versions-Fallback auf Projektversion</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class HotPatchMakerMojoTest {

    @TempDir
    Path tempDir;

    @Mock
    private MavenProject project;

    @Mock
    private RepositorySystem repoSystem;

    @Mock
    private RepositorySystemSession repoSession;

    @Mock
    private Log log;

    private HotPatchMakerMojo mojo;

    @BeforeEach
    void setUp() throws Exception {
        mojo = new HotPatchMakerMojo();
        mojo.setLog(log);
        setField(mojo, "project", project);
        setField(mojo, "repoSystem", repoSystem);
        setField(mojo, "repoSession", repoSession);
        setField(mojo, "remoteRepositories", new ArrayList<RemoteRepository>());
    }

    // ================================================================== //
    //  Tests fuer contentEquals
    // ================================================================== //

    @Test
    void contentEquals_identischeDateien_gibtTrueZurueck() throws Exception {
        byte[] content = "Hallo Welt".getBytes(StandardCharsets.UTF_8);
        assertTrue(invokeContentEquals(content, content.clone()));
    }

    @Test
    void contentEquals_unterschiedlicherInhalt_gibtFalseZurueck() throws Exception {
        byte[] a = "Hallo".getBytes(StandardCharsets.UTF_8);
        byte[] b = "Welt".getBytes(StandardCharsets.UTF_8);
        assertFalse(invokeContentEquals(a, b));
    }

    @Test
    void contentEquals_nurZeilenendeUnterschied_CRLF_vs_LF_gibtTrueZurueck() throws Exception {
        byte[] crlf = "Zeile1\r\nZeile2\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] lf = "Zeile1\nZeile2\n".getBytes(StandardCharsets.UTF_8);
        assertTrue(invokeContentEquals(crlf, lf));
    }

    @Test
    void contentEquals_textUndZeilenendeUnterschied_gibtFalseZurueck() throws Exception {
        byte[] a = "Zeile1\r\nZeile2\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] b = "Zeile1\nAndereZeile\n".getBytes(StandardCharsets.UTF_8);
        assertFalse(invokeContentEquals(a, b));
    }

    @Test
    void contentEquals_binaerDateien_identisch_gibtTrueZurueck() throws Exception {
        // Binaerdaten mit Null-Bytes (wird von RawText.isBinary erkannt)
        byte[] binary = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, 0x00};
        assertTrue(invokeContentEquals(binary, binary.clone()));
    }

    @Test
    void contentEquals_binaerDateien_unterschiedlich_gibtFalseZurueck() throws Exception {
        byte[] a = new byte[]{0x00, 0x01, 0x02};
        byte[] b = new byte[]{0x00, 0x01, 0x03};
        assertFalse(invokeContentEquals(a, b));
    }

    @Test
    void contentEquals_leereDateien_gibtTrueZurueck() throws Exception {
        assertTrue(invokeContentEquals(new byte[0], new byte[0]));
    }

    // ================================================================== //
    //  Tests fuer isDefaultExcluded
    // ================================================================== //

    @Test
    void isDefaultExcluded_gitVerzeichnis_gibtTrueZurueck() throws Exception {
        assertTrue(invokeIsDefaultExcluded(".git/config"));
    }

    @Test
    void isDefaultExcluded_svnVerzeichnis_gibtTrueZurueck() throws Exception {
        assertTrue(invokeIsDefaultExcluded(".svn/entries"));
    }

    @Test
    void isDefaultExcluded_cvsignore_gibtTrueZurueck() throws Exception {
        assertTrue(invokeIsDefaultExcluded(".cvsignore"));
    }

    @Test
    void isDefaultExcluded_cvsVerzeichnis_gibtTrueZurueck() throws Exception {
        assertTrue(invokeIsDefaultExcluded("CVS/Root"));
    }

    @Test
    void isDefaultExcluded_dsStore_gibtTrueZurueck() throws Exception {
        assertTrue(invokeIsDefaultExcluded(".DS_Store"));
    }

    @Test
    void isDefaultExcluded_tildeDatei_gibtTrueZurueck() throws Exception {
        assertTrue(invokeIsDefaultExcluded("config.xml~"));
    }

    @Test
    void isDefaultExcluded_normaleDatei_gibtFalseZurueck() throws Exception {
        assertFalse(invokeIsDefaultExcluded("com/example/App.class"));
    }

    @Test
    void isDefaultExcluded_propertiesDatei_gibtFalseZurueck() throws Exception {
        assertFalse(invokeIsDefaultExcluded("application.properties"));
    }

    @Test
    void isDefaultExcluded_verschachtelterPfad_gibtFalseZurueck() throws Exception {
        assertFalse(invokeIsDefaultExcluded("com/example/resources/config.xml"));
    }

    // ================================================================== //
    //  Tests fuer readJarContents (mit Exclude-Filterung)
    // ================================================================== //

    @Test
    void readJarContents_liestNormaleEintraege() throws Exception {
        File jar = erstelleTestJar(
                eintrag("com/example/App.class", "bytecode"),
                eintrag("application.properties", "key=value")
        );

        Map<String, byte[]> contents = invokeReadJarContents(jar);

        assertEquals(2, contents.size());
        assertTrue(contents.containsKey("com/example/App.class"));
        assertTrue(contents.containsKey("application.properties"));
    }

    @Test
    void readJarContents_filtertStandardExcludes() throws Exception {
        File jar = erstelleTestJar(
                eintrag("application.properties", "key=value"),
                eintrag(".git/config", "gitconfig"),
                eintrag(".svn/entries", "svndata"),
                eintrag(".DS_Store", "dsstore"),
                eintrag("config.xml~", "backup")
        );

        Map<String, byte[]> contents = invokeReadJarContents(jar);

        assertEquals(1, contents.size());
        assertTrue(contents.containsKey("application.properties"));
        assertFalse(contents.containsKey(".git/config"));
        assertFalse(contents.containsKey(".svn/entries"));
        assertFalse(contents.containsKey(".DS_Store"));
        assertFalse(contents.containsKey("config.xml~"));
    }

    @Test
    void readJarContents_leereJarDatei_gibtLeereMapZurueck() throws Exception {
        File jar = erstelleTestJar();

        Map<String, byte[]> contents = invokeReadJarContents(jar);

        assertTrue(contents.isEmpty());
    }

    // ================================================================== //
    //  Tests fuer computeDiff
    // ================================================================== //

    @Test
    void computeDiff_identischerInhalt_gibtLeereMengeZurueck() throws Exception {
        // Lokale Dateien anlegen
        Path classesDir = tempDir.resolve("classes-identical");
        erstelleDatei(classesDir, "config.properties", "key=value");
        erstelleDatei(classesDir, "com/example/App.class", "bytecode");

        // JAR mit identischem Inhalt
        File jar = erstelleTestJar(
                eintrag("config.properties", "key=value"),
                eintrag("com/example/App.class", "bytecode")
        );

        Set<String> diff = invokeComputeDiff(classesDir.toFile(), jar);

        assertTrue(diff.isEmpty());
    }

    @Test
    void computeDiff_geaenderteDatei_wirdErkannt() throws Exception {
        Path classesDir = tempDir.resolve("classes-modified");
        erstelleDatei(classesDir, "config.properties", "key=neuerWert");

        File jar = erstelleTestJar(
                eintrag("config.properties", "key=alterWert")
        );

        Set<String> diff = invokeComputeDiff(classesDir.toFile(), jar);

        assertEquals(1, diff.size());
        assertTrue(diff.contains("config.properties"));
    }

    @Test
    void computeDiff_neueDatei_wirdErkannt() throws Exception {
        Path classesDir = tempDir.resolve("classes-new");
        erstelleDatei(classesDir, "config.properties", "key=value");
        erstelleDatei(classesDir, "neue-datei.xml", "<root/>");

        File jar = erstelleTestJar(
                eintrag("config.properties", "key=value")
        );

        Set<String> diff = invokeComputeDiff(classesDir.toFile(), jar);

        assertEquals(1, diff.size());
        assertTrue(diff.contains("neue-datei.xml"));
    }

    @Test
    void computeDiff_zeilenendeUnterschied_wirdIgnoriert() throws Exception {
        Path classesDir = tempDir.resolve("classes-lineending");
        erstelleDatei(classesDir, "config.properties", "zeile1\r\nzeile2\r\n");

        File jar = erstelleTestJar(
                eintrag("config.properties", "zeile1\nzeile2\n")
        );

        Set<String> diff = invokeComputeDiff(classesDir.toFile(), jar);

        assertTrue(diff.isEmpty());
    }

    @Test
    void computeDiff_standardExcludesWerdenUebersprungen() throws Exception {
        Path classesDir = tempDir.resolve("classes-excludes");
        erstelleDatei(classesDir, "config.properties", "key=value");
        erstelleDatei(classesDir, ".git/config", "lokale git config");
        erstelleDatei(classesDir, ".DS_Store", "ds store inhalt");

        File jar = erstelleTestJar(
                eintrag("config.properties", "key=value")
        );

        Set<String> diff = invokeComputeDiff(classesDir.toFile(), jar);

        // .git/config und .DS_Store sollten vom Scanner ignoriert werden
        assertTrue(diff.isEmpty());
    }

    @Test
    void computeDiff_mehrereDiffs_werdenAlleErkannt() throws Exception {
        Path classesDir = tempDir.resolve("classes-multi");
        erstelleDatei(classesDir, "a.txt", "geaendert");
        erstelleDatei(classesDir, "b.txt", "original");
        erstelleDatei(classesDir, "c.txt", "neu");

        File jar = erstelleTestJar(
                eintrag("a.txt", "original"),
                eintrag("b.txt", "original")
        );

        Set<String> diff = invokeComputeDiff(classesDir.toFile(), jar);

        assertEquals(2, diff.size());
        assertTrue(diff.contains("a.txt"));
        assertTrue(diff.contains("c.txt"));
        assertFalse(diff.contains("b.txt"));
    }

    // ================================================================== //
    //  Tests fuer createZip
    // ================================================================== //

    @Test
    void createZip_erstelltZipMitAngegebenenDateien() throws Exception {
        Path classesDir = tempDir.resolve("classes-zip");
        erstelleDatei(classesDir, "a.txt", "Inhalt A");
        erstelleDatei(classesDir, "sub/b.xml", "Inhalt B");

        Set<String> relativePaths = Set.of("a.txt", "sub/b.xml");
        File zipFile = tempDir.resolve("output.zip").toFile();

        invokeCreateZip(classesDir, relativePaths, zipFile);

        assertTrue(zipFile.exists());

        // ZIP-Inhalt pruefen
        try (ZipFile zip = new ZipFile(zipFile)) {
            assertNotNull(zip.getEntry("a.txt"));
            assertNotNull(zip.getEntry("sub/b.xml"));
            assertEquals(2, zip.size());
        }
    }

    @Test
    void createZip_inhaltStimmtUeberein() throws Exception {
        Path classesDir = tempDir.resolve("classes-zip-content");
        String expectedContent = "Testinhalt fuer ZIP";
        erstelleDatei(classesDir, "test.txt", expectedContent);

        Set<String> relativePaths = Set.of("test.txt");
        File zipFile = tempDir.resolve("content-check.zip").toFile();

        invokeCreateZip(classesDir, relativePaths, zipFile);

        try (ZipFile zip = new ZipFile(zipFile)) {
            ZipEntry entry = zip.getEntry("test.txt");
            assertNotNull(entry);
            byte[] content = zip.getInputStream(entry).readAllBytes();
            assertEquals(expectedContent, new String(content, StandardCharsets.UTF_8));
        }
    }

    @Test
    void createZip_leereMenge_erstelltLeeresZip() throws Exception {
        Path classesDir = tempDir.resolve("classes-zip-empty");
        Files.createDirectories(classesDir);

        Set<String> relativePaths = Set.of();
        File zipFile = tempDir.resolve("empty.zip").toFile();

        invokeCreateZip(classesDir, relativePaths, zipFile);

        assertTrue(zipFile.exists());
        try (ZipFile zip = new ZipFile(zipFile)) {
            assertEquals(0, zip.size());
        }
    }

    // ================================================================== //
    //  Tests fuer execute (Integrationsnaher Test mit Mocks)
    // ================================================================== //

    @Test
    void execute_fehlendesClassesVerzeichnis_wirftMojoFailureException() throws Exception {
        File nichtExistent = tempDir.resolve("nicht-vorhanden").toFile();
        setField(mojo, "classesDirectory", nichtExistent);
        setField(mojo, "buildDirectory", tempDir.toFile());
        setField(mojo, "compareVersion", "1.0.0");

        when(project.getGroupId()).thenReturn("com.example");
        when(project.getArtifactId()).thenReturn("test-project");

        // Artefakt-Aufloesung mocken
        File dummyJar = erstelleTestJar(eintrag("dummy.txt", "dummy"));
        ArtifactResult result = new ArtifactResult(new ArtifactRequest());
        DefaultArtifact resolved = new DefaultArtifact("com.example", "test-project", "jar", "1.0.0");
        result.setArtifact(resolved.setFile(dummyJar));
        when(repoSystem.resolveArtifact(any(), any())).thenReturn(result);

        assertThrows(MojoFailureException.class, () -> mojo.execute());
    }

    @Test
    void execute_keineUnterschiedeGefunden_erstelltKeinZip() throws Exception {
        Path classesDir = tempDir.resolve("classes-exec-nodiff");
        erstelleDatei(classesDir, "config.txt", "inhalt");

        File jar = erstelleTestJar(eintrag("config.txt", "inhalt"));
        File buildDir = tempDir.resolve("target-nodiff").toFile();
        buildDir.mkdirs();

        konfiguriereMojoFuerExecute(classesDir, buildDir, jar, "1.0.0");

        mojo.execute();

        File zipFile = new File(buildDir, "hotpatch.zip");
        assertFalse(zipFile.exists());
    }

    @Test
    void execute_unterschiedeGefunden_erstelltHotpatchZip() throws Exception {
        Path classesDir = tempDir.resolve("classes-exec-diff");
        erstelleDatei(classesDir, "config.txt", "neuer Inhalt");
        erstelleDatei(classesDir, "neu.txt", "ganz neu");

        File jar = erstelleTestJar(eintrag("config.txt", "alter Inhalt"));
        File buildDir = tempDir.resolve("target-diff").toFile();
        buildDir.mkdirs();

        konfiguriereMojoFuerExecute(classesDir, buildDir, jar, "1.0.0");

        mojo.execute();

        File zipFile = new File(buildDir, "hotpatch.zip");
        assertTrue(zipFile.exists());

        try (ZipFile zip = new ZipFile(zipFile)) {
            assertNotNull(zip.getEntry("config.txt"));
            assertNotNull(zip.getEntry("neu.txt"));
            assertEquals(2, zip.size());
        }
    }

    @Test
    void execute_ohneExpliziteVersion_nutztProjektversion() throws Exception {
        Path classesDir = tempDir.resolve("classes-exec-defaultver");
        erstelleDatei(classesDir, "app.txt", "inhalt");

        File jar = erstelleTestJar(eintrag("app.txt", "inhalt"));
        File buildDir = tempDir.resolve("target-defaultver").toFile();
        buildDir.mkdirs();

        // compareVersion NICHT setzen → null → Fallback auf Projektversion
        konfiguriereMojoFuerExecute(classesDir, buildDir, jar, null);
        when(project.getVersion()).thenReturn("2.0.0-SNAPSHOT");

        mojo.execute();

        // Sicherstellen, dass getVersion aufgerufen wurde (Fallback-Logik)
        verify(project).getVersion();
    }

    @Test
    void execute_artefaktNichtAufloesbar_wirftMojoExecutionException() throws Exception {
        Path classesDir = tempDir.resolve("classes-exec-noresolve");
        erstelleDatei(classesDir, "dummy.txt", "inhalt");

        setField(mojo, "classesDirectory", classesDir.toFile());
        setField(mojo, "buildDirectory", tempDir.toFile());
        setField(mojo, "outputFileName", "hotpatch.zip");
        setField(mojo, "compareVersion", "1.0.0");

        when(project.getGroupId()).thenReturn("com.example");
        when(project.getArtifactId()).thenReturn("test-project");
        when(repoSystem.resolveArtifact(any(), any()))
                .thenThrow(new ArtifactResolutionException(new ArrayList<>()));

        assertThrows(MojoExecutionException.class, () -> mojo.execute());
    }

    // ================================================================== //
    //  Hilfsmethoden: Reflection-Zugriff auf private Methoden/Felder
    // ================================================================== //

    private boolean invokeContentEquals(byte[] local, byte[] jar) throws Exception {
        Method m = HotPatchMakerMojo.class.getDeclaredMethod("contentEquals", byte[].class, byte[].class);
        m.setAccessible(true);
        return (boolean) m.invoke(mojo, local, jar);
    }

    private boolean invokeIsDefaultExcluded(String path) throws Exception {
        Method m = HotPatchMakerMojo.class.getDeclaredMethod("isDefaultExcluded", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(mojo, path);
    }

    @SuppressWarnings("unchecked")
    private Map<String, byte[]> invokeReadJarContents(File jarFile) throws Exception {
        Method m = HotPatchMakerMojo.class.getDeclaredMethod("readJarContents", File.class);
        m.setAccessible(true);
        return (Map<String, byte[]>) m.invoke(mojo, jarFile);
    }

    @SuppressWarnings("unchecked")
    private Set<String> invokeComputeDiff(File classesDir, File jarFile) throws Exception {
        Method m = HotPatchMakerMojo.class.getDeclaredMethod("computeDiff", File.class, File.class);
        m.setAccessible(true);
        return (Set<String>) m.invoke(mojo, classesDir, jarFile);
    }

    private void invokeCreateZip(Path classesDir, Set<String> paths, File zipFile) throws Exception {
        Method m = HotPatchMakerMojo.class.getDeclaredMethod("createZip", Path.class, Set.class, File.class);
        m.setAccessible(true);
        m.invoke(mojo, classesDir, paths, zipFile);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ================================================================== //
    //  Hilfsmethoden: Test-Infrastruktur
    // ================================================================== //

    /**
     * Erstellt eine Datei mit dem angegebenen Inhalt im temporaeren Verzeichnis.
     * Legt bei Bedarf auch Unterverzeichnisse an.
     */
    private void erstelleDatei(Path baseDir, String relativePath, String inhalt) throws IOException {
        Path file = baseDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, inhalt.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Erstellt eine Test-JAR-Datei mit den angegebenen Eintraegen.
     */
    private File erstelleTestJar(TestJarEintrag... eintraege) throws IOException {
        File jarFile = tempDir.resolve("test-" + System.nanoTime() + ".jar").toFile();
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile))) {
            for (TestJarEintrag e : eintraege) {
                jos.putNextEntry(new JarEntry(e.pfad));
                jos.write(e.inhalt);
                jos.closeEntry();
            }
        }
        return jarFile;
    }

    /**
     * Erstellt einen Test-JAR-Eintrag mit Pfad und Textinhalt.
     */
    private static TestJarEintrag eintrag(String pfad, String inhalt) {
        return new TestJarEintrag(pfad, inhalt.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Konfiguriert das Mojo mit allen noetigen Feldern fuer einen execute()-Aufruf.
     */
    private void konfiguriereMojoFuerExecute(Path classesDir, File buildDir, File jarFile, String version)
            throws Exception {

        setField(mojo, "classesDirectory", classesDir.toFile());
        setField(mojo, "buildDirectory", buildDir);
        setField(mojo, "outputFileName", "hotpatch.zip");
        setField(mojo, "compareVersion", version);

        when(project.getGroupId()).thenReturn("com.example");
        when(project.getArtifactId()).thenReturn("test-project");

        // Artefakt-Aufloesung mocken
        ArtifactResult result = new ArtifactResult(new ArtifactRequest());
        DefaultArtifact resolved = new DefaultArtifact("com.example", "test-project", "jar",
                version != null ? version : "1.0.0-SNAPSHOT");
        result.setArtifact(resolved.setFile(jarFile));
        when(repoSystem.resolveArtifact(any(), any())).thenReturn(result);
    }

    /**
     * Hilfsklasse fuer JAR-Eintraege in Tests.
     */
    private static class TestJarEintrag {
        final String pfad;
        final byte[] inhalt;

        TestJarEintrag(String pfad, byte[] inhalt) {
            this.pfad = pfad;
            this.inhalt = inhalt;
        }
    }
}
