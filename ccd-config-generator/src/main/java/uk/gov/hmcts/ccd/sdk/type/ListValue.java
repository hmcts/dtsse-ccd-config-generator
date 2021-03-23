package uk.gov.hmcts.ccd.sdk.type;

import javax.annotation.Nullable;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ListValue<T> {

  @Nullable
  private final String id;

  private final T value;
}
