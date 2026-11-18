package uk.gov.hmcts.ccd.sdk.bundling.managed;

import static org.assertj.core.api.Assertions.assertThat;

import feign.FeignException;
import feign.Request;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason;

class CdamBundleDocumentResolverTest {

  @Test
  void onlyCdamSayingSoMakesADocumentMissingOrForbidden() {
    assertThat(reason(404)).isEqualTo(ResolutionFailureReason.NOT_FOUND);
    assertThat(reason(401)).isEqualTo(ResolutionFailureReason.ACCESS_DENIED);
    assertThat(reason(403)).isEqualTo(ResolutionFailureReason.ACCESS_DENIED);
    // Anything else says nothing about the document: a request timeout, throttling, a server
    // error, or a response that broke off while its body was being read.
    assertThat(reason(408)).isEqualTo(ResolutionFailureReason.TRANSIENT_FAILURE);
    assertThat(reason(429)).isEqualTo(ResolutionFailureReason.TRANSIENT_FAILURE);
    assertThat(reason(503)).isEqualTo(ResolutionFailureReason.TRANSIENT_FAILURE);
    assertThat(reason(200)).isEqualTo(ResolutionFailureReason.TRANSIENT_FAILURE);
  }

  private static ResolutionFailureReason reason(int status) {
    Request request = Request.create(Request.HttpMethod.GET, "http://cdam", Map.of(), null,
        StandardCharsets.UTF_8, null);
    FeignException failure = status == 200
        ? new FeignException.FeignClientException(200, "Read timed out", request, null, Map.of())
        : FeignException.errorStatus("getDocumentBinary",
            Response.builder().status(status).request(request).build());
    return CdamBundleDocumentResolver.failure(failure).reason();
  }
}
