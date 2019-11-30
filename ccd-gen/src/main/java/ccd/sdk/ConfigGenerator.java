package ccd.sdk;

import ccd.sdk.types.MyAnnotation;
import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.impldep.com.google.api.client.util.Lists;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

public class ConfigGenerator  extends DefaultTask {

    @TaskAction
    public void generate() throws ClassNotFoundException {
        SourceSetContainer ssc = getProject().getConvention().getPlugin(JavaPluginConvention.class).getSourceSets();
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

        Reflections reflections = new Reflections(config);
        System.out.println("Found types: " + reflections.getTypesAnnotatedWith(MyAnnotation.class).size());
    }
}
