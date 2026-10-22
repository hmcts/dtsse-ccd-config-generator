package uk.gov.hmcts.ccd.sdk.converter.model;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/** A CaseTypeTab, mapped onto ConfigBuilder.tab(...). */
@Value
@Builder
public class TabModel {

  String tabId;
  String label;
  Integer displayOrder;
  String showCondition;

  /** Role restriction (UserRole column on the tab), or null for unrestricted tabs. */
  String userRole;

  List<TabField> fields;

  @Value
  @Builder
  public static class TabField {

    String caseFieldId;
    Integer displayOrder;
    String showCondition;
    String displayContextParameter;
    String label;
  }
}
