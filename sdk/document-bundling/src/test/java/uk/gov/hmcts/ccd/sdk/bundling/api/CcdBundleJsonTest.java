package uk.gov.hmcts.ccd.sdk.bundling.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.ccd.sdk.type.YesOrNo;

/**
 * Pins the output format's JSON contract: it must stay wire-compatible with
 * em-ccd-orchestrator's CcdBundleDTO and with the bundle models consumer services hold in their
 * caseBundles fields (e.g. sptribs-case-api's Bundle).
 */
class CcdBundleJsonTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void serialisesTheCcdBundleDtoWireShape() throws Exception {
    CcdBundle bundle = CcdBundle.builder()
        .id("bundle-1")
        .title("Hearing bundle")
        .fileName("hearing-bundle.pdf")
        .stitchedDocument(Document.builder()
            .url("http://dm-store/documents/abc")
            .binaryUrl("http://dm-store/documents/abc/binary")
            .filename("hearing-bundle.pdf")
            .build())
        .folders(List.of(new ListValue<>("1", CcdBundleFolder.builder()
            .name("Applications")
            .sortIndex(0)
            .documents(List.of(new ListValue<>("1", CcdBundleDocument.builder()
                .name("Application form")
                .sortIndex(0)
                .sourceDocument(Document.builder()
                    .url("http://dm-store/documents/src-1")
                    .binaryUrl("http://dm-store/documents/src-1/binary")
                    .filename("application.pdf")
                    .build())
                .build())))
            .build())))
        .hasTableOfContents(YesOrNo.YES)
        .hasCoversheets(YesOrNo.NO)
        .hasFolderCoversheets(YesOrNo.YES)
        .paginationStyle("bottomCenter")
        .pageNumberFormat("numberOfPages")
        .stitchStatus("DONE")
        .build();

    JsonNode json = mapper.valueToTree(bundle);

    assertThat(json.get("title").asText()).isEqualTo("Hearing bundle");
    assertThat(json.get("fileName").asText()).isEqualTo("hearing-bundle.pdf");
    assertThat(json.get("stitchedDocument").get("document_url").asText())
        .isEqualTo("http://dm-store/documents/abc");
    assertThat(json.get("stitchedDocument").get("document_binary_url").asText())
        .isEqualTo("http://dm-store/documents/abc/binary");
    assertThat(json.get("stitchedDocument").get("document_filename").asText())
        .isEqualTo("hearing-bundle.pdf");
    JsonNode folder = json.get("folders").get(0).get("value");
    assertThat(folder.get("name").asText()).isEqualTo("Applications");
    assertThat(folder.get("sortIndex").asInt()).isZero();
    JsonNode document = folder.get("documents").get(0).get("value");
    assertThat(document.get("name").asText()).isEqualTo("Application form");
    assertThat(document.get("sourceDocument").get("document_url").asText())
        .isEqualTo("http://dm-store/documents/src-1");
    assertThat(json.get("hasTableOfContents").asText()).isEqualTo("Yes");
    assertThat(json.get("hasCoversheets").asText()).isEqualTo("No");
    assertThat(json.get("paginationStyle").asText()).isEqualTo("bottomCenter");
    assertThat(json.get("pageNumberFormat").asText()).isEqualTo("numberOfPages");
    assertThat(json.get("stitchStatus").asText()).isEqualTo("DONE");
  }

  @Test
  void toleratesUnknownPropertiesFromConsumerBundleModels() throws Exception {
    // Properties other bundle models carry that the SDK does not: sptribs' documentImage and
    // dateAndTime formats, the orchestrator's coverpageTemplateData, and anything added later.
    String json = """
        {
          "title": "Hearing bundle",
          "stitchStatus": "DONE",
          "documentImage": {"docmosisAssetId": "hmcts.png"},
          "someFutureProperty": 42
        }
        """;

    CcdBundle bundle = mapper.readValue(json, CcdBundle.class);

    assertThat(bundle.getTitle()).isEqualTo("Hearing bundle");
    assertThat(bundle.getStitchStatus()).isEqualTo("DONE");
  }
}
