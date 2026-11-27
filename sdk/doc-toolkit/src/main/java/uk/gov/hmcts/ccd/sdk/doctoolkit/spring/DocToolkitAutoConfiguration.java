package uk.gov.hmcts.ccd.sdk.doctoolkit.spring;

import java.nio.file.Path;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkit;
import uk.gov.hmcts.ccd.sdk.doctoolkit.cdam.CdamSystemUserTokenProvider;
import uk.gov.hmcts.ccd.sdk.doctoolkit.cdam.IdamSystemUser;
import uk.gov.hmcts.ccd.sdk.doctoolkit.cdam.JdbcCaseLocator;
import uk.gov.hmcts.ccd.sdk.doctoolkit.docmosis.DocmosisConnection;
import uk.gov.hmcts.ccd.sdk.doctoolkit.docmosis.DocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.doctoolkit.docmosis.HttpDocmosisRenderService;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.idam.client.IdamClient;

/**
 * Provides the {@link DocToolkit} bean.
 *
 * <ul>
 *   <li>Office formats are converted through Docmosis when
 *       {@code ccd.doc-toolkit.docmosis.convert-endpoint} and {@code access-key} are set, or
 *       through the application's own {@link DocmosisRenderService} bean.</li>
 *   <li>CDAM documents are fetched, and results uploaded, using the application's
 *       {@link CdamSystemUserTokenProvider} or the configured IDAM system user.</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(DocToolkitProperties.class)
public class DocToolkitAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public DocToolkit docToolkit(DocToolkitProperties properties,
      ObjectProvider<DocmosisRenderService> docmosis,
      ObjectProvider<CaseDocumentClientApi> cdam,
      ObjectProvider<AuthTokenGenerator> serviceTokens,
      ObjectProvider<CdamSystemUserTokenProvider> systemUser,
      ObjectProvider<IdamClient> idam,
      ObjectProvider<JdbcTemplate> jdbc) {
    Path temp = properties.getTempDirectory() != null
        ? properties.getTempDirectory() : Path.of(System.getProperty("java.io.tmpdir"));
    DocToolkit.Builder builder = DocToolkit.builder().tempDirectory(temp);
    DocmosisRenderService converting = docmosis.getIfAvailable();
    DocToolkitProperties.Docmosis connection = properties.getDocmosis();
    if (converting == null && connection.getConvertEndpoint() != null
        && connection.getAccessKey() != null && !connection.getAccessKey().isBlank()) {
      converting = new HttpDocmosisRenderService(
          DocmosisConnection.withDefaults(connection.getConvertEndpoint(),
              connection.getAccessKey()),
          temp.resolve("ccd-doc-toolkit-docmosis"));
    }
    if (converting != null) {
      builder.docmosis(converting);
    }
    CaseDocumentClientApi client = cdam.getIfAvailable();
    AuthTokenGenerator tokens = serviceTokens.getIfAvailable();
    JdbcTemplate database = jdbc.getIfAvailable();
    if (client != null && tokens != null && database != null) {
      Supplier<String> provider = systemUser.getIfAvailable();
      if (provider == null) {
        provider = configuredSystemUser(properties.getSystemUser(), idam);
      }
      if (provider != null) {
        builder.cdam(client, tokens, provider, new JdbcCaseLocator(database));
      }
    }
    return builder.build();
  }

  private static IdamSystemUser configuredSystemUser(
      DocToolkitProperties.SystemUser user, ObjectProvider<IdamClient> idam) {
    if (user.getUsername() == null || user.getUsername().isBlank()) {
      return null;
    }
    IdamClient client = idam.getIfAvailable();
    return client == null ? null : new IdamSystemUser(client, user.getUsername(), user.getPassword());
  }
}
