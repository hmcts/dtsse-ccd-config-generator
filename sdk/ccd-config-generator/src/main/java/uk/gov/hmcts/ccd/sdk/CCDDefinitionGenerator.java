package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.Lists;
import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  private static List<ResolvedCCDConfig<?, ?, ?>> loadConfigs(Collection<CCDConfig<?, ?, ?>> configs) {
    Map<ConfigGroup, List<CCDConfig<?, ?, ?>>> configsByGroup = new LinkedHashMap<>();
    for (CCDConfig<?, ?, ?> config : configs) {
      Class<?> caseDataClass = resolveCaseDataClass(config);
      for (String caseTypeId : resolveCaseTypeIds(config)) {
        ConfigGroup group = new ConfigGroup(caseDataClass, caseTypeId);
        configsByGroup.computeIfAbsent(group, ignored -> Lists.newArrayList()).add(config);
      }
    }

    List<ResolvedCCDConfig<?, ?, ?>> result = Lists.newArrayList();
    for (Map.Entry<ConfigGroup, List<CCDConfig<?, ?, ?>>> entry : configsByGroup.entrySet()) {
      ResolvedCCDConfig<?, ?, ?> resolved = new ConfigResolver(entry.getValue()).resolveCCDConfig();
      String declaredCaseTypeId = entry.getKey().caseTypeId();
      if (!declaredCaseTypeId.isEmpty()
          && (resolved.caseType == null || resolved.caseType.isEmpty())) {
        throw new IllegalStateException(
            "No case type configuration found for declared case type '%s'".formatted(declaredCaseTypeId)
        );
      }
      if (!declaredCaseTypeId.isEmpty() && !declaredCaseTypeId.equals(resolved.caseType)) {
        throw new IllegalStateException(
            "Configuration declared case type '%s' but resolved case type '%s'"
                .formatted(declaredCaseTypeId, resolved.caseType)
        );
      }
      result.add(resolved);
    }
    return result;
  }

  private static Set<String> resolveCaseTypeIds(CCDConfig<?, ?, ?> config) {
    Set<String> caseTypeIds = config.caseTypeIds();
    return caseTypeIds.isEmpty() ? Set.of("") : caseTypeIds;
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

  private record ConfigGroup(Class<?> caseDataClass, String caseTypeId) {
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
