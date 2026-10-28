package uk.gov.hmcts.ccd.sdk.conversion.spring;

import java.nio.file.Path;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import uk.gov.hmcts.ccd.sdk.conversion.DocumentConverter;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.HttpDocmosisRenderService;

/**
 * Opt-out auto-configuration of a {@link DocumentConverter}. It uses the application's
 * {@link DocmosisRenderService} bean when there is one that converts files (document bundling's,
 * for example), and otherwise builds one from {@code ccd.conversion.docmosis.*} when the convert
 * endpoint and access key are set. A bundling client set up only to render cover pages is not
 * used for conversion. Every bean is {@code @ConditionalOnMissingBean}.
 */
@AutoConfiguration(
    afterName = "uk.gov.hmcts.ccd.sdk.bundling.spring.BundlingAutoConfiguration")
@ConditionalOnProperty(prefix = "ccd.conversion", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(ConversionProperties.class)
public class ConversionAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  @Conditional(ConversionDocmosisConfigured.class)
  public DocmosisRenderService conversionDocmosisRenderService(ConversionProperties properties) {
    return conversionDocmosis(properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public DocumentConverter documentConverter(ConversionProperties properties,
      ObjectProvider<DocmosisRenderService> docmosis, Environment environment) {
    DocumentConverter.Builder builder = DocumentConverter.builder()
        .tempDirectory(tempDirectory(properties));
    Optional<DocmosisRenderService> converting =
        docmosis.orderedStream().filter(DocmosisRenderService::convertsFiles).findFirst();
    if (converting.isPresent()) {
      builder.docmosis(converting.get());
    } else if (ConversionDocmosisConfigured.isConfigured(environment)) {
      // The application's client only renders templates; conversion needs its own.
      builder.docmosis(conversionDocmosis(properties));
    }
    return builder.build();
  }

  private static DocmosisRenderService conversionDocmosis(ConversionProperties properties) {
    return new HttpDocmosisRenderService(properties.getDocmosis().toConnection(),
        tempDirectory(properties).resolve("ccd-conversion-docmosis"));
  }

  private static Path tempDirectory(ConversionProperties properties) {
    return properties.getTempDirectory() != null
        ? properties.getTempDirectory() : Path.of(System.getProperty("java.io.tmpdir"));
  }
}
