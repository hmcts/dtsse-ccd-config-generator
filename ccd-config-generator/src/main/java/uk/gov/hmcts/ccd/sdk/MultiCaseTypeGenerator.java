package uk.gov.hmcts.ccd.sdk;

import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import java.io.File;
import java.util.List;
import net.jodah.typetools.TypeResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;

@Configuration
class MultiCaseTypeGenerator {
  private final List<CCDConfig<?, ?, ?>> configs;

  @Autowired
  public MultiCaseTypeGenerator(List<CCDConfig<?, ?, ?>> configs) {
    this.configs = configs;
  }

  @Bean
  public List<ResolvedCCDConfig<?, ?, ?>> loadConfigs() {
    Multimap<Class<?>, CCDConfig<?, ?, ?>>
        configsByDataClass = Multimaps
        .index(configs, x -> TypeResolver.resolveRawArguments(CCDConfig.class, x.getClass())[0]);

    List<ResolvedCCDConfig<?, ?, ?>> result = Lists.newArrayList();
    for (Class<?> c : configsByDataClass.keySet()) {
      ConfigResolver generator = new ConfigResolver(configsByDataClass.get(c));
      result.add(generator.resolveCCDConfig());
    }
    return result;
  }

  public void generateCaseTypes(File outFolder) {
    for (ResolvedCCDConfig<?, ?, ?> c : loadConfigs()) {
      File f = new File(outFolder, c.caseType);
      f.mkdirs();
      new JSONConfigWriter().writeConfig(f, c);
    }
  }

}
