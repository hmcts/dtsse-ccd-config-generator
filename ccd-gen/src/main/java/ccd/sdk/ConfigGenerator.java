package ccd.sdk;

import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.tasks.TaskAction;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;
import uk.gov.ccd.sdk.types.MyAnnotation;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

public class ConfigGenerator  extends DefaultTask {

    @TaskAction
    public void generate() {
        Configuration compileClasspath = getProject().getConfigurations().getByName("compileClasspath");
        List<URL> files = compileClasspath.getFiles().stream().map(x -> {
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
        System.out.println(reflections.getTypesAnnotatedWith(MyAnnotation.class));
    }
}
