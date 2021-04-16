package uk.gov.hmcts.ccd.sdk

import com.google.common.collect.HashMultimap
import com.google.common.collect.SetMultimap

class PomParser {
    static mavenToGradleConfigurations = [
            runtime: 'runtimeOnly',
            compile: 'implementation'
    ]

    static SetMultimap<String, String> getGeneratorDependencies() {
        def pomXml = new XmlSlurper().parse(CcdSdkPlugin.class.getClassLoader().getResourceAsStream("generator/pom-default.xml"))

        SetMultimap<String, String> result = HashMultimap.create();
        pomXml.dependencies.dependency.each { dependency ->
            def coordinates = "${dependency.groupId}:${dependency.artifactId}:${dependency.version}".toString()
            def config = mavenToGradleConfigurations.get(dependency.scope.toString())
            result.put(config, coordinates)
        }
        return result
    }
}
