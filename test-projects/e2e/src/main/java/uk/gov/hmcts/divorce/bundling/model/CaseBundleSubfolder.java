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
 * A nested folder inside a {@link CaseBundleFolder}. CCD complex types cannot be recursive, so —
 * exactly like the em-ccd-orchestrator bundle definitions every consumer imports today — the
 * folder tree is modelled to a fixed depth; deeper nesting in the SDK's {@code CcdBundle} output
 * is dropped on deserialisation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CaseBundleSubfolder {

    @CCD(label = "Name")
    private String name;

    @CCD(
        label = "Documents",
        typeOverride = FieldType.Collection,
        typeParameterOverride = "CaseBundleDocument"
    )
    private List<ListValue<CaseBundleDocument>> documents;

    @CCD(label = "Sort index")
    private Integer sortIndex;
}
