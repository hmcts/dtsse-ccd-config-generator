package uk.gov.hmcts.ccd.sdk.bundling.cdam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import feign.FeignException;
import feign.Request;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.DocumentReference;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolutionFailureReason;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocument;
import uk.gov.hmcts.ccd.sdk.bundling.api.ResolvedDocuments;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;

class CdamDocumentResolverTest {

  private static final byte[] CONTENT = "document-bytes".getBytes(StandardCharsets.UTF_8);
  private static final UUID DOC_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  private final CaseDocumentClientApi client = mock(CaseDocumentClientApi.class);
  private final BundlingAuthenticationProvider authentication = mock(BundlingAuthenticationProvider.class);
  private final BundleExecutionContext context = BundleExecutionContext.empty();

  @TempDir
  private Path tempDir;

  private Path spoolDirectory;
  private CdamDocumentResolver resolver;

  @BeforeEach
  void setUp() {
    spoolDirectory = tempDir.resolve("spool");
    resolver = new CdamDocumentResolver(client, authentication, spoolDirectory);
    when(authentication.systemUserToken()).thenReturn("Bearer user-token");
    when(authentication.serviceToken()).thenReturn("service-token");
  }

  @Test
  void providerIsCdam() {
    assertThat(resolver.provider()).isEqualTo("cdam");
  }

