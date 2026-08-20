package uk.gov.hmcts.ccd.sdk.bundling.spring;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleDestination;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleLimits;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRendererBuilder;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlingExtension;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.cdam.BundlingAuthenticationProvider;
import uk.gov.hmcts.ccd.sdk.bundling.cdam.CdamBundleDestination;
import uk.gov.hmcts.ccd.sdk.bundling.cdam.CdamDocumentResolver;
import uk.gov.hmcts.ccd.sdk.bundling.cdam.CdamUploadSettings;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisConnection;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.bundling.docmosis.HttpDocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobAutoConfiguration;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

/**
 * Opt-in auto-configuration for the document-bundling module: a {@link BundleRenderer} assembled
 * from {@link BundlingProperties} and the beans the consuming service defines. Disabled entirely
 * with {@code ccd.bundling.enabled=false}, and every bean here backs off to a consumer-defined
 * one.
 *
 * <p>Docmosis follows the design's behavioural rule: when
 * {@code ccd.bundling.docmosis.convert-endpoint}, {@code render-endpoint}, and {@code access-key}
 * are all present, a bounded {@link HttpDocmosisRenderService} is registered and the renderer
 * handles office media types by default; when any is absent, office types stay unhandled and a
 * bundle containing one fails with an error naming the properties to set.
 *
 * <p>The built-in CDAM destination and resolver wire up only when three things are all present: a
 * {@link CaseDocumentClientApi} bean, a {@link BundlingAuthenticationProvider} bean, and the
 * {@code ccd.bundling.cdam.*} properties. The authentication provider is deliberately
 * consumer-provided rather than auto-configured: system-user token wiring is service-specific —
 * each service has its own IDAM client, system-user credentials, and S2S microservice name, and
 * the SDK must not guess that configuration or invent property names for credentials it never
 * persists. The two collaborator beans are resolved when the CDAM adapters are instantiated, not
 * when their conditions are evaluated, so it does not matter which (possibly unordered)
 * auto-configuration contributes them; this class additionally orders itself after the CDAM
 * client's own auto-configuration. A partially configured CDAM block — some but not all
 * properties, or all properties with a collaborator bean missing — is reported at WARN on
 * startup naming exactly what is missing; a service that sets none of the properties hears
 * nothing.
 *
 * <p>This configuration orders itself before {@link BundleJobAutoConfiguration} so the durable
 * job runner's {@code @ConditionalOnBean(BundleRenderer.class)} worker sees the renderer defined
 * here: with a {@code NamedParameterJdbcTemplate} and a renderer the worker comes up, and without
 * either it backs off through its own conditions.
 */
@AutoConfiguration(
    before = BundleJobAutoConfiguration.class,
    afterName =
        "uk.gov.hmcts.reform.ccd.document.am.config.CaseDocumentManagementClientAutoConfiguration")
