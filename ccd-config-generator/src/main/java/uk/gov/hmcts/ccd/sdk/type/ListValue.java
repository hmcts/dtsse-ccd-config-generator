package uk.gov.hmcts.ccd.sdk.type;

import javax.annotation.Nullable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;

@AllArgsConstructor
@Builder
@Data
@Jacksonized
public class ListValue<T> {

  @Nullable
  private final String id;

  private final T value;
}
