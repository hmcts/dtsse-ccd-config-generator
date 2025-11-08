package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import java.io.File;
import java.util.List;
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
    Multimap<Class<?>, CCDConfig<?, ?, ?>>
        configsByDataClass = Multimaps
        .index(configs, CCDDefinitionGenerator::resolveCaseDataClass);

    List<ResolvedCCDConfig<?, ?, ?>> result = Lists.newArrayList();
    for (Class<?> c : configsByDataClass.keySet()) {
      ConfigResolver generator = new ConfigResolver(configsByDataClass.get(c));
      result.add(generator.resolveCCDConfig());
    }
    return result;
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

  /**
   * Export all case types to the specified folder.
   */
  public void generateAllCaseTypesToJSON(File destinationFolder) {
    for (ResolvedCCDConfig<?, ?, ?> c : loadConfigs()) {
      File f = new File(destinationFolder, c.caseType);
      f.mkdirs();
      writer.writeConfig(f, c);
    }
  }

}
