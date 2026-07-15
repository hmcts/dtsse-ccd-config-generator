package uk.gov.hmcts.ccd.sdk.converter.model;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Value;

/**
 * One wizard page of an event, assembled from CaseEventToFields rows sharing a PageID,
 * plus any EventToComplexTypes overrides scoped to fields on the page.
 */
@Value
@Builder
public class PageModel {

  String pageId;
  String label;
  Integer displayOrder;
  String showCondition;

  List<PageField> fields;

  @Value
  @Builder
  public static class PageField {

    String caseFieldId;

    /** MANDATORY / OPTIONAL / READONLY / HIDDEN / COMPLEX. */
    String displayContext;

    String displayContextParameter;
    String label;
    String hint;
    String showCondition;

    /**
     * ShowSummaryChangeOption: whether the field shows on the event's check-your-answers summary.
     * The SDK's field builder defaults this on (Y); {@code FALSE} selects a {@code *NoSummary}
     * builder variant so the generated ShowSummaryChangeOption matches the input.
     */
    Boolean showSummary;
    String defaultValue;
    Boolean retainHiddenValue;
    Boolean publish;
    String publishAs;
    Boolean nullifyByDefault;
    Integer displayOrder;
    Integer pageColumnNumber;

    /**
     * EventToComplexTypes rows scoped to this field on this event, keyed by
     * ListElementCode; raw column maps are preserved for the emitter.
     */
    Map<String, Map<String, Object>> complexTypeOverrides;
  }
}
