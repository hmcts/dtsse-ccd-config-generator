package uk.gov.hmcts.ccd.sdk.json;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import uk.gov.hmcts.ccd.sdk.CCDDefinitionGenerator;
import uk.gov.hmcts.ccd.sdk.ResolvedCCDConfig;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.config.CcdCaseDataMapperConfiguration;
import uk.gov.hmcts.ccd.sdk.impl.json.JsonCallbackBridge;
import uk.gov.hmcts.ccd.sdk.json.JsonEventReplacementIntegrationTest.CaseData;
import uk.gov.hmcts.ccd.sdk.json.JsonEventReplacementIntegrationTest.Role;
import uk.gov.hmcts.ccd.sdk.json.JsonEventReplacementIntegrationTest.State;

class JsonDefinitionLayoutIntegrationTest {

  @TempDir
  Path definitions;

  @BeforeEach
  void writeDefinition() throws IOException {
    write("CaseType.json", """
        [{"ID": "TEST", "JurisdictionID": "TEST"}]
        """);
    write("State/State.json", """
        [{"ID": "Open", "CaseTypeID": "TEST", "Name": "Open"}]
        """);
    write("State/State-nonprod.json", """
        [{"ID": "Trial", "CaseTypeID": "TEST", "Name": "Trial"}]
        """);
    write("CaseEvent/CaseEvent.json", """
        [{"ID": "create", "CaseTypeID": "TEST", "Name": "Create"}]
        """);
    write("CaseEvent/User/UserEvents-prod.json", """
        [{"ID": "reply", "CaseTypeID": "TEST", "Name": "Reply (prod)"}]
        """);
    write("CaseEvent/User/UserEvents-jb-nonprod.json", """
        [{"ID": "reply", "CaseTypeID": "TEST", "Name": "Reply (nonprod)"}]
        """);
    write("CaseEvent/Camunda/Deep/CaseEvents.json", """
        [{"ID": "notify", "CaseTypeID": "TEST", "Name": "Notify"}]
        """);
    write("CaseEvent/TestingSupport/CaseEvent-testing-prod.json", """
        [{"ID": "reset", "CaseTypeID": "TEST", "Name": "Reset"}]
        """);
  }

  @Test
  void readsSheetsFromSubfoldersAndSkipsExcludedVariants() {
    context(definitions.toUri().toString(), "*-prod.json").run(context -> {
      assertThat(context).hasNotFailed();
      ResolvedCCDConfig<?, ?, ?> resolved = context.getBean(ResolvedCCDConfig.class);
      assertThat(resolved.getEvents()).containsOnlyKeys("create", "reply", "notify");
      assertThat(resolved.getEvents().get("reply").getName()).isEqualTo("Reply (nonprod)");
      assertThat(resolved.labelForState("Open")).contains("Open");
      assertThat(resolved.labelForState("Trial")).contains("Trial");
    });
  }

  @Test
  void excludesSeveralPatterns() {
    context(definitions.toUri().toString(), "*-nonprod.json, *-testing*.json").run(context -> {
      assertThat(context).hasNotFailed();
      ResolvedCCDConfig<?, ?, ?> resolved = context.getBean(ResolvedCCDConfig.class);
      assertThat(resolved.getEvents()).containsOnlyKeys("create", "reply", "notify");
      assertThat(resolved.getEvents().get("reply").getName()).isEqualTo("Reply (prod)");
      assertThat(resolved.labelForState("Open")).contains("Open");
      assertThat(resolved.labelForState("Trial")).isEmpty();
    });
  }

  @Test
  void readsADefinitionPackagedInAJar(@TempDir Path jars) throws IOException {
    Path jar = jars.resolve("definitions.jar");
    try (var out = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(definitions)) {
      // Directory entries as Gradle and Spring Boot write them, which classpath folder searches rely on.
      for (Path path : files.sorted().toList()) {
        String name = ("ccd-definitions/test/" + definitions.relativize(path)).replaceAll("/$", "");
        if (Files.isDirectory(path)) {
          out.putNextEntry(new JarEntry(name + "/"));
        } else {
          out.putNextEntry(new JarEntry(name));
          out.write(Files.readAllBytes(path));
        }
        out.closeEntry();
      }
    }

    try (var classLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader())) {
      context("classpath:ccd-definitions/test", "*-prod.json")
          .withClassLoader(classLoader)
          .run(context -> {
            assertThat(context).hasNotFailed();
            ResolvedCCDConfig<?, ?, ?> resolved = context.getBean(ResolvedCCDConfig.class);
            assertThat(resolved.getEvents()).containsOnlyKeys("create", "reply", "notify");
          });
    }
  }

  private void write(String path, String json) throws IOException {
    Path file = definitions.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, json);
  }

  private ApplicationContextRunner context(String root, String excluded) {
    return new ApplicationContextRunner()
        .withUserConfiguration(LayoutConfiguration.class, JsonCCDConfigSupport.class, JsonCallbackBridge.class)
        .withBean(CcdCaseDataMapperConfiguration.CCD_CASE_DATA_OBJECT_MAPPER, ObjectMapper.class, ObjectMapper::new)
        .withBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class, RequestMappingHandlerMapping::new)
        .withPropertyValues("definitions=" + root, "excluded=" + excluded);
  }

  @Configuration(proxyBeanMethods = false)
  static class LayoutConfiguration {
    @Bean
    CCDConfig<CaseData, State, Role> jsonConfig(JsonCCDConfigSupport support, @Value("${definitions}") String root,
                                                @Value("${excluded}") String excluded) {
      return new JsonBackedCCDConfig<CaseData, State, Role>(support, "TEST", root, List.of(excluded.split(","))) {};
    }

    @Bean
    ResolvedCCDConfig<?, ?, ?> resolvedConfig(List<CCDConfig<?, ?, ?>> configs) {
      return new CCDDefinitionGenerator(configs, null).loadConfigs().getFirst();
    }
  }
}
