package uk.gov.hmcts.ccd.sdk.bundling.spring;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRendererBuilder;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtension;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.HttpDocmosisRenderService;

/**
 * Opt-in auto-configuration: a {@link BundleRenderer} assembled from every {@link DocumentResolver}
 * bean, every {@link BundlingExtension} bean (in {@code @Order} order), the Docmosis client when
 * {@code ccd.bundling.docmosis.*} is set, the consumer's {@code MeterRegistry} when one exists,
 * and the {@code ccd.bundling.*} properties. Every bean is
 * {@code @ConditionalOnMissingBean}, so a consumer-defined bean of the same type wins.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "ccd.bundling", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(BundlingProperties.class)
public class BundlingAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(BundlingAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "ccd.bundling.docmosis", name = {"convert-endpoint", "access-key"})
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
      ObjectProvider<BundlingExtension> extensions,
      ObjectProvider<DocmosisRenderService> docmosis,
      ObjectProvider<MeterRegistry> meterRegistry) {
    BundleRendererBuilder builder = BundleRenderer.builder()
        .limits(properties.getLimits().toLimits())
        .maxConcurrentRenders(properties.getMaxConcurrentRenders())
        .tempDirectory(tempDirectory(properties));
    resolvers.forEach(builder::resolver);
    List<BundlingExtension> extensionList = extensions.orderedStream().toList();
    extensionList.forEach(builder::extension);
    docmosis.ifAvailable(builder::docmosis);
    meterRegistry.ifAvailable(builder::meterRegistry);
    log.info("Auto-configured BundleRenderer: resolvers={}, docmosis={}, extensions={}",
        resolvers.stream().map(DocumentResolver::provider).toList(),
        docmosis.getIfAvailable() != null,
        extensionList.stream().map(BundlingExtension::name).toList());
    return builder.build();
  }

  private static Path tempDirectory(BundlingProperties properties) {
    return properties.getTempDirectory() != null
        ? properties.getTempDirectory() : Path.of(System.getProperty("java.io.tmpdir"));
  }
}
