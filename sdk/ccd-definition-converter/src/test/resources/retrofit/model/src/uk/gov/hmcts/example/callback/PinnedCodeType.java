package uk.gov.hmcts.example.callback;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A fixed list whose enum models every one of the definition's codes but names none of its constants
 * after them: each carries its own {@code @JsonProperty} pinning the code instead (sscs's
 * {@code CommunicationRequestTopic} names {@code APPELLANT_PERSONAL_INFORMATION} and pins
 * {@code appellantPersonalInfo}).
 *
 * <p>Regression: resolving a code only by constant NAME concluded that {@code alphaTopic} had no constant
 * and ADDED one for it, so the emitted list carried two rows for the same code — worse than the label
 * divergence the addition was closing. A constant carries a code when it EMITS it, whether that is by its
 * name or by a {@code @JsonProperty} the enum honours.
 */
public enum PinnedCodeType {

  @JsonProperty("alphaTopic")
  ALPHA_TOPIC_LONG_NAME("Alpha topic"),

  @JsonProperty("betaTopic")
  BETA_TOPIC_LONG_NAME("Beta topic");

  private final String value;

  PinnedCodeType(String value) {
    this.value = value;
  }

  public String getValue() {
    return value;
  }
}
