package uk.gov.hmcts.ccd.sdk.type;

import javax.annotation.Nullable;
import lombok.Data;

@Data
public class ListValue<T> {

  @Nullable
  private final String id;

  private final T value;
}
