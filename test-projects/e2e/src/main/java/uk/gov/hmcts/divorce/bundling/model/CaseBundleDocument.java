package uk.gov.hmcts.divorce.bundling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * One document entry in a {@link CaseBundle}: this service's bounded mirror of the
 * document-bundling SDK's {@code CcdBundleDocument} (both sides tolerate unknown properties, so
 * the SDK output deserialises straight in).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CaseBundleDocument {

    @CCD(label = "Name")
    private String name;

    @CCD(label = "Description")
    private String description;

    @CCD(label = "Sort index")
    private Integer sortIndex;

    @CCD(label = "Source document")
    private Document sourceDocument;
}
