package uk.gov.hmcts.ccd.sdk.bundling.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A cover page rendered from a Docmosis template and placed first in the bundle, before the
 * title and index pages, exactly as the stitching microservice places a supplied cover page.
 * The template lives on the shared Docmosis instance; the data is the template's JSON payload.
 *
 * @param templateName the Docmosis template name, for example {@code FL-FRM-GOR-ENG-12345.docx}
 * @param data the template data, serialised to JSON as the {@code data} part of the render call
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoverPage(
    @JsonProperty("templateName") String templateName,
    @JsonProperty("data") Map<String, Object> data) {

  public CoverPage {
    Validate.requireNonBlank(templateName, "CoverPage.templateName");
    data = Collections.unmodifiableMap(new LinkedHashMap<>(
        data == null ? Map.of() : data));
  }
}
