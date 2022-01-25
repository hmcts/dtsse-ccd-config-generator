package uk.gov.hmcts.ccd.sdk.type;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class KeyValue {

  private String key;

  private String value;
}