@ConditionalOnProperty(prefix = "ccd.bundling", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(BundlingProperties.class)
public class BundlingAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(BundlingAutoConfiguration.class);

  private static final String CDAM_DESTINATION_BEAN_NAME = "bundlingCdamBundleDestination";

  private static final String CDAM_RESOLVER_BEAN_NAME = "bundlingCdamDocumentResolver";

  /**
   * The bounded client for the shared Docmosis render service, present only when the three
   * connection properties are configured. Timeouts, the source-size ceiling, and the retry
   * budget fall back to {@link DocmosisConnection}'s defaults; converted files land in a
   * dedicated directory under the configured temp directory.
   *
   * @param properties the bound {@code ccd.bundling} properties
   * @return the Docmosis render service
   * @throws IllegalStateException if the configured connection values are invalid — for example
   *     a blank access key, the classic empty environment-variable default
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "ccd.bundling.docmosis",
      name = {"convert-endpoint", "render-endpoint", "access-key"})
  public DocmosisRenderService bundlingDocmosisRenderService(BundlingProperties properties) {
    BundlingProperties.Docmosis docmosis = properties.getDocmosis();
    DocmosisConnection connection;
    try {
      connection = new DocmosisConnection(
          docmosis.getConvertEndpoint(),
          docmosis.getRenderEndpoint(),
          docmosis.getAccessKey(),
          docmosis.getConnectTimeout() != null
              ? docmosis.getConnectTimeout() : DocmosisConnection.DEFAULT_CONNECT_TIMEOUT,
          docmosis.getReadTimeout() != null
              ? docmosis.getReadTimeout() : DocmosisConnection.DEFAULT_READ_TIMEOUT,
          docmosis.getMaxSourceBytes() != null
              ? docmosis.getMaxSourceBytes() : DocmosisConnection.DEFAULT_MAX_SOURCE_BYTES,
          docmosis.getRetryAttempts() != null
              ? docmosis.getRetryAttempts() : DocmosisConnection.DEFAULT_RETRY_ATTEMPTS);
    } catch (IllegalArgumentException e) {
      // The validation messages never carry key material, so they are safe to rethrow.
      String hint = e.getMessage() != null && e.getMessage().contains("accessKey")
          ? " Set ccd.bundling.docmosis.access-key; a blank value usually means the environment"
              + " variable behind it resolved to an empty default (e.g. ${DOCMOSIS_ACCESS_KEY:})."
          : "";
      throw new IllegalStateException(
          "Invalid ccd.bundling.docmosis configuration: " + e.getMessage() + "." + hint, e);
    }
    return new HttpDocmosisRenderService(
        connection, baseTempDirectory(properties).resolve("ccd-bundling-docmosis"));
  }

  /**
   * Prunes the CDAM bean definitions whose collaborators turn out to be missing, once every
   * definition in the context is known. Registration-time {@code @ConditionalOnBean} cannot see
   * beans contributed by auto-configurations that sort later, so the CDAM beans register on
   * their property condition alone and this post-processor — which runs after all configuration
   * classes are parsed, whatever their order — removes the destination when another
   * {@link BundleDestination} bean is defined (the consumer's destination simply wins) and
   * removes both adapters when a collaborator bean is missing (reported at WARN by
   * {@link #bundlingCdamWiringReport}). Static because bean-factory post-processors must not
   * force early auto-configuration instantiation.
   *
   * @return the definition pruner
   */
  @Bean
  public static BeanDefinitionRegistryPostProcessor bundlingCdamDefinitionPruner() {
    return new BeanDefinitionRegistryPostProcessor() {
      @Override
      public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        if (!(registry instanceof ListableBeanFactory beanFactory)) {
          return;
        }
        boolean collaboratorMissing = !missingCdamBeans(beanFactory).isEmpty();
        if (registry.containsBeanDefinition(CDAM_DESTINATION_BEAN_NAME)
            && (collaboratorMissing || otherDestinationDefined(beanFactory))) {
          registry.removeBeanDefinition(CDAM_DESTINATION_BEAN_NAME);
          log.debug("Backing off the built-in CDAM destination: collaborator missing or another "
              + "BundleDestination bean is defined");
        }
        if (registry.containsBeanDefinition(CDAM_RESOLVER_BEAN_NAME) && collaboratorMissing) {
          registry.removeBeanDefinition(CDAM_RESOLVER_BEAN_NAME);
          log.debug("Backing off the built-in CDAM resolver: missing collaborator beans {}",
              missingCdamBeans(beanFactory));
        }
      }

      @Override
      public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
      }
    };
  }

  /**
   * The invariant production destination, publishing every finished bundle to CDAM. Registered
   * when all three {@code ccd.bundling.cdam.*} properties are present — the upload
   * classification is explicit configuration, never defaulted — then pruned by
   * {@link #bundlingCdamDefinitionPruner()} unless a {@link CaseDocumentClientApi} bean and a
   * {@link BundlingAuthenticationProvider} bean exist (see the class Javadoc for why the
   * authentication provider is consumer-provided) and no other {@link BundleDestination} bean is
   * defined. The collaborators are resolved at instantiation time, so it does not matter which
   * auto-configuration contributes them or in what order.
   *
   * @param caseDocumentClients the consuming service's CDAM client, wherever it is defined
   * @param authenticationProviders the consuming service's system-user authentication port
   * @param properties the bound {@code ccd.bundling} properties
   * @return the CDAM destination
   * @throws IllegalStateException if a collaborator bean cannot be resolved — possible only when
   *     its definition's type was not predictable when the pruner ran
   */
  @Bean
  @ConditionalOnProperty(prefix = "ccd.bundling.cdam",
      name = {"jurisdiction-id", "case-type-id", "classification"})
  public CdamBundleDestination bundlingCdamBundleDestination(
      ObjectProvider<CaseDocumentClientApi> caseDocumentClients,
      ObjectProvider<BundlingAuthenticationProvider> authenticationProviders,
      BundlingProperties properties) {
    BundlingProperties.Cdam cdam = properties.getCdam();
    return new CdamBundleDestination(
        requireCollaborator(caseDocumentClients, CaseDocumentClientApi.class),
        requireCollaborator(authenticationProviders, BundlingAuthenticationProvider.class),
        new CdamUploadSettings(
            cdam.getJurisdictionId(), cdam.getCaseTypeId(), cdam.getClassification(),
            cdam.isAttachToCase()));
  }

  /**
   * The built-in resolver for CDAM-sourced inputs, spooling each fetched binary to an owner-only
   * file under the configured temp directory. Registered under the same property condition as
   * the CDAM destination, pruned by {@link #bundlingCdamDefinitionPruner()} when a collaborator
   * bean is missing, and backs off only to a consumer-defined {@link CdamDocumentResolver};
   * other {@link DocumentResolver} beans register alongside it, not instead of it.
   *
   * @param caseDocumentClients the consuming service's CDAM client, wherever it is defined
   * @param authenticationProviders the consuming service's system-user authentication port
   * @param properties the bound {@code ccd.bundling} properties
   * @return the CDAM resolver
   * @throws IllegalStateException if a collaborator bean cannot be resolved — possible only when
   *     its definition's type was not predictable when the pruner ran
   */
  @Bean
  @ConditionalOnMissingBean(CdamDocumentResolver.class)
  @ConditionalOnProperty(prefix = "ccd.bundling.cdam",
      name = {"jurisdiction-id", "case-type-id", "classification"})
  public CdamDocumentResolver bundlingCdamDocumentResolver(
      ObjectProvider<CaseDocumentClientApi> caseDocumentClients,
      ObjectProvider<BundlingAuthenticationProvider> authenticationProviders,
      BundlingProperties properties) {
    return new CdamDocumentResolver(
        requireCollaborator(caseDocumentClients, CaseDocumentClientApi.class),
        requireCollaborator(authenticationProviders, BundlingAuthenticationProvider.class),
        baseTempDirectory(properties).resolve("ccd-bundling-cdam-spool"));
  }

  /**
   * Startup report for partial CDAM configuration: when some {@code ccd.bundling.cdam.*}
   * properties are set but the wiring is incomplete — a property or a collaborator bean is
   * missing — it says so at WARN, naming exactly which keys and beans are absent. A service that
   * sets none of the properties is legitimately not using CDAM and hears nothing; a fully wired
   * service hears nothing either.
   *
   * @param properties the bound {@code ccd.bundling} properties
   * @param beanFactory the bean factory, used to detect the collaborator beans
   * @return the report callback, invoked once all singletons exist
   */
  @Bean
  public SmartInitializingSingleton bundlingCdamWiringReport(
      BundlingProperties properties, ListableBeanFactory beanFactory) {
    return () -> {
      List<String> missingProperties = missingCdamProperties(properties);
      if (missingProperties.size() == 3) {
        return;
      }
      List<String> missingBeans = missingCdamBeans(beanFactory);
      if (missingProperties.isEmpty() && missingBeans.isEmpty()) {
        return;
      }
      log.warn("CDAM bundling is partially configured, so the built-in CDAM destination and "
              + "resolver were not registered. Missing properties: {}. Missing beans: {}. "
              + "Complete the wiring, or unset every ccd.bundling.cdam.* property if CDAM "
              + "bundling is not wanted.",
          missingProperties, missingBeans);
    };
  }

  /**
   * The renderer, assembled from every {@link DocumentResolver} bean in the context (the CDAM
   * resolver and any the consumer defines), the single {@link BundleDestination} bean, every
   * {@link BundlingExtension} bean in {@code @Order} order, the {@link DocmosisRenderService}
   * when one is present, the configured limits and concurrency, and the consumer's
   * {@link MeterRegistry} when one exists. Resolvers and the destination are collected from the
   * bean factory at instantiation time, so beans contributed by unordered auto-configurations
   * are seen. Backs off when the context has no resolver at all — a service that registers none
   * is not using bundling — and fails descriptively when wiring is attempted without a usable
   * destination or resolver.
   *
   * @param properties the bound {@code ccd.bundling} properties
   * @param beanFactory the bean factory the resolvers and destination are collected from
   * @param extensions every extension bean in the context, applied in {@code @Order} order
   * @param docmosisRenderService the Docmosis client, when configured
   * @param meterRegistry the consumer's meter registry, when one exists
   * @return the renderer
   * @throws IllegalStateException if wiring is attempted with no destination bean and no CDAM
   *     configuration, with no usable resolver, or with resolvers whose provider names collide
   */
  @Bean
  @ConditionalOnMissingBean(BundleRenderer.class)
  @ConditionalOnBean(DocumentResolver.class)
  public BundleRenderer bundleRenderer(
      BundlingProperties properties,
      ListableBeanFactory beanFactory,
      ObjectProvider<BundlingExtension> extensions,
      ObjectProvider<DocmosisRenderService> docmosisRenderService,
      ObjectProvider<MeterRegistry> meterRegistry) {
    Map<String, BundleDestination> destinations =
        beanFactory.getBeansOfType(BundleDestination.class);
    if (destinations.isEmpty()) {
      throw new IllegalStateException(
          "A BundleRenderer cannot be auto-configured without a BundleDestination. Either wire "
              + "the built-in CDAM destination — define CaseDocumentClientApi and "
              + "BundlingAuthenticationProvider beans and set ccd.bundling.cdam.jurisdiction-id, "
              + "ccd.bundling.cdam.case-type-id and ccd.bundling.cdam.classification — or define "
              + "a BundleDestination bean yourself (tests and local runs can use "
              + "FilesystemBundleDestination)." + cdamCollaboratorGap(properties, beanFactory));
    }
    if (destinations.size() > 1) {
      throw new IllegalStateException(
          "Multiple BundleDestination beans exist: " + destinations.keySet()
              + ". A renderer publishes through exactly one destination; remove all but one");
    }
    Map<String, DocumentResolver> resolvers = beanFactory.getBeansOfType(DocumentResolver.class);
    if (resolvers.isEmpty()) {
      throw new IllegalStateException(
          "A BundleRenderer cannot be auto-configured without at least one usable "
              + "DocumentResolver bean." + cdamCollaboratorGap(properties, beanFactory));
    }
    BundleRendererBuilder builder = BundleRenderer.builder();
    for (DocumentResolver resolver : resolvers.values()) {
      try {
        builder.resolver(resolver);
      } catch (IllegalArgumentException e) {
        throw new IllegalStateException(
            e.getMessage() + ". Two DocumentResolver beans declare the same provider: rename "
                + "your resolver's provider(), or — when the collision is with the built-in "
                + "'cdam' provider — define your own CdamDocumentResolver bean, which replaces "
                + "the built-in registration.", e);
      }
    }
    BundleDestination destination = destinations.values().iterator().next();
    builder.destination(destination);
    List<BundlingExtension> extensionList = extensions.orderedStream().toList();
    warnOnDuplicateExtensionNames(extensionList);
    extensionList.forEach(builder::extension);
    DocmosisRenderService docmosis = docmosisRenderService.getIfAvailable();
    if (docmosis != null) {
      builder.docmosis(docmosis);
    }
    builder.limits(effectiveLimits(properties.getLimits()));
    if (properties.getMaxConcurrentRenders() != null) {
      builder.maxConcurrentRenders(properties.getMaxConcurrentRenders());
    }
    MeterRegistry registry = meterRegistry.getIfAvailable();
    if (registry != null) {
      builder.meterRegistry(registry);
    }
    if (properties.getTempDirectory() != null) {
      builder.tempDirectory(baseTempDirectory(properties));
    }
    log.info(
        "Auto-configured BundleRenderer: resolvers={}, destination={}, docmosis={}, "
            + "extensions={}, metrics={}",
        resolvers.values().stream().map(DocumentResolver::provider).toList(),
        destination.getClass().getSimpleName(),
        docmosis != null,
        extensionList.stream().map(BundlingExtension::name).toList(),
        registry != null);
    return builder.build();
  }

  private static void warnOnDuplicateExtensionNames(List<BundlingExtension> extensions) {
    List<String> duplicates = extensions.stream()
        .collect(Collectors.groupingBy(
            BundlingExtension::name, LinkedHashMap::new, Collectors.counting()))
        .entrySet().stream()
        .filter(entry -> entry.getValue() > 1)
        .map(Map.Entry::getKey)
        .toList();
    if (!duplicates.isEmpty()) {
      log.warn("Multiple BundlingExtension beans share the name(s) {}. Registry errors attribute "
          + "failures by extension name, so give each extension a unique name()", duplicates);
    }
  }

  private static <T> T requireCollaborator(ObjectProvider<T> provider, Class<T> type) {
    T collaborator = provider.getIfAvailable();
    if (collaborator == null) {
      throw new IllegalStateException(
          "The built-in CDAM wiring needs a " + type.getSimpleName() + " bean, but none could "
              + "be resolved. Define one, or unset the ccd.bundling.cdam.* properties if CDAM "
              + "bundling is not wanted");
    }
    return collaborator;
  }

  private static boolean otherDestinationDefined(ListableBeanFactory beanFactory) {
    for (String name : beanFactory.getBeanNamesForType(BundleDestination.class, true, false)) {
      if (!CDAM_DESTINATION_BEAN_NAME.equals(name)) {
        return true;
      }
    }
    return false;
  }

  private static String cdamCollaboratorGap(
      BundlingProperties properties, ListableBeanFactory beanFactory) {
    if (!missingCdamProperties(properties).isEmpty()) {
      return "";
    }
    List<String> missingBeans = missingCdamBeans(beanFactory);
    if (missingBeans.isEmpty()) {
      return "";
    }
    return " ccd.bundling.cdam.* is fully set, but the built-in CDAM wiring backed off because "
        + "these beans are missing: " + String.join(", ", missingBeans) + ".";
  }

  private static List<String> missingCdamProperties(BundlingProperties properties) {
    BundlingProperties.Cdam cdam = properties.getCdam();
    List<String> missing = new ArrayList<>();
    if (cdam.getJurisdictionId() == null || cdam.getJurisdictionId().isBlank()) {
      missing.add("ccd.bundling.cdam.jurisdiction-id");
    }
    if (cdam.getCaseTypeId() == null || cdam.getCaseTypeId().isBlank()) {
      missing.add("ccd.bundling.cdam.case-type-id");
    }
    if (cdam.getClassification() == null) {
      missing.add("ccd.bundling.cdam.classification");
    }
    return missing;
  }

  private static List<String> missingCdamBeans(ListableBeanFactory beanFactory) {
    List<String> missing = new ArrayList<>();
    if (beanFactory.getBeanNamesForType(CaseDocumentClientApi.class, true, false).length == 0) {
      missing.add(CaseDocumentClientApi.class.getSimpleName());
    }
    if (beanFactory
        .getBeanNamesForType(BundlingAuthenticationProvider.class, true, false).length == 0) {
      missing.add(BundlingAuthenticationProvider.class.getSimpleName());
    }
    return missing;
  }

  private static Path baseTempDirectory(BundlingProperties properties) {
    Path configured = properties.getTempDirectory();
    if (configured == null) {
      return Path.of(System.getProperty("java.io.tmpdir"));
    }
    try {
      Files.createDirectories(configured);
    } catch (IOException e) {
      throw new IllegalStateException(
          "ccd.bundling.temp-directory (" + configured + ") is not a usable directory: "
              + e + ". Point it at a writable directory (it is created if absent)", e);
    }
    if (!Files.isWritable(configured)) {
      throw new IllegalStateException(
          "ccd.bundling.temp-directory (" + configured + ") is not writable");
    }
    return configured;
  }

  private static BundleLimits effectiveLimits(BundlingProperties.Limits limits) {
    BundleLimits defaults = BundleLimits.defaults();
    return new BundleLimits(
        limits.getMaxDocumentCount() != null
            ? limits.getMaxDocumentCount() : defaults.maxDocumentCount(),
        limits.getMaxSourceBytesPerDocument() != null
            ? limits.getMaxSourceBytesPerDocument() : defaults.maxSourceBytesPerDocument(),
        limits.getMaxOfficeSourceBytesPerDocument() != null
            ? limits.getMaxOfficeSourceBytesPerDocument()
            : defaults.maxOfficeSourceBytesPerDocument(),
        limits.getMaxOutputBytes() != null
            ? limits.getMaxOutputBytes() : defaults.maxOutputBytes(),
        limits.getMaxTotalPages() != null
            ? limits.getMaxTotalPages() : defaults.maxTotalPages(),
        limits.getMaxElapsed() != null
            ? limits.getMaxElapsed() : defaults.maxElapsed());
  }
}
