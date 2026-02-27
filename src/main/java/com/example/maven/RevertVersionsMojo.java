package com.example.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.BuildPluginManager;
import static org.twdata.maven.mojoexecutor.MojoExecutor.*;

@Mojo(name = "revert-versions")
public class RevertVersionsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true)
    private MavenProject project;

    @Parameter(defaultValue = "${session}", readonly = true)
    private MavenSession session;

    @Component
    private BuildPluginManager pluginManager;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        executeMojo(
                plugin(
                        groupId("org.codehaus.mojo"),
                        artifactId("versions-maven-plugin"),
                        version("2.21.0")
                ),
                goal("revert"),
                configuration(),
                executionEnvironment(project, session, pluginManager)
        );
    }
}
