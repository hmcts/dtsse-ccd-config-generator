package uk.gov.hmcts.ccd.sdk.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.DocmosisConnection;
import uk.gov.hmcts.ccd.sdk.conversion.docmosis.HttpDocmosisRenderService;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.Fixtures;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.PrintRequirements;

/**
 * Opt-in: converts the real office fixtures through a real Docmosis and checks the output still
 * meets the print requirements. Runs only when {@code DOCMOSIS_CONVERT_ENDPOINT} (the
 * {@code /rs/convert} URL of a non-production Docmosis) and {@code DOCMOSIS_ACCESS_KEY} are set.
 */
@EnabledIfEnvironmentVariable(named = "DOCMOSIS_CONVERT_ENDPOINT", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DOCMOSIS_ACCESS_KEY", matches = ".+")
class RealDocmosisContractTest {

  @TempDir
  Path dir;

  @ParameterizedTest(name = "{0}")
  @CsvSource({
      "wordDocument.doc, application/msword",
      "wordDocument2.docx, application/vnd.openxmlformats-officedocument.wordprocessingml.document",
      "TestExcelConversion.xlsx, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      "sample.csv, text/csv",
      "sample.txt, text/plain",
      "sample.rtf, application/rtf"})
  void realDocmosisOutputIsMadePrintReady(String fileName, String mediaType) throws Exception {
    Path source = switch (fileName) {
      case "sample.csv" -> Fixtures.text(dir, fileName, "date,amount\n2026-01-01,450.00\n");
      case "sample.txt" -> Fixtures.text(dir, fileName, "Supporting statement\n");
      case "sample.rtf" -> Fixtures.text(dir, fileName, "{\\rtf1\\ansi Supporting statement}");
      default -> Fixtures.real(dir, fileName);
    };
    DocumentConverter converter = DocumentConverter.builder()
        .docmosis(new HttpDocmosisRenderService(DocmosisConnection.withDefaults(
            URI.create(System.getenv("DOCMOSIS_CONVERT_ENDPOINT")),
            System.getenv("DOCMOSIS_ACCESS_KEY")), dir.resolve("docmosis")))
        .tempDirectory(dir)
        .build();

    try (ConvertedPdf pdf = converter.toPrintReadyPdf(
        ConversionSource.of(source, fileName, mediaType), PrintOptions.bulkPrint())) {
      assertThat(pdf.pageCount()).isPositive();
      PrintRequirements.assertPrintReady(pdf.file(), PrintOptions.bulkPrint());
    }
  }
}
