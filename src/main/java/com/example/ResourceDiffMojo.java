package com.example;

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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Maven Plugin (Mojo) that compares resources in target/classes with
 * resources from a JAR artifact resolved via Maven repositories.
 * <p>
 * Files that differ (or exist only in target/classes) are packaged
 * into a ZIP archive. .class files are excluded from the comparison.
 * </p>
 * <p>
 * The groupId and artifactId of the artifact to compare against are
 * read from the current project's POM. The version can be overridden
 * via a parameter; by default the project's own version is used.
 * </p>
 *
 * <p>Usage in a POM:</p>
 * <pre>{@code
 * <plugin>
 *   <groupId>com.example.maven</groupId>
 *   <artifactId>resource-diff-maven-plugin</artifactId>
 *   <version>1.0.0-SNAPSHOT</version>
 *   <executions>
 *     <execution>
 *       <goals>
 *         <goal>diff</goal>
 *       </goals>
 *     </execution>
 *   </executions>
 *   <configuration>
 *     <!-- optional: override the version to compare against -->
 *     <compareVersion>1.0.0</compareVersion>
 *   </configuration>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "diff", defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class ResourceDiffMojo extends AbstractMojo {

    /**
     * The current Maven project. Used to read groupId, artifactId, and version.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * The version of the artifact to compare against.
     * If not specified, the project's own version is used.
     */
    @Parameter(property = "resourcediff.compareVersion")
    private String compareVersion;

    /**
     * The build output directory (typically target/classes).
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    private File classesDirectory;

    /**
     * The build directory (typically target/).
     */
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDirectory;

    /**
     * The name of the output ZIP file.
     */
    @Parameter(defaultValue = "resource-diff.zip", property = "resourcediff.outputFileName")
    private String outputFileName;

    // -- Aether components for artifact resolution --

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true, required = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true, required = true)
    private List<RemoteRepository> remoteRepositories;

    /**
     * File extensions that are treated as text files for line-ending normalization.
     * Files with these extensions will have their line endings normalized
     * (\r\n and \r → \n) before comparison, so that OS-specific line endings
     * do not cause false positives.
     */
    private static final Set<String> TEXT_EXTENSIONS;
    static {
        Set<String> exts = new HashSet<>();
        exts.add("properties");
        exts.add("xml");
        exts.add("xsl");
        exts.add("xslt");
        exts.add("xsd");
        exts.add("dtd");
        exts.add("html");
        exts.add("htm");
        exts.add("xhtml");
        exts.add("css");
        exts.add("js");
        exts.add("json");
        exts.add("yaml");
        exts.add("yml");
        exts.add("txt");
        exts.add("csv");
        exts.add("tsv");
        exts.add("md");
        exts.add("cfg");
        exts.add("conf");
        exts.add("ini");
        exts.add("sql");
        exts.add("graphql");
        exts.add("gql");
        exts.add("ftl");
        exts.add("vm");
        exts.add("jsp");
        exts.add("jspx");
        exts.add("tag");
        exts.add("tld");
        exts.add("wsdl");
        exts.add("fxml");
        exts.add("log");
        exts.add("sh");
        exts.add("bat");
        exts.add("cmd");
        exts.add("groovy");
        exts.add("kt");
        exts.add("scala");
        exts.add("java");
        TEXT_EXTENSIONS = Collections.unmodifiableSet(exts);
    }

    // -----------------------------------------------

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        // 1. Determine coordinates
        String groupId = project.getGroupId();
        String artifactId = project.getArtifactId();
        String version = (compareVersion != null && !compareVersion.trim().isEmpty())
                ? compareVersion.trim()
                : project.getVersion();

        getLog().info("Resource-Diff: comparing target/classes against artifact "
                + groupId + ":" + artifactId + ":" + version);

        // 2. Resolve the JAR from the repository
        File jarFile = resolveArtifact(groupId, artifactId, version);
        getLog().info("Resolved artifact JAR: " + jarFile.getAbsolutePath());

        // 3. Validate that target/classes exists
        if (!classesDirectory.isDirectory()) {
            throw new MojoFailureException(
                    "Classes directory does not exist: " + classesDirectory.getAbsolutePath()
                            + " – did you run 'compile' first?");
        }

        // 4. Perform the diff
        try {
            Set<String> diffFiles = computeDiff(classesDirectory.toPath(), jarFile);

            if (diffFiles.isEmpty()) {
                getLog().info("No differences found – no ZIP will be created.");
                return;
            }

            getLog().info("Found " + diffFiles.size() + " differing file(s).");

            // 5. Create the ZIP
            File zipFile = new File(buildDirectory, outputFileName);
            createZip(classesDirectory.toPath(), diffFiles, zipFile);

            getLog().info("Diff ZIP created: " + zipFile.getAbsolutePath());

        } catch (IOException e) {
            throw new MojoExecutionException("Error during resource diff", e);
        }
    }

    // ------------------------------------------------------------------ //
    //  Artifact resolution via Aether / Maven Repository System
    // ------------------------------------------------------------------ //

    /**
     * Resolves a JAR artifact from the configured Maven repositories.
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
                    "Could not resolve artifact " + groupId + ":" + artifactId + ":" + version, e);
        }
    }

    // ------------------------------------------------------------------ //
    //  Diff logic
    // ------------------------------------------------------------------ //

    /**
     * Computes which resource files in {@code classesDir} differ from
     * (or are missing in) the given JAR.
     * <p>
     * .class files are excluded from comparison.
     * </p>
     *
     * @return set of relative paths (using "/" as separator) of differing files
     */
    private Set<String> computeDiff(Path classesDir, File jarFile) throws IOException {

        Set<String> diffPaths = new HashSet<>();

        // Read all entries from the JAR into memory (byte arrays)
        Map<String, byte[]> jarContents = readJarResources(jarFile);

        // Walk target/classes and compare each non-.class file
        Files.walkFileTree(classesDir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String relativePath = classesDir.relativize(file).toString().replace('\\', '/');

                // Skip .class files
                if (relativePath.endsWith(".class")) {
                    return FileVisitResult.CONTINUE;
                }

                byte[] localBytes = Files.readAllBytes(file);
                byte[] jarBytes = jarContents.get(relativePath);

                if (jarBytes == null) {
                    // File exists only in target/classes (missing in JAR)
                    getLog().debug("NEW      : " + relativePath);
                    diffPaths.add(relativePath);
                } else {
                    // For text files: normalize line endings before comparison
                    byte[] localCompare = localBytes;
                    byte[] jarCompare = jarBytes;

                    if (isTextFile(relativePath)) {
                        localCompare = normalizeLineEndings(localBytes);
                        jarCompare = normalizeLineEndings(jarBytes);
                    }

                    if (!Arrays.equals(localCompare, jarCompare)) {
                        getLog().debug("MODIFIED : " + relativePath);
                        diffPaths.add(relativePath);
                    } else {
                        getLog().debug("UNCHANGED: " + relativePath);
                    }
                }

                return FileVisitResult.CONTINUE;
            }
        });

        return diffPaths;
    }

    /**
     * Reads all non-.class entries from a JAR file into a map
     * of relative-path → byte-content.
     */
    private Map<String, byte[]> readJarResources(File jarFile) throws IOException {

        Map<String, byte[]> contents = new HashMap<>();

        try (JarFile jar = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                // Skip directories and .class files
                if (entry.isDirectory() || entry.getName().endsWith(".class")) {
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
    //  Utility
    // ------------------------------------------------------------------ //

    /**
     * Determines whether a file should be treated as a text file
     * based on its extension. Text files have their line endings
     * normalized before comparison.
     */
    private boolean isTextFile(String relativePath) {
        int dotIndex = relativePath.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == relativePath.length() - 1) {
            return false;
        }
        String extension = relativePath.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        return TEXT_EXTENSIONS.contains(extension);
    }

    /**
     * Normalizes line endings in a byte array:
     * \r\n (Windows) and standalone \r (old Mac) are replaced with \n (Unix).
     * This avoids false-positive diffs caused by OS-specific line endings.
     */
    private byte[] normalizeLineEndings(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (int i = 0; i < data.length; i++) {
            byte b = data[i];
            if (b == '\r') {
                out.write('\n');
                // Skip the \n in a \r\n sequence to avoid producing \n\n
                if (i + 1 < data.length && data[i + 1] == '\n') {
                    i++;
                }
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    /**
     * Reads all bytes from an InputStream into a byte array.
     * Compatible with Java 8+ (replacement for InputStream.readAllBytes()).
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
    //  ZIP creation
    // ------------------------------------------------------------------ //

    /**
     * Creates a ZIP archive containing only the files whose relative paths
     * are in {@code relativePaths}, taken from {@code classesDir}.
     */
    private void createZip(Path classesDir, Set<String> relativePaths, File zipFile) throws IOException {

        // Ensure parent directory exists
        zipFile.getParentFile().mkdirs();

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (String relPath : relativePaths) {
                Path sourceFile = classesDir.resolve(relPath);
                if (!Files.exists(sourceFile)) {
                    getLog().warn("File vanished before zipping: " + relPath);
                    continue;
                }

                zos.putNextEntry(new ZipEntry(relPath));
                Files.copy(sourceFile, zos);
                zos.closeEntry();
            }
        }
    }
}
