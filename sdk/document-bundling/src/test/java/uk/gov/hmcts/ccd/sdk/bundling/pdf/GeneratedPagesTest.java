package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.doc;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.textPdf;
import static uk.gov.hmcts.ccd.sdk.bundling.pdf.TestPdfs.tocOnly;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.bundling.api.MediaPlaceholder;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;

/**
 * The new deterministic generated pages: the bundle title page with bounded text, and the media
 * link page with its metadata lines and clickable absolute access link. Both participate in the
 * table of contents, bookmarks and pagination like any document (the empty-section page is
 * covered in {@link PdfBundleAssemblerCoversheetsTest}).
 */
class GeneratedPagesTest {

  @TempDir
  Path tmp;

  private final PdfBundleAssembler assembler = new PdfBundleAssembler();
  private Path workDir;

  @BeforeEach
  void setUp() {
    workDir = tmp.resolve("work");
  }

  @Test
  void titlePageRendersLongTitlesWithinThePage() throws IOException {
    String longTitle = "In the matter of " + "a very long case name ".repeat(15).trim();
    AssemblyRequest request = new AssemblyRequest(longTitle, "stitched.pdf",
        Optional.of("Hearing 12 June 2026"), tocOnly(), true, Optional.empty(),
        TestPdfs.nodes(doc("Bundle Doc 1", textPdf(tmp, "one", 1))));

    AssemblyResult result = assembler.assemble(request, workDir);

    String titlePage = Pdfs.pageText(result.outputPdf(), 1).replaceAll("\\s+", " ").trim();
    assertThat(titlePage).contains(longTitle).contains("Hearing 12 June 2026");
    assertThat(titlePage).doesNotContain("Index Page");
  }

  @Test
  void mediaLinkPageShowsMetadataAndClickableAbsoluteLink() throws IOException {
    MediaPlaceholder placeholder = MediaPlaceholder.builder()
        .accessUrl("https://media.example.org/recordings/day-2")
        .duration(Duration.ofMinutes(65))
        .note("Playback requires case access")
        .build();
    AssemblyItem recording = new AssemblyItem("Hearing recording, day 2",
        Optional.of(LocalDate.of(2024, 1, 12)), false,
        new MediaLinkPage("audio/mpeg", placeholder));

    AssemblyResult result = assembler.assemble(TestPdfs.request(tocOnly(),
        doc("Bundle Doc 1", textPdf(tmp, "one", 1)), recording), workDir);

    // Pages: contents, document page, media link page.
    assertThat(result.totalPages()).isEqualTo(3);
    String mediaPage = Pdfs.pageText(result.outputPdf(), 3);
    assertThat(mediaPage)
        .contains("Hearing recording, day 2")
        .contains("Date: 12 Jan 2024")
        .contains("Media type: audio/mpeg")
        .contains("Duration: 1h 5m 0s")
        .contains("Playback requires case access")
        .contains("https://media.example.org/recordings/day-2");
    assertThat(Pdfs.uriLinks(result.outputPdf(), 3))
        .containsExactly("https://media.example.org/recordings/day-2");
  }

  @Test
  void mediaLinkPageWithoutOptionalFieldsOmitsTheirLines() throws IOException {
    MediaPlaceholder placeholder = MediaPlaceholder.builder()
        .accessUrl("https://media.example.org/short")
        .build();
    AssemblyItem recording = new AssemblyItem("Recording", Optional.empty(), false,
        new MediaLinkPage("video/mp4", placeholder));

    AssemblyResult result = assembler.assemble(
        TestPdfs.request(TestPdfs.plain(), recording), workDir);

    String mediaPage = Pdfs.pageText(result.outputPdf(), 1);
    assertThat(mediaPage)
        .contains("Media type: video/mp4")
        .doesNotContain("Date:")
        .doesNotContain("Duration:");
  }

  @Test
  void mediaLinkPageParticipatesInContentsBookmarksAndPagination() throws IOException {
    MediaPlaceholder placeholder = MediaPlaceholder.builder()
        .accessUrl("https://media.example.org/recordings/day-2")
        .build();
    AssemblyItem recording = new AssemblyItem("Hearing recording",
        Optional.of(LocalDate.of(2024, 1, 12)), false,
        new MediaLinkPage("audio/mpeg", placeholder));
    var presentation = tocOnly().withPageNumbers(PageNumbers.BOTTOM_CENTRE_N_OF_M);

    AssemblyResult result = assembler.assemble(TestPdfs.request(presentation,
        doc("Bundle Doc 1", textPdf(tmp, "one", 1)), recording), workDir);

    assertThat(Pdfs.pageText(result.outputPdf(), 1)).contains("Hearing recording");
    assertThat(Pdfs.internalLinkTargets(result.outputPdf(), 1)).containsExactly(2, 3);
    assertThat(Pdfs.outline(result.outputPdf())).contains("  Hearing recording -> 3");
    assertThat(Pdfs.pageText(result.outputPdf(), 3)).contains("3 of 3");
    assertThat(result.items()).contains(new AssembledItem("Hearing recording", 3, 1));
  }
}
