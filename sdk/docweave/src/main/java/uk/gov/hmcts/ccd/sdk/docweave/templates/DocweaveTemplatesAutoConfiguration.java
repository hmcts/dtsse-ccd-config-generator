package uk.gov.hmcts.ccd.sdk.docweave.templates;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@AutoConfiguration
@EnableConfigurationProperties(DocweaveTemplatesAutoConfiguration.Properties.class)
@Import({
    DocweaveTemplateController.class,
    DocweaveTemplateRepository.class
})
public class DocweaveTemplatesAutoConfiguration {

  @Data
  @ConfigurationProperties(prefix = "docweave.templates")
  public static class Properties {
    private List<String> allowedServices = new ArrayList<>();
  }
}
