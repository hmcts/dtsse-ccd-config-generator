package uk.gov.hmcts.divorce.bundling.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;

/** One document entry in a {@link CaseBundle}: where the SDK placed it in the stitched PDF. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseBundleDocument {

    @CCD(label = "Name")
    private String name;

    @CCD(label = "Start page")
    private Integer startPage;

    @CCD(label = "Page count")
    private Integer pageCount;
}
