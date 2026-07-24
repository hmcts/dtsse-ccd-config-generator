package uk.gov.hmcts.example.model.event;

/**
 * A prefixed {@code @JsonUnwrapped} sub-object: its members flatten into CaseData's namespace as
 * {@code hearing + capitalize(member)} — {@code hearingType}, {@code hearingLength}.
 */
public class HearingEventData {

  private String type;

  private Integer length;
}
