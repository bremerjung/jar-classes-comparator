package com.example.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.twdata.maven.mojoexecutor.MojoExecutor;

import java.io.File;
import java.io.IOException;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

/**
 * Ermittelt den aktuellen Git-Branch via JGit und setzt ihn
 * als Maven-Projektversion mit dem versions:set Goal.
 */
@Mojo(
        name = "set-branch-version",
        defaultPhase = LifecyclePhase.NONE,
        requiresProject = true,
        aggregator = false
)
public class BranchVersionMojo extends AbstractMojo {

    /** Das aktuelle Maven-Projekt. */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** Die aktuelle Maven-Session. */
    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    /** Der BuildPluginManager zum Ausführen anderer Plugins. */
    @Component
    private BuildPluginManager pluginManager;

    /**
     * Optionales Präfix, das dem Branch-Namen vorangestellt wird.
     * Beispiel: prefix="v" → version="v/feature/my-feature"
     */
    @Parameter(property = "branchVersion.prefix", defaultValue = "")
    private String prefix;

    /**
     * Optionales Suffix, das an den Branch-Namen angehängt wird.
     * Beispiel: suffix="-SNAPSHOT" → version="main-SNAPSHOT"
     */
    @Parameter(property = "branchVersion.suffix", defaultValue = "")
    private String suffix;

    /**
     * Schrägstriche im Branch-Namen ersetzen (z.B. feature/foo → feature-foo).
     * Standard: true, da Schrägstriche in Maven-Versionen problematisch sind.
     */
    @Parameter(property = "branchVersion.replaceSlashes", defaultValue = "true")
    private boolean replaceSlashes;

    /**
     * Zeichen, durch das Schrägstriche ersetzt werden.
     */
    @Parameter(property = "branchVersion.slashReplacement", defaultValue = "-")
    private String slashReplacement;

    /**
     * Version des org.codehaus.mojo:versions-maven-plugin.
     */
    @Parameter(property = "branchVersion.versionsPluginVersion", defaultValue = "2.16.2")
    private String versionsPluginVersion;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        // 1. Git-Branch ermitteln
        String branchName = resolveCurrentBranch();
        getLog().info("Ermittelter Git-Branch: " + branchName);

        // 2. Version aus Branch-Name ableiten
        String newVersion = buildVersion(branchName);
        getLog().info("Neue Maven-Version: " + newVersion);

        // 3. versions:set aufrufen
        setMavenVersion(newVersion);
        getLog().info("Maven-Version erfolgreich auf '" + newVersion + "' gesetzt.");
    }

    // -----------------------------------------------------------------------
    // Branch-Ermittlung via JGit
    // -----------------------------------------------------------------------

    private String resolveCurrentBranch() throws MojoExecutionException {
        File basedir = project.getBasedir();
        try {
            FileRepositoryBuilder builder = new FileRepositoryBuilder();
            try (Repository repository = builder
                    .readEnvironment()          // GIT_DIR etc. aus Umgebung
                    .findGitDir(basedir)        // .git vom Projektverzeichnis aus suchen
                    .setMustExist(true)
                    .build()) {

                String branch = repository.getBranch();

                if (branch == null || branch.isEmpty()) {
                    throw new MojoExecutionException(
                            "Kein Branch gefunden – befindet sich das Repository im 'detached HEAD'-Zustand?");
                }

                // Im detached-HEAD-Zustand liefert getBranch() den Commit-Hash
                if (isCommitHash(branch)) {
                    throw new MojoExecutionException(
                            "Repository befindet sich im 'detached HEAD'-Zustand (Commit: " + branch + "). "
                                    + "Bitte einen Branch auschecken.");
                }

                return branch;
            }
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Fehler beim Lesen des Git-Repositories in: " + basedir, e);
        }
    }

    /** Prüft, ob der String ein Git-Commit-Hash ist (40 Hex-Zeichen). */
    private boolean isCommitHash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{40}");
    }

    // -----------------------------------------------------------------------
    // Versions-String aufbauen
    // -----------------------------------------------------------------------

    private String buildVersion(String branchName) {
        String version = branchName;

        if (replaceSlashes) {
            version = version.replace("/", slashReplacement);
        }

        return prefix + version + suffix;
    }

    // -----------------------------------------------------------------------
    // versions:set via mojo-executor aufrufen
    // -----------------------------------------------------------------------

    private void setMavenVersion(String newVersion) throws MojoExecutionException {
        executeMojo(
                plugin(
                        groupId("org.codehaus.mojo"),
                        artifactId("versions-maven-plugin"),
                        version(versionsPluginVersion)
                ),
                goal("set"),
                configuration(
                        element(name("newVersion"),         newVersion),
                        element(name("generateBackupPoms"), "false")   // kein Backup-POM
                ),
                executionEnvironment(project, session, pluginManager)
        );
    }
}