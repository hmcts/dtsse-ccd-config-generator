package uk.gov.hmcts.ccd.sdk.doctoolkit.spring;

import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkit;
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
 *   <li>CDAM documents are fetched, and results uploaded, as the IDAM user in
 *       {@code ccd.doc-toolkit.system-user.username} and {@code password}, when they are set and
 *       the application has a {@link CaseDocumentClientApi}, an {@link AuthTokenGenerator} and an
 *       {@link IdamClient}.</li>
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
    DocToolkitProperties.SystemUser user = properties.getSystemUser();
    IdamClient idamClient = idam.getIfAvailable();
    JdbcTemplate database = jdbc.getIfAvailable();
    if (client != null && tokens != null && idamClient != null && database != null
        && user.getUsername() != null && !user.getUsername().isBlank()) {
      builder.cdam(client, tokens,
          new IdamSystemUser(idamClient, user.getUsername(), user.getPassword()),
          new JdbcCaseLocator(database));
    }
    return builder.build();
  }
}
