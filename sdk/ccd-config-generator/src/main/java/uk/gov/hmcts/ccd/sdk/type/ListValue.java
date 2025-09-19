package uk.gov.hmcts.ccd.sdk.type;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import javax.annotation.Nullable;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Builder
@Data
public class ListValue<T> {

  @Nullable
  private String id;

  private T value;

  @JsonCreator
  public ListValue(
      @JsonProperty("id") String id,
      @JsonProperty("value") T value
  ) {
    this.id = id;
    this.value = value;
  }
}
