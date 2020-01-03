package ccd.sdk;

import org.reflections.Reflections;

import java.io.File;

public class Main {
    public static void main(String[] args) {
        File outputDir = new File(args[0]);
        Reflections reflections = new Reflections(args[1]);
        ConfigGenerator generator = new ConfigGenerator(reflections, outputDir);
        generator.generate(args[2]);
    }
}
