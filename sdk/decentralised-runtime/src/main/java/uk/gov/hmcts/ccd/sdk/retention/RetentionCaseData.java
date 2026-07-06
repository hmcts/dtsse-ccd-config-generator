package uk.gov.hmcts.ccd.sdk.retention;

import java.time.LocalDate;

public record RetentionCaseData(
    Long reference,
    Long id,
    String caseTypeId,
    String jurisdiction,
    LocalDate resolvedTtl
) {
}
