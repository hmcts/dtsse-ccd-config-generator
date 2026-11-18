package uk.gov.hmcts.divorce.divorcecase.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uk.gov.hmcts.ccd.sdk.api.CCD;
import uk.gov.hmcts.ccd.sdk.type.Document;

import java.time.LocalDate;

/**
 * A service-owned type that uses a {@code @JsonCreator} constructor and wraps an SDK
 * {@link Document}, matching the shape of service document types.
 */
@Data
@NoArgsConstructor
@Builder
public class RoundTripDocument {

    @CCD(label = "Document link")
    private Document documentLink;

    @CCD(label = "Date added")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate documentDateAdded;

    @CCD(label = "Comment")
    private String documentComment;

    @CCD(label = "File name")
    private String documentFileName;

    @JsonCreator
    public RoundTripDocument(@JsonProperty("documentLink") Document documentLink,
                             @JsonProperty("documentDateAdded") LocalDate documentDateAdded,
                             @JsonProperty("documentComment") String documentComment,
                             @JsonProperty("documentFileName") String documentFileName) {
        this.documentLink = documentLink;
        this.documentDateAdded = documentDateAdded;
        this.documentComment = documentComment;
        this.documentFileName = documentFileName;
    }
}
