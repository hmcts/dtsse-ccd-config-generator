package uk.gov.hmcts.reform.fpl.model.common;

import ccd.sdk.types.ComplexType;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder(toBuilder = true)
@ComplexType(name = "Document")
public class DocumentReference {
    @JsonProperty("document_url")
    private final String url;
    @JsonProperty("document_filename")
    private final String filename;
    @JsonProperty("document_binary_url")
    private final String binaryUrl;
}
