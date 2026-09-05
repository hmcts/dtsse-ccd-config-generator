package uk.gov.hmcts.example.enums;

import uk.gov.hmcts.ccd.sdk.api.HasLabel;

/**
 * An enum that already implements the SDK's {@code HasLabel}. {@code FixedListGenerator} reads that
 * FIRST — before {@code @CCD(label)} — so pinning a label here would be shadowed and the patch must
 * leave its constants alone rather than claim a fix it did not make.
 */
public enum LabelBearingList implements HasLabel {

  FIRST("Something the enum itself says");

  private final String label;

  LabelBearingList(String label) {
    this.label = label;
  }

  @Override
  public String getLabel() {
    return label;
  }
}
