package uk.gov.hmcts.b4.model;

import lombok.Data;

/** A normal unwrapped parent: CaseData's @Data generates getWorkAllocation(), so its leaf is placeable. */
@Data
public class WorkAllocationParent {
  private String workAllocationField;
}
