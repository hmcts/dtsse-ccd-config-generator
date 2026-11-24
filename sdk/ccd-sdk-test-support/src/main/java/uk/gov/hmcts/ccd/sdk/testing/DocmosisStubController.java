package uk.gov.hmcts.ccd.sdk.testing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stands in for Docmosis's {@code /rs/convert} in local runs and tests, so office documents can
 * be converted without the shared service. Every conversion returns a one-page PDF naming the
 * file, and the names are recorded.
 *
 * <p>Off unless {@code ccd.doc-toolkit.docmosis-stub.enabled=true}. Point the toolkit at it:
 *
 * <pre>
 * ccd.doc-toolkit.docmosis-stub.enabled: true
 * ccd.doc-toolkit.docmosis.convert-endpoint: http://localhost:${server.port}/stub/rs/convert
 * ccd.doc-toolkit.docmosis.access-key: stub
 * </pre>
 *
 * <p>An application that does not scan {@code uk.gov.hmcts.ccd.sdk} adds it with
 * {@code @Import(DocmosisStubController.class)}.
 */
@Slf4j
@RestController
@ConditionalOnProperty(prefix = "ccd.doc-toolkit.docmosis-stub", name = "enabled",
    havingValue = "true")
public class DocmosisStubController {

  /** The text on every page the stub returns, followed by the file name. */
  public static final String CONVERTED_MARKER = "Stubbed Docmosis conversion of";

  private final List<String> convertedSources = new CopyOnWriteArrayList<>();

  /** The names of the files converted so far, in order. */
  public List<String> convertedSources() {
    return List.copyOf(convertedSources);
  }

  @PostMapping(value = "/stub/rs/convert", produces = MediaType.APPLICATION_PDF_VALUE)
  public ResponseEntity<byte[]> convert(
      @RequestParam("accessKey") String accessKey,
      @RequestParam("outputName") String outputName,
      @RequestParam("file") MultipartFile file) throws IOException {
    log.info("Docmosis stub converting {} ({} bytes, {}) to {}",
        file.getOriginalFilename(), file.getSize(), file.getContentType(), outputName);
    convertedSources.add(file.getOriginalFilename());
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .body(pdf(List.of(
            CONVERTED_MARKER + " " + file.getOriginalFilename(),
            "Source content type: " + file.getContentType(),
            "Source size: " + file.getSize() + " bytes")));
  }

  private static byte[] pdf(List<String> lines) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(72, 700);
        content.setLeading(16);
        for (String line : lines) {
          content.showText(line);
          content.newLine();
        }
        content.endText();
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    }
  }
}
