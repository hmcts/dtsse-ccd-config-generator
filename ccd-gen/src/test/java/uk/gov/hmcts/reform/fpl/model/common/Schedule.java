package uk.gov.hmcts.reform.fpl.model.common;

import uk.gov.hmcts.ccd.sdk.types.CaseField;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class Schedule {
    public final String allocation;
    public final String application;
    public final String todaysHearing;
    public final String childrensCurrentArrangement;
    public final String timetableForProceedings;
    @CaseField(ignore = true)
    public final String timetableForTheChildren;
    public final String alternativeCarers;
    public final String threshold;
    public final String keyIssues;
    public final String partiesPositions;
}
