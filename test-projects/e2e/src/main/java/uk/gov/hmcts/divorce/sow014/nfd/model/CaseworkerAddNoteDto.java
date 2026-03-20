package uk.gov.hmcts.divorce.sow014.nfd.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseworkerAddNoteDto {
    private String note;
}
