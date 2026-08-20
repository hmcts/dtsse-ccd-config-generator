package uk.gov.hmcts.divorce.bundling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.FieldType;
import uk.gov.hmcts.ccd.sdk.type.ListValue;

/**
 * One top-level folder in a {@link CaseBundle}: this service's bounded mirror of the
 * document-bundling SDK's {@code CcdBundleFolder}, with the recursion CCD cannot express
 * flattened to one nested {@link CaseBundleSubfolder} level.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CaseBundleFolder {

    @CCD(label = "Name")
    private String name;

    @CCD(
        label = "Documents",
        typeOverride = FieldType.Collection,
        typeParameterOverride = "CaseBundleDocument"
    )
    private List<ListValue<CaseBundleDocument>> documents;

    @CCD(
        label = "Folders",
        typeOverride = FieldType.Collection,
        typeParameterOverride = "CaseBundleSubfolder"
    )
    private List<ListValue<CaseBundleSubfolder>> folders;

    @CCD(label = "Sort index")
    private Integer sortIndex;
}
