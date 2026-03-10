package uk.gov.hmcts.reform.typedshow;

import com.fasterxml.jackson.annotation.JsonProperty;

public enum TypedShowVisibility {
  @JsonProperty("show")
  SHOW,
  HIDE
}
