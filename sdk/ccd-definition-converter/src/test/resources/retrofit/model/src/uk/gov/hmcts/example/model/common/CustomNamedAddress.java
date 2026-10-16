package uk.gov.hmcts.example.model.common;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

/**
 * probate's shape: a team's OWN {@code @JsonNaming} strategy class, which is arbitrary Java the
 * converter cannot evaluate without running it. Its members therefore never resolve through the
 * strategy and never get pinned — guessing would write a WRONG {@code @JsonProperty} and silently
 * change the CCD field id, so the affected rows keep their verbatim passthrough instead.
 */
@Data
@JsonNaming(RegularCaseNamingStrategy.class)
public class CustomNamedAddress {

  private String addressLine1;
}
