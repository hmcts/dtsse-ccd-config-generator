package uk.gov.hmcts.divorce.bundling;

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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Stub for the shared Docmosis render service, following the {@code RefDataStubController}
 * pattern: the e2e app hosts its own downstream dependencies so office conversion is exercised
 * without the shared per-environment Docmosis instance. Mimics the {@code /rs/convert} endpoint
 * the document-bundling SDK's {@code HttpDocmosisRenderService} speaks to (multipart
 * accessKey/outputName/file) and answers with a small valid PDF whose text names the source, so
 * cftlib tests can assert the conversion round trip semantically.
 */
@RestController
@Slf4j
public class DocmosisStubController {

    public static final String CONVERTED_MARKER = "Stubbed Docmosis conversion of";

    /**
     * Every source filename this stub has converted, in order — the in-process record cftlib
     * tests assert against, instead of grepping the append-only HTTP traffic log whose earlier
     * runs would satisfy any grep.
     */
    private final List<String> convertedSources = new CopyOnWriteArrayList<>();

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
        return pdf(List.of(
            CONVERTED_MARKER + " " + file.getOriginalFilename(),
            "Source content type: " + file.getContentType(),
            "Source size: " + file.getSize() + " bytes"
        ));
    }


    private ResponseEntity<byte[]> pdf(List<String> lines) throws IOException {
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
            return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .body(out.toByteArray());
        }
    }
}
