package uk.gov.hmcts.ccd.sdk.api;

public class EventDefaults {

  private boolean omitLiveFrom;
  private boolean omitPublish;
  private String endButtonLabel;

  public EventDefaults omitLiveFrom() {
    this.omitLiveFrom = true;
    return this;
  }

  public EventDefaults includeLiveFrom() {
    this.omitLiveFrom = false;
    return this;
  }

  public EventDefaults omitPublish() {
    this.omitPublish = true;
    return this;
  }

  public EventDefaults includePublish() {
    this.omitPublish = false;
    return this;
  }

  public EventDefaults noEndButtonLabel() {
    return endButtonLabel("");
  }

  public EventDefaults endButtonLabel(String label) {
    this.endButtonLabel = label;
    return this;
  }

  boolean isOmitLiveFrom() {
    return omitLiveFrom;
  }

  boolean isOmitPublish() {
    return omitPublish;
  }

  boolean hasEndButtonLabel() {
    return endButtonLabel != null;
  }

  String getEndButtonLabel() {
    return endButtonLabel;
  }
}
