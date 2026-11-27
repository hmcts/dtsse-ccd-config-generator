package uk.gov.hmcts.ccd.sdk.doctoolkit.cdam;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.ONE_CASE_TYPE;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.SERVICE_TOKENS;
import static uk.gov.hmcts.ccd.sdk.doctoolkit.support.ToolkitFixtures.fixture;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocSource;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkit;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PdfResult;
import uk.gov.hmcts.ccd.sdk.doctoolkit.support.FakeCdam;
import uk.gov.hmcts.reform.idam.client.IdamClient;

/** IDAM can revoke the system user's token before the toolkit would sign in again. */
class RevokedTokenTest {
  @TempDir
  private Path work;

  private final AtomicInteger signIns = new AtomicInteger();
  private final IdamClient idam = new IdamClient(null, null) {
    @Override
    public String getAccessToken(String username, String password) {
      return "Bearer system-" + signIns.incrementAndGet();
    }
  };

  @Test
  void aTokenCdamRejectsIsReplacedAndTheFetchTriedOnceMore() throws Exception {
    FakeCdam cdam = new FakeCdam();
    UUID id = cdam.store(fixture("one-page.pdf"), "application/pdf", "claim.pdf");
    DocToolkit toolkit = DocToolkit.builder().tempDirectory(work)
        .cdam(cdam, SERVICE_TOKENS, new IdamSystemUser(idam, "system", "secret"), ONE_CASE_TYPE)
        .build();
    cdam.rejectNextToken();

    try (PdfResult result = toolkit.render(
        PdfRequest.builder("Claim").document(DocSource.cdam(id)).build())) {
      assertThat(result.failures()).isEmpty();
    }
    assertThat(cdam.fetchedAs).extracting(fetch -> fetch.split(" ")[1])
        .containsExactly("system-1", "system-2");
  }
}
