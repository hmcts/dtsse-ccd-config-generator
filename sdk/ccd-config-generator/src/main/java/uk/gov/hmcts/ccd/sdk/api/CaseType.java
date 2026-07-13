package uk.gov.hmcts.ccd.sdk.api;

import java.time.LocalDate;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;

/**
 * Metadata for a CCD case type.
 */
@Builder
@Value
public class CaseType {

  @NonNull
  String id;
  @NonNull
  String name;
  @NonNull
  String description;
  @Builder.Default
  LocalDate liveFrom = LocalDate.of(2017, 1, 1);
  String printableDocumentsUrl;
  Boolean enableForDeletion;
  Integer retriesTimeoutUrlPrintEvent;
}
