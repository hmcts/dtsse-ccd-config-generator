package uk.gov.hmcts.divorce.bundling.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.ccd.sdk.type.FieldType;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.ccd.sdk.type.YesOrNo;

/**
 * This service's bundle case model: the shape its {@code caseBundles} field holds, mirroring the
 * platform's {@code CcdBundleDTO} the way existing consumers (for example sptribs-case-api's
 * {@code Bundle}) already do. The document-bundling SDK's {@code CcdBundle} render output is
 * JSON-compatible with this type — both sides ignore unknown properties — so
 * {@code result.output()} converts straight in. CCD complex types cannot be recursive, so the
 * folder tree is bounded ({@link CaseBundleFolder} holding {@link CaseBundleSubfolder}), exactly
 * as the em-ccd-orchestrator bundle definitions bound it today.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CaseBundle {

    @CCD(label = "Id")
    private String id;

    @CCD(label = "Title")
    private String title;

    @CCD(label = "Description")
    private String description;

    @CCD(label = "Stitched document")
    private Document stitchedDocument;

    @CCD(
        label = "Documents",
        typeOverride = FieldType.Collection,
        typeParameterOverride = "CaseBundleDocument"
    )
    private List<ListValue<CaseBundleDocument>> documents;

    @CCD(
        label = "Folders",
        typeOverride = FieldType.Collection,
        typeParameterOverride = "CaseBundleFolder"
    )
    private List<ListValue<CaseBundleFolder>> folders;

    @CCD(label = "File name")
    private String fileName;

    @CCD(label = "Has table of contents")
    private YesOrNo hasTableOfContents;

    @CCD(label = "Has coversheets")
    private YesOrNo hasCoversheets;

    @CCD(label = "Has folder coversheets")
    private YesOrNo hasFolderCoversheets;

    @CCD(label = "Stitch status")
    private String stitchStatus;

    @CCD(label = "Pagination style")
    private String paginationStyle;

    @CCD(label = "Page number format")
    private String pageNumberFormat;

    @CCD(label = "Stitching failure message")
    private String stitchingFailureMessage;

    @CCD(label = "Generated")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime dateAndTime;
}
