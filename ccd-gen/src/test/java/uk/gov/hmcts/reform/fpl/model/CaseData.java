package uk.gov.hmcts.reform.fpl.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.util.List;

@Data
@Builder(toBuilder = true)
@AllArgsConstructor
public class CaseData {
    @NotBlank(message = "Enter a case name")
    private final String caseName;
    private final String gatekeeperEmail;
    private final String caseLocalAuthority;
    private final List<Solicitor> solicitors;
}
