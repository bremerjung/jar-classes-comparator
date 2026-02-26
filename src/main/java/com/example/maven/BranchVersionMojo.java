package com.example.maven;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.eclipse.jgit.api.Git;

import java.io.File;

import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

@Mojo(
        name = "set-branch-version",
        defaultPhase = LifecyclePhase.NONE,
        requiresProject = true
)
public class BranchVersionMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Component
    private BuildPluginManager pluginManager;

    @Parameter(property = "branchVersion.prefix", defaultValue = "")
    private String prefix;

    @Parameter(property = "branchVersion.suffix", defaultValue = "")
    private String suffix;

    @Parameter(property = "branchVersion.replaceSlashes", defaultValue = "true")
    private boolean replaceSlashes;

    @Parameter(property = "branchVersion.slashReplacement", defaultValue = "-")
    private String slashReplacement;

    @Parameter(property = "branchVersion.versionsPluginVersion", defaultValue = "2.16.2")
    private String versionsPluginVersion;

    @Override
    public void execute() throws MojoExecutionException {
        String branch = resolveCurrentBranch();
        getLog().info("Ermittelter Git-Branch: " + branch);

        String newVersion = buildVersion(branch);
        getLog().info("Neue Maven-Version: " + newVersion);

        setMavenVersion(newVersion);
        getLog().info("Maven-Version erfolgreich auf '" + newVersion + "' gesetzt.");
    }

    private String resolveCurrentBranch() throws MojoExecutionException {
        File basedir = project.getBasedir();
        try (Git git = Git.open(basedir)) {
            String branch = git.getRepository().getBranch();
            if (branch == null || branch.isEmpty()) {
                throw new MojoExecutionException(
                        "Kein Branch gefunden – befindet sich das Repository im 'detached HEAD'-Zustand?");
            }
            if (branch.matches("[0-9a-fA-F]{40}")) {
                throw new MojoExecutionException(
                        "Repository befindet sich im 'detached HEAD'-Zustand (Commit: " + branch + ").");
            }
            return branch;
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Fehler beim Lesen des Git-Repositories in: " + basedir, e);
        }
    }

    private String buildVersion(String branchName) {
        String version = branchName;
        if (replaceSlashes) {
            version = version.replace("/", slashReplacement);
        }
        return prefix + version + suffix;
    }

    private MavenProject getRootProject() {
        MavenProject root = project;

        while (root.getParent() != null) {
            File parentPom = root.getParent().getFile();
            if (parentPom == null || !parentPom.exists()) {
                break; // kein lokales POM → externer Parent, Stop
            }
            root = root.getParent();
        }

        return root;
    }

    private void setMavenVersion(String newVersion) throws MojoExecutionException {
        MavenProject root = getRootProject();
        MavenProject originalProject = session.getCurrentProject();
        session.setCurrentProject(root);
        try {
            executeMojo(
                    plugin(
                            groupId("org.codehaus.mojo"),
                            artifactId("versions-maven-plugin"),
                            version(versionsPluginVersion)
                    ),
                    goal("set"),
                    configuration(
                            element(name("newVersion"),         newVersion),
                            element(name("generateBackupPoms"), "false"),
                            element(name("processAllModules"),  "true")
                    ),
                    executionEnvironment(root, session, pluginManager)
            );
        } finally {
            session.setCurrentProject(originalProject);
        }
    }
}