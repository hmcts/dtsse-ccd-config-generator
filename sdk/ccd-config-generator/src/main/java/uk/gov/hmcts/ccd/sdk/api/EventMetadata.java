package uk.gov.hmcts.ccd.sdk.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventMetadata {

  private String summary;

  private String description;
}
