package uk.gov.hmcts.divorce.bundling.model;

import com.fasterxml.jackson.annotation.JsonFormat;
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

/**
 * This service's own bounded bundle model: the stitched document plus the generation report the
 * SDK returned. The SDK deliberately has no case-data shape of its own.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CaseBundle {

    @CCD(label = "Id")
    private String id;

    @CCD(label = "Title")
    private String title;

    @CCD(label = "File name")
    private String fileName;

    @CCD(label = "Stitched document")
    private Document stitchedDocument;

    @CCD(label = "Page count")
    private Integer pageCount;

    @CCD(label = "Stitch status")
    private String stitchStatus;

    @CCD(label = "Documents", typeOverride = FieldType.Collection, typeParameterOverride = "CaseBundleDocument")
    private List<ListValue<CaseBundleDocument>> documents;

    @CCD(label = "Generated")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS")
    private LocalDateTime dateAndTime;
}
