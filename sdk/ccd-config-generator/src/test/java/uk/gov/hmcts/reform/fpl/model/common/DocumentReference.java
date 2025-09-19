package uk.gov.hmcts.reform.fpl.model.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;
import uk.gov.hmcts.ccd.sdk.api.ComplexType;

@Data
@Builder(toBuilder = true)
@ComplexType(name = "Document", generate = false)
public class DocumentReference {
    @JsonProperty("document_url")
    private final String url;
    @JsonProperty("document_filename")
    private final String filename;
    @JsonProperty("document_binary_url")
    private final String binaryUrl;

}
