package uk.gov.hmcts.reform;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.reform.fpl.access.BulkScan;

/**
 * Case data for {@link UnwrappedCollisionCaseType}, reproducing sscs's collision: the
 * {@code @JsonUnwrapped} container is named {@code caseOutcome} and the type it holds declares its
 * own {@code caseOutcome} leaf, so the container's Java member name equals a real field's CCD ID.
 *
 * <p>{@link #plainField} keeps the case type honest — a field on no unwrapped path at all.
 */
@Data
public class UnwrappedCollisionCaseData {

  @JsonUnwrapped
  private UnwrappedCollisionOutcome caseOutcome;

  @CCD(label = "Plain field", access = {BulkScan.class})
  private String plainField;
}
