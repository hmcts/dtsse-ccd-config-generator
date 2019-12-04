package uk.gov.hmcts.reform.fpl;

import ccd.sdk.types.CCDConfig;
import ccd.sdk.types.ConfigBuilder;
import ccd.sdk.types.FieldType;

public class FPLConfig implements CCDConfig {
    @Override
    public void configure(ConfigBuilder builder) {
        builder.caseField("standardDirectionsDocument", FieldType.Document)
                .label("standardDirectionsLabel", "Upload standard directions and other relevant documents, for example the C6 Notice of Proceedings or C9 statement of service.")
                .label("standardDirectionsTitle", "## 1. Standard directions");
        builder.caseField("schedule", FieldType.Schedule)
                .label("allPartiesLabelCMO", "## For all parties")
                .label("localAuthorityDirectionsLabelCMO", "## For the local authority")
                .label("cafcassDirectionsLabelCMO", "## For Cafcass")
                .label("courtDirectionsLabelCMO", "## For the court");
        builder.caseField("recitals", FieldType.FixedList)
                .label("orderBasisLabel", "## Basis of order")
                .label("addRecitalLabel", "## Add recital");
        builder.caseField("respondentsDropdownLabelCMO", FieldType.TextArea)
                .label("respondentsDirectionLabelCMO", "## For the parents or respondents");
        builder.caseField("otherPartiesDropdownLabelCMO", FieldType.TextArea)
                .label("otherPartiesDirectionLabelCMO", "## For other parties");
    }
}
