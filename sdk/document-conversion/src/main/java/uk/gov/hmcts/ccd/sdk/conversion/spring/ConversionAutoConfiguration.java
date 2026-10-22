package uk.gov.hmcts.ccd.sdk.conversion.spring;

import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.sdk.conversion.DocumentConverter;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.HttpDocmosisRenderService;

/**
 * Opt-out auto-configuration of a {@link DocumentConverter}. It uses the application's
 * {@link DocmosisRenderService} bean when there is one (document bundling's, for example), and
 * otherwise builds one from {@code ccd.conversion.docmosis.*} when the convert endpoint and
 * access key are set. Every bean is {@code @ConditionalOnMissingBean}.
 */
@AutoConfiguration(
    afterName = "uk.gov.hmcts.ccd.sdk.bundling.spring.BundlingAutoConfiguration")
@ConditionalOnProperty(prefix = "ccd.conversion", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(ConversionProperties.class)
public class ConversionAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  // Both must be non-blank: services set them from optional env vars, which bind as "".
  @ConditionalOnExpression("!'${ccd.conversion.docmosis.convert-endpoint:}'.isBlank()"
      + " and !'${ccd.conversion.docmosis.access-key:}'.isBlank()")
  public DocmosisRenderService conversionDocmosisRenderService(ConversionProperties properties) {
    return new HttpDocmosisRenderService(properties.getDocmosis().toConnection(),
        tempDirectory(properties).resolve("ccd-conversion-docmosis"));
  }

  @Bean
  @ConditionalOnMissingBean
  public DocumentConverter documentConverter(ConversionProperties properties,
      ObjectProvider<DocmosisRenderService> docmosis) {
    DocumentConverter.Builder builder = DocumentConverter.builder()
        .tempDirectory(tempDirectory(properties));
    docmosis.ifAvailable(builder::docmosis);
    return builder.build();
  }

  private static Path tempDirectory(ConversionProperties properties) {
    return properties.getTempDirectory() != null
        ? properties.getTempDirectory() : Path.of(System.getProperty("java.io.tmpdir"));
  }
}
