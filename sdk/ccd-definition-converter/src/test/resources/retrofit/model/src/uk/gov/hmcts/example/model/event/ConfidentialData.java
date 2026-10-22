package uk.gov.hmcts.example.model.event;

/**
 * A prefix-less {@code @JsonUnwrapped} sub-object: its members flatten verbatim (no capitalisation
 * change) into CaseData's namespace — {@code confidentialNote}.
 */
public class ConfidentialData {

  private String confidentialNote;
}
