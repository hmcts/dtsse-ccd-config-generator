package uk.gov.hmcts.ccd.sdk.bundling.spring;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRendererBuilder;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobAutoConfiguration;
import uk.gov.hmcts.ccd.sdk.conversion.DocumentConverter;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.HttpDocmosisRenderService;

/**
 * Opt-in auto-configuration: a {@link BundleRenderer} assembled from every {@link DocumentResolver}
 * bean, the application's {@link DocumentConverter} (document-conversion auto-configures one, with
 * any {@code PdfConverter} beans registered on it), the Docmosis client when
 * {@code ccd.bundling.docmosis.access-key} and an endpoint are set, the consumer's
 * {@code MeterRegistry} when one exists, and the {@code ccd.bundling.*} properties. Every bean is
 * {@code @ConditionalOnMissingBean}, so a consumer-defined bean of the same type wins.
 */
@AutoConfiguration(before = BundleJobAutoConfiguration.class)
@ConditionalOnProperty(prefix = "ccd.bundling", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(BundlingProperties.class)
public class BundlingAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(BundlingAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "ccd.bundling.docmosis", name = "access-key")
  @ConditionalOnExpression("!'${ccd.bundling.docmosis.convert-endpoint:}'.isEmpty()"
      + " or !'${ccd.bundling.docmosis.render-endpoint:}'.isEmpty()")
  public DocmosisRenderService bundlingDocmosisRenderService(BundlingProperties properties) {
    return new HttpDocmosisRenderService(
        properties.getDocmosis().toConnection(),
        tempDirectory(properties).resolve("ccd-bundling-docmosis"));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnBean(DocumentResolver.class)
  public BundleRenderer bundleRenderer(
      BundlingProperties properties,
      List<DocumentResolver> resolvers,
      ObjectProvider<DocumentConverter> converter,
      ObjectProvider<DocmosisRenderService> docmosis,
      ObjectProvider<MeterRegistry> meterRegistry) {
    BundleRendererBuilder builder = BundleRenderer.builder()
        .limits(properties.getLimits().toLimits())
        .maxConcurrentRenders(properties.getMaxConcurrentRenders())
        .tempDirectory(tempDirectory(properties));
    resolvers.forEach(builder::resolver);
    converter.ifAvailable(builder::converter);
    docmosis.ifAvailable(builder::docmosis);
    meterRegistry.ifAvailable(builder::meterRegistry);
    BundleRenderer renderer = builder.build();
    log.info("Auto-configured BundleRenderer: resolvers={}, docmosis={}, media types={}",
        resolvers.stream().map(DocumentResolver::provider).toList(),
        docmosis.getIfAvailable() != null, renderer.handledMediaTypes());
    return renderer;
  }

  private static Path tempDirectory(BundlingProperties properties) {
    return properties.getTempDirectory() != null
        ? properties.getTempDirectory() : Path.of(System.getProperty("java.io.tmpdir"));
  }
}
