package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.Lists;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.generator.JSONConfigGenerator;

/**
 * Public API for programmatically generating and exporting definitions.
 */
@Configuration
public class CCDDefinitionGenerator {
  private final List<CCDConfig<?, ?, ?>> configs;
  private final JSONConfigGenerator writer;

  @Autowired
  public CCDDefinitionGenerator(List<CCDConfig<?, ?, ?>> configs, JSONConfigGenerator writer) {
    this.configs = configs;
    this.writer = writer;
  }

  @Bean
  public List<ResolvedCCDConfig<?, ?, ?>> loadConfigs() {
    return loadConfigs(configs);
  }

  static List<ResolvedCCDConfig<?, ?, ?>> loadConfigs(Collection<CCDConfig<?, ?, ?>> configs) {
    Map<ConfigGroup, List<CCDConfig<?, ?, ?>>> configsByGroup = new LinkedHashMap<>();
    for (CCDConfig<?, ?, ?> config : configs) {
      List<String> groupingKeys = config.groupingKeys();
      if (groupingKeys == null || groupingKeys.isEmpty()) {
        throw new IllegalStateException("CCDConfig groupingKeys must not be empty for "
            + ClassUtils.getUserClass(config).getName());
      }
      for (String groupingKey : groupingKeys) {
        ConfigGroup group = resolveConfigGroup(config, groupingKey);
        configsByGroup.computeIfAbsent(group, ignored -> new ArrayList<>()).add(config);
      }
    }

    List<ResolvedCCDConfig<?, ?, ?>> result = Lists.newArrayList();
    for (Map.Entry<ConfigGroup, List<CCDConfig<?, ?, ?>>> entry : configsByGroup.entrySet()) {
      ConfigResolver generator = new ConfigResolver(entry.getValue());
      result.add(generator.resolveCCDConfig());
    }
    return result;
  }

  private static ConfigGroup resolveConfigGroup(CCDConfig<?, ?, ?> config, String groupingKey) {
    if (groupingKey == null) {
      throw new IllegalStateException("CCDConfig grouping key must not be null for "
          + ClassUtils.getUserClass(config).getName());
    }
    return new ConfigGroup(resolveCaseDataClass(config), groupingKey);
  }

  private static Class<?> resolveCaseDataClass(CCDConfig<?, ?, ?> config) {
    Class<?> userClass = ClassUtils.getUserClass(config);
    ResolvableType configType = ResolvableType.forClass(userClass).as(CCDConfig.class);
    Class<?> caseType = configType.getGeneric(0).resolve();
    if (caseType == null) {
      throw new IllegalStateException("Unable to resolve case data type for " + userClass.getName());
    }
    return caseType;
  }

  private record ConfigGroup(Class<?> caseDataClass, String groupingKey) {
  }

  /**
   * Export all case types to the specified folder.
   */
  public void generateAllCaseTypesToJSON(File destinationFolder) {
    for (ResolvedCCDConfig<?, ?, ?> c : loadConfigs(configs)) {
      File f = new File(destinationFolder, c.caseType);
      f.mkdirs();
      writer.writeConfig(f, c);
    }
  }

}