  @Test
  void makesExactlyOneBinaryFetchPerDocumentAndReadsTheHeaders() throws Exception {
    when(client.getDocumentBinary(any(), any(), eq(DOC_ID))).thenReturn(
        ResponseEntity.ok()
            .header("OriginalFileName", "medical-report.pdf")
            .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
            .contentLength(CONTENT.length)
            .body(new ByteArrayResource(CONTENT)));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(DOC_ID)), context);

    verify(client).getDocumentBinary("Bearer user-token", "service-token", DOC_ID);
    verifyNoMoreInteractions(client);
    assertThat(outcome.failures()).isEmpty();
    try (ResolvedDocument document = outcome.resolved().get(reference(DOC_ID))) {
      assertThat(document.fileName()).isEqualTo("medical-report.pdf");
      assertThat(document.mediaType()).isEqualTo("application/pdf");
      assertThat(document.contentLength()).hasValue(CONTENT.length);
      assertThat(document.checksum()).isEmpty();
      assertThat(document.content().readAllBytes()).isEqualTo(CONTENT);
    }
  }

  @Test
  void fallsBackToTheQuotedContentDispositionFilename() throws Exception {
    when(client.getDocumentBinary(any(), any(), eq(DOC_ID))).thenReturn(
        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"hearing notes.pdf\"")
            .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
            .body(new ByteArrayResource(CONTENT)));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(DOC_ID)), context);

    try (ResolvedDocument document = outcome.resolved().get(reference(DOC_ID))) {
      assertThat(document.fileName()).isEqualTo("hearing notes.pdf");
    }
  }

  @Test
  void fallsBackToTheUnquotedContentDispositionFilename() throws Exception {
    when(client.getDocumentBinary(any(), any(), eq(DOC_ID))).thenReturn(
        ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=notes.pdf; size=42")
            .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
            .body(new ByteArrayResource(CONTENT)));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(DOC_ID)), context);

    try (ResolvedDocument document = outcome.resolved().get(reference(DOC_ID))) {
      assertThat(document.fileName()).isEqualTo("notes.pdf");
    }
  }

  @Test
  void fallsBackToTheDocumentIdWhenNoFilenameHeaderIsPresent() throws Exception {
    when(client.getDocumentBinary(any(), any(), eq(DOC_ID))).thenReturn(
        ResponseEntity.ok().body(new ByteArrayResource(CONTENT)));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(DOC_ID)), context);

    try (ResolvedDocument document = outcome.resolved().get(reference(DOC_ID))) {
      assertThat(document.fileName()).isEqualTo(DOC_ID.toString());
      assertThat(document.mediaType()).isEqualTo("application/octet-stream");
      assertThat(document.contentLength()).isEmpty();
    }
  }

  @Test
  void maps404ToNotFoundWithoutLeakingTheResponseBody() {
    when(client.getDocumentBinary(any(), any(), eq(DOC_ID)))
        .thenThrow(feignException(404, "secret error body"));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(DOC_ID)), context);

    assertThat(outcome.resolved()).isEmpty();
    assertThat(outcome.failures().get(reference(DOC_ID)).reason())
        .isEqualTo(ResolutionFailureReason.NOT_FOUND);
    assertThat(outcome.failures().get(reference(DOC_ID)).detail())
        .contains("404")
        .contains(DOC_ID.toString())
        .doesNotContain("secret error body")
        .doesNotContain("user-token");
  }

  @Test
  void maps403ToAccessDenied() {
    when(client.getDocumentBinary(any(), any(), eq(DOC_ID)))
        .thenThrow(feignException(403, "forbidden body"));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(DOC_ID)), context);

    assertThat(outcome.failures().get(reference(DOC_ID)).reason())
        .isEqualTo(ResolutionFailureReason.ACCESS_DENIED);
  }

  @Test
  void maps401ToAccessDenied() {
    when(client.getDocumentBinary(any(), any(), eq(DOC_ID)))
        .thenThrow(feignException(401, "unauthorised body"));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(DOC_ID)), context);

    assertThat(outcome.failures().get(reference(DOC_ID)).reason())
        .isEqualTo(ResolutionFailureReason.ACCESS_DENIED);
  }

  @Test
  void mapsServerErrorsToTransientFailure() {
    when(client.getDocumentBinary(any(), any(), eq(DOC_ID)))
        .thenThrow(feignException(503, "gateway body"));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(DOC_ID)), context);

    assertThat(outcome.failures().get(reference(DOC_ID)).reason())
        .isEqualTo(ResolutionFailureReason.TRANSIENT_FAILURE);
    assertThat(outcome.failures().get(reference(DOC_ID)).detail()).contains("503");
  }

  @Test
  void mapsUnreadableStreamsToTransientFailure() throws IOException {
    Resource body = mock(Resource.class);
    when(body.getInputStream()).thenThrow(new IOException("connection reset"));
    when(client.getDocumentBinary(any(), any(), eq(DOC_ID)))
        .thenReturn(ResponseEntity.ok().body(body));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(DOC_ID)), context);

    assertThat(outcome.failures().get(reference(DOC_ID)).reason())
        .isEqualTo(ResolutionFailureReason.TRANSIENT_FAILURE);
  }

  @Test
  void mapsAnEmptyResponseBodyToTransientFailure() {
    when(client.getDocumentBinary(any(), any(), eq(DOC_ID)))
        .thenReturn(ResponseEntity.ok().build());

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(DOC_ID)), context);

    assertThat(outcome.failures().get(reference(DOC_ID)).reason())
        .isEqualTo(ResolutionFailureReason.TRANSIENT_FAILURE);
  }

  @Test
  void mapsOtherClientErrorsToInvalidContentSoJobsDoNotRetryThem() {
    when(client.getDocumentBinary(any(), any(), eq(DOC_ID)))
        .thenThrow(feignException(415, "unsupported body"));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(DOC_ID)), context);

    assertThat(outcome.failures().get(reference(DOC_ID)).reason())
        .isEqualTo(ResolutionFailureReason.INVALID_CONTENT);
    assertThat(outcome.failures().get(reference(DOC_ID)).detail()).contains("415");
  }

  @Test
  void keeps429AsTransientSoJobsMayRetry() {
    when(client.getDocumentBinary(any(), any(), eq(DOC_ID)))
        .thenThrow(feignException(429, "throttled"));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(DOC_ID)), context);

    assertThat(outcome.failures().get(reference(DOC_ID)).reason())
        .isEqualTo(ResolutionFailureReason.TRANSIENT_FAILURE);
  }

  @Test
  void duplicateReferencesAreFetchedOnce() {
    when(client.getDocumentBinary(any(), any(), eq(DOC_ID))).thenReturn(
        ResponseEntity.ok()
            .header("OriginalFileName", "once.pdf")
            .body(new ByteArrayResource(CONTENT)));

    ResolvedDocuments outcome = resolver.resolveAll(
        List.of(reference(DOC_ID), reference(DOC_ID)), context);

    verify(client).getDocumentBinary(any(), any(), eq(DOC_ID));
    verifyNoMoreInteractions(client);
    assertThat(outcome.resolved()).containsOnlyKeys(reference(DOC_ID));
    assertThat(outcome.failures()).isEmpty();
  }

  @Test
  void aTokenAcquisitionFailureFailsEveryReferenceTransientlyWithoutCallingCdam() {
    when(authentication.systemUserToken())
        .thenThrow(new IllegalStateException("idam down at http://idam with secret"));
    UUID otherId = UUID.fromString("44444444-4444-4444-4444-444444444444");

    ResolvedDocuments outcome = resolver.resolveAll(
        List.of(reference(DOC_ID), reference(otherId)), context);

    verifyNoInteractions(client);
    assertThat(outcome.resolved()).isEmpty();
    assertThat(outcome.failures()).containsOnlyKeys(reference(DOC_ID), reference(otherId));
    assertThat(outcome.failures().values()).allSatisfy(failure -> {
      assertThat(failure.reason()).isEqualTo(ResolutionFailureReason.TRANSIENT_FAILURE);
      assertThat(failure.detail())
          .contains("token acquisition")
          .contains("IllegalStateException")
          .doesNotContain("secret")
          .doesNotContain("http://idam");
    });
  }

  @Test
  void spoolsToAnOwnerOnlyFileAndDeletesItOnClose() throws Exception {
    when(client.getDocumentBinary(any(), any(), eq(DOC_ID))).thenReturn(
        ResponseEntity.ok()
            .header("OriginalFileName", "spooled.pdf")
            .body(new ByteArrayResource(CONTENT)));

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference(DOC_ID)), context);

    ResolvedDocument document = outcome.resolved().get(reference(DOC_ID));
    Path spooled;
    try (Stream<Path> files = Files.list(spoolDirectory)) {
      spooled = files.findFirst().orElseThrow();
    }
    if (spoolDirectory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
      assertThat(Files.getPosixFilePermissions(spooled))
          .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      assertThat(Files.getPosixFilePermissions(spoolDirectory))
          .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }
    assertThat(document.content().readAllBytes()).isEqualTo(CONTENT);

    document.close();

    assertThat(spooled).doesNotExist();
  }

  @Test
  void rejectsANonUuidReferenceWithoutCallingCdam() {
    DocumentReference reference = new DocumentReference("cdam", "not-a-uuid");

    ResolvedDocuments outcome = resolver.resolveAll(List.of(reference), context);

    verifyNoInteractions(client);
    assertThat(outcome.failures().get(reference).reason()).isEqualTo(ResolutionFailureReason.NOT_FOUND);
    assertThat(outcome.failures().get(reference).detail()).contains("not-a-uuid");
  }

  @Test
  void aMixedBatchReturnsSuccessesAndTypedFailuresTogether() {
    UUID missingId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    UUID brokenId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    when(client.getDocumentBinary(any(), any(), eq(DOC_ID))).thenReturn(
        ResponseEntity.ok()
            .header("OriginalFileName", "ok.pdf")
            .body(new ByteArrayResource(CONTENT)));
    when(client.getDocumentBinary(any(), any(), eq(missingId)))
        .thenThrow(feignException(404, "missing"));
    when(client.getDocumentBinary(any(), any(), eq(brokenId)))
        .thenThrow(feignException(500, "boom"));

    ResolvedDocuments outcome = resolver.resolveAll(
        List.of(reference(DOC_ID), reference(missingId), reference(brokenId)), context);

    verify(client).getDocumentBinary(any(), any(), eq(DOC_ID));
    verify(client).getDocumentBinary(any(), any(), eq(missingId));
    verify(client).getDocumentBinary(any(), any(), eq(brokenId));
    verifyNoMoreInteractions(client);
    assertThat(outcome.resolved()).containsOnlyKeys(reference(DOC_ID));
    assertThat(outcome.failures()).containsOnlyKeys(reference(missingId), reference(brokenId));
    assertThat(outcome.failures().get(reference(missingId)).reason())
        .isEqualTo(ResolutionFailureReason.NOT_FOUND);
    assertThat(outcome.failures().get(reference(brokenId)).reason())
        .isEqualTo(ResolutionFailureReason.TRANSIENT_FAILURE);
  }

  private static DocumentReference reference(UUID id) {
    return new DocumentReference("cdam", id.toString());
  }

  private static FeignException feignException(int status, String body) {
    Request request = Request.create(
        Request.HttpMethod.GET, "/cases/documents/" + DOC_ID + "/binary",
        Map.of(), null, StandardCharsets.UTF_8, null);
    return FeignException.errorStatus(
        "getDocumentBinary",
        feign.Response.builder()
            .status(status)
            .reason("error")
            .request(request)
            .body(body, StandardCharsets.UTF_8)
            .build());
  }
}
