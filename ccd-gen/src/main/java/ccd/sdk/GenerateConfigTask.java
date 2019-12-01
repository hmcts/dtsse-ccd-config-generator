package ccd.sdk;

import ccd.sdk.types.ComplexType;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskAction;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import javax.inject.Inject;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

public class GenerateConfigTask extends DefaultTask {

    private final Project project;

    @Inject
    public GenerateConfigTask(Project project) {
        this.project = project;
    }

    @TaskAction
    public void generate() {
        System.out.println("Found types: " + getReflections().getTypesAnnotatedWith(ComplexType.class).size());
    }

    private Reflections getReflections() {
        SourceSetContainer ssc = project.getConvention().getPlugin(JavaPluginConvention.class).getSourceSets();
        SourceSet source = ssc.getByName("main");
        List<URL> files = source.getOutput().getClassesDirs().getFiles().stream().map(x -> {
            try {
                return x.toURI().toURL();
            } catch (MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }).collect(Collectors.toList());

        ClassLoader classLoader = new java.net.URLClassLoader(files.toArray(new URL[0]));
        ConfigurationBuilder config = new ConfigurationBuilder()
                .addClassLoader(classLoader)
                .setUrls(ClasspathHelper.forClassLoader(classLoader))
                .setExpandSuperTypes(false);

        return new Reflections(config);
    }
}
