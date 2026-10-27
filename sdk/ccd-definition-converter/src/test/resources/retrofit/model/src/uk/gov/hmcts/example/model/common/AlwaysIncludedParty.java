package uk.gov.hmcts.example.model.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * A complex type carrying a MARKER {@code @JsonInclude} — no value, which Jackson reads as
 * {@code ALWAYS} (sscs's {@code Appellant}/{@code Appointee}/{@code Representative} shape). The
 * team's code never populates a definition-only member, so synthesising one plainly would add
 * {@code "<id>": null} to every serialised instance — a breaking wire-format change for a published
 * library. Each synthesised field must therefore carry its own
 * {@code @JsonInclude(JsonInclude.Include.NON_NULL)}.
 */
@Data
@JsonInclude
public class AlwaysIncludedParty {

  private String existingName;
}
