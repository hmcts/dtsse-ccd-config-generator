package uk.gov.hmcts.ccd.sdk.bundling.cdam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import feign.Request;
import feign.RetryableException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocuments;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

/**
 * Adversarial review counter-examples for the CDAM adapters. Every finding's proof test is now
 * enabled and asserts the fixed behaviour, serving as a live regression; the remaining tests are
 * regression evidence that the attacked area held.
 */
class CdamAdversarialReviewTest {

  private static final byte[] CONTENT = "document-bytes".getBytes(StandardCharsets.UTF_8);
  private static final UUID POISONED_ID = UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111");
  private static final UUID HEALTHY_ID = UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222");

  private final CaseDocumentClientApi client = mock(CaseDocumentClientApi.class);
  private final BundlingAuthenticationProvider authentication = mock(BundlingAuthenticationProvider.class);
  private final BundleExecutionContext context = BundleExecutionContext.empty();

  @TempDir
  private Path tempDir;

  private CdamDocumentResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new CdamDocumentResolver(client, authentication, tempDir.resolve("spool"));
    when(authentication.systemUserToken()).thenReturn("Bearer user-token");
    when(authentication.serviceToken()).thenReturn("service-token");
  }

  /**
   * FINDING F1, FIXED (resolver batch abort): {@code fetch()} used to parse response headers via
   * {@code HttpHeaders.getContentType()}, whose {@code InvalidMediaTypeException} escaped
   * {@code resolveAll} untyped and aborted the whole batch for one document stored in dm-store
   * with a malformed mime type. The fix reads the Content-Type header raw (the pipeline's
   * content-based detection is the backstop), so the poisoned document now RESOLVES with the raw
   * declared value and the batch survives; a catch-all in the loop additionally maps any future
   * unexpected RuntimeException to a typed per-reference failure.
   */
  @Test
  void aMalformedContentTypeOnOneDocumentMustNotAbortTheWholeBatch() throws Exception {
    when(client.getDocumentBinary(any(), any(), eq(POISONED_ID))).thenReturn(
        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, "pdf") // no slash: MediaType.parseMediaType throws
            .header("OriginalFileName", "poisoned.pdf")
            .body(new ByteArrayResource(CONTENT)));
    when(client.getDocumentBinary(any(), any(), eq(HEALTHY_ID))).thenReturn(
        ResponseEntity.ok()
            .header("OriginalFileName", "healthy.pdf")
            .body(new ByteArrayResource(CONTENT)));

    ResolvedDocuments outcome = resolver.resolveAll(
        List.of(reference(POISONED_ID), reference(HEALTHY_ID)), context);

    assertThat(outcome.failures()).isEmpty();
    assertThat(outcome.resolved()).containsKey(reference(HEALTHY_ID));
    try (ResolvedDocument document = outcome.resolved().get(reference(POISONED_ID))) {
      // The raw declared value is passed through untouched for the pipeline to verify.
      assertThat(document.mediaType()).isEqualTo("pdf");
    }
  }

  /**
   * FINDING F1, FIXED (same defect class): {@code HttpHeaders.getContentLength()} used to do
   * {@code Long.parseLong} on the raw header and its {@code NumberFormatException} aborted the
   * batch. The fix parses Content-Length defensively: garbage degrades to an empty
   * {@code OptionalLong} and the document still resolves.
   */
  @Test
  void aMalformedContentLengthOnOneDocumentMustNotAbortTheWholeBatch() throws Exception {
    when(client.getDocumentBinary(any(), any(), eq(POISONED_ID))).thenReturn(
        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_LENGTH, "unknown")
            .header("OriginalFileName", "poisoned.pdf")
            .body(new ByteArrayResource(CONTENT)));
    when(client.getDocumentBinary(any(), any(), eq(HEALTHY_ID))).thenReturn(
        ResponseEntity.ok()
            .header("OriginalFileName", "healthy.pdf")
            .body(new ByteArrayResource(CONTENT)));

    ResolvedDocuments outcome = resolver.resolveAll(
        List.of(reference(POISONED_ID), reference(HEALTHY_ID)), context);

    assertThat(outcome.failures()).isEmpty();
    assertThat(outcome.resolved()).containsKey(reference(HEALTHY_ID));
    try (ResolvedDocument document = outcome.resolved().get(reference(POISONED_ID))) {
      assertThat(document.contentLength()).isEmpty();
    }
  }

  /**
   * REGRESSION (area survived): a Feign transport failure surfaces as {@code RetryableException}
   * with status -1 whose message embeds the request URL. The resolver maps it to
   * TRANSIENT_FAILURE per reference and never copies the message into the failure detail.
   */
  @Test
  void transportFailuresMapToTransientPerDocumentWithoutLeakingTheRequestUrl() {
    Request request = Request.create(
        Request.HttpMethod.GET, "http://cdam/cases/documents/" + POISONED_ID + "/binary",
        Map.of(), null, StandardCharsets.UTF_8, null);
    when(client.getDocumentBinary(any(), any(), eq(POISONED_ID))).thenThrow(
        new RetryableException(
            -1,
            "Connection refused executing GET http://cdam/cases/documents/" + POISONED_ID + "/binary",
            Request.HttpMethod.GET, (Long) null, request));
    when(client.getDocumentBinary(any(), any(), eq(HEALTHY_ID))).thenReturn(
        ResponseEntity.ok()
            .header("OriginalFileName", "healthy.pdf")
            .body(new ByteArrayResource(CONTENT)));

    ResolvedDocuments outcome = resolver.resolveAll(
        List.of(reference(POISONED_ID), reference(HEALTHY_ID)), context);

    assertThat(outcome.resolved()).containsKey(reference(HEALTHY_ID));
    assertThat(outcome.failures().get(reference(POISONED_ID)).reason())
        .isEqualTo(ResolutionFailureReason.TRANSIENT_FAILURE);
    assertThat(outcome.failures().get(reference(POISONED_ID)).detail())
        .doesNotContain("http://cdam")
        .doesNotContain("Connection refused");
  }

  /**
   * REGRESSION (area survived): a non-Feign RuntimeException thrown by the client call itself is
   * caught per reference and only the exception's class name — never its message, which may embed
   * headers or URLs — reaches the failure detail.
   */
  @Test
  void anUnexpectedRuntimeExceptionDuringTheFetchFailsOnlyThatDocumentAndHidesItsMessage() {
    when(client.getDocumentBinary(any(), any(), eq(POISONED_ID))).thenThrow(
        new IllegalStateException("request headers: Authorization=Bearer super-secret"));
    when(client.getDocumentBinary(any(), any(), eq(HEALTHY_ID))).thenReturn(
        ResponseEntity.ok()
            .header("OriginalFileName", "healthy.pdf")
            .body(new ByteArrayResource(CONTENT)));

    ResolvedDocuments outcome = resolver.resolveAll(
        List.of(reference(POISONED_ID), reference(HEALTHY_ID)), context);

    assertThat(outcome.resolved()).containsKey(reference(HEALTHY_ID));
    assertThat(outcome.failures().get(reference(POISONED_ID)).reason())
        .isEqualTo(ResolutionFailureReason.TRANSIENT_FAILURE);
    assertThat(outcome.failures().get(reference(POISONED_ID)).detail())
        .contains("IllegalStateException")
        .doesNotContain("super-secret");
  }

  /**
   * EVIDENCE for the memory finding: the client's Feign decoder path
   * (ResponseEntityDecoder -> SpringDecoder -> ResourceHttpMessageConverter) materialises a
   * declared {@code Resource} body as a fully buffered {@code ByteArrayResource} — the whole
   * binary is on the heap before the resolver ever sees the response. Combined with
   * {@code resolveAll} holding every resolved document in a map until the batch completes, the
   * peak heap for a batch is the SUM of all document sizes (design targets: 20 x 300 MB).
   * em-stitching-api's production code confirms the runtime type by casting the body to
   * ByteArrayResource (CdamService.downloadFile).
   */
  @Test
  void theClientDecoderBuffersTheWholeBinaryOnTheHeapBeforeTheAdapterSeesIt() throws Exception {
    byte[] body = new byte[1024 * 1024];
    MockHttpInputMessage message = new MockHttpInputMessage(new ByteArrayInputStream(body));

    Resource decoded = new ResourceHttpMessageConverter().read(Resource.class, message);

    // The converter copied the entire stream into memory: this is what getDocumentBinary returns.
    assertThat(decoded).isInstanceOf(ByteArrayResource.class);
    assertThat(decoded.contentLength()).isEqualTo(body.length);
  }

  /**
   * DOCUMENTED DEGRADATION: RFC 5987 {@code filename*=} parameters (what a UTF-8 filename is sent
   * as when dm-store encodes non-ASCII names) do not match the resolver's
   * {@code filename=} regex, so the document silently degrades to its UUID as the display name.
   */
  @Test
  void anRfc5987EncodedFilenameDegradesToTheDocumentUuid() throws Exception {
    when(client.getDocumentBinary(any(), any(), eq(HEALTHY_ID))).thenReturn(
        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''na%C3%AFve%20report.pdf")
            .body(new ByteArrayResource(CONTENT)));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(HEALTHY_ID)), context);

    try (ResolvedDocument document = outcome.resolved().get(reference(HEALTHY_ID))) {
      assertThat(document.fileName()).isEqualTo(HEALTHY_ID.toString());
    }
  }

  /**
   * DOCUMENTED DEFECT (minor): an unquoted filename with a stray trailing quote is captured
   * including the quote character, because the unquoted alternative {@code [^;\s]+} admits '"'.
   */
  @Test
  void anUnquotedFilenameWithAStrayQuoteKeepsTheQuote() throws Exception {
    when(client.getDocumentBinary(any(), any(), eq(HEALTHY_ID))).thenReturn(
        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=report.pdf\"")
            .body(new ByteArrayResource(CONTENT)));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(HEALTHY_ID)), context);

    try (ResolvedDocument document = outcome.resolved().get(reference(HEALTHY_ID))) {
      assertThat(document.fileName()).isEqualTo("report.pdf\"");
    }
  }

  /**
   * FINDING F7, FIXED (filename sanitisation): the resolver used to pass the CDAM-supplied
   * OriginalFileName through verbatim, traversal segments included, so any downstream consumer
   * joining it onto a directory escaped its spool. The adapter now sanitises at the boundary:
   * control characters are stripped, only the final path segment survives, leading dots are
   * removed, and the document UUID is the fallback when nothing safe remains.
   */
  @Test
  void aPathTraversalOriginalFileNameIsSanitisedToItsFinalSegment() throws Exception {
    when(client.getDocumentBinary(any(), any(), eq(HEALTHY_ID))).thenReturn(
        ResponseEntity.ok()
            .header("OriginalFileName", "../../../etc/cron.d/evil.pdf")
            .body(new ByteArrayResource(CONTENT)));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(HEALTHY_ID)), context);

    try (ResolvedDocument document = outcome.resolved().get(reference(HEALTHY_ID))) {
      assertThat(document.fileName()).isEqualTo("evil.pdf");
    }
  }

  /**
   * FINDING F7 companion: a header name that is nothing but traversal characters and control
   * codes sanitises to nothing, so the document UUID is used instead.
   */
  @Test
  void aWhollyUnsafeOriginalFileNameFallsBackToTheDocumentUuid() throws Exception {
    when(client.getDocumentBinary(any(), any(), eq(HEALTHY_ID))).thenReturn(
        ResponseEntity.ok()
            .header("OriginalFileName", "..\\..\r\n/...")
            .body(new ByteArrayResource(CONTENT)));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(HEALTHY_ID)), context);

    try (ResolvedDocument document = outcome.resolved().get(reference(HEALTHY_ID))) {
      assertThat(document.fileName()).isEqualTo(HEALTHY_ID.toString());
    }
  }

  /**
   * FINDING F6, FIXED (upload misdiagnosis): CdamBundleDestination used to acquire the IDAM/S2S
   * tokens INSIDE the try block that catches FeignException, so a FeignException thrown by the
   * consumer's token provider (IDAM is itself a Feign client in HMCTS services) was reported as
   * "CDAM upload ... failed with HTTP status N" even though CDAM was never called. Tokens are
   * now acquired before that try and an acquisition failure names token acquisition.
   */
  @Test
  void aTokenAcquisitionFailureMustNotBeReportedAsACdamUploadHttpFailure() {
    CdamBundleDestination destination = new CdamBundleDestination(
        client, authentication, new CdamUploadSettings("BBA3", "Benefit",
            uk.gov.hmcts.reform.ccd.document.am.model.Classification.RESTRICTED));
    Request request = Request.create(
        Request.HttpMethod.POST, "http://idam/o/token", Map.of(), null, StandardCharsets.UTF_8, null);
    when(authentication.systemUserToken()).thenThrow(new feign.FeignException.BadGateway(
        "idam down", request, null, Map.of()));

    assertThatThrownBy(() -> destination.store(
        new InMemoryBundleArtifact("bundle.pdf", CONTENT), context))
        .isInstanceOf(CdamUploadException.class)
        .satisfies(e -> {
          verifyNoInteractions(client);
          // The message must not claim CDAM returned an HTTP status when CDAM was never reached.
          assertThat(e.getMessage())
              .doesNotContain("HTTP status 502")
              .contains("Token acquisition");
        });
  }

  private static DocumentReference reference(UUID id) {
    return new DocumentReference("cdam", id.toString());
  }
}
