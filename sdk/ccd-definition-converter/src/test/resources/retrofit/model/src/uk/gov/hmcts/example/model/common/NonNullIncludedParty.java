package uk.gov.hmcts.example.model.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * The same shape but with a VALUED {@code @JsonInclude(JsonInclude.Include.NON_NULL)}: nulls are
 * already suppressed class-wide, so a synthesised member needs no per-field annotation. Pins that
 * the fix keys on the marker form alone and does not annotate every synthesis site.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NonNullIncludedParty {

  private String existingName;
}
