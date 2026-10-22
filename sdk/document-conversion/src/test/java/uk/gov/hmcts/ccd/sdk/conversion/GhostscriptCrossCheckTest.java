package uk.gov.hmcts.ccd.sdk.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.Fixtures;
import uk.gov.hmcts.ccd.sdk.conversion.testsupport.PrintRequirements;

/**
 * The other print tests read the output back with PDFBox, the library that wrote it. This one
 * has Ghostscript, an independent interpreter close to what print RIPs run, open and render the
 * output: it must parse without complaint and agree that the margins are blank. Skipped where
 * {@code gs} is not installed.
 */
@EnabledIf("ghostscriptInstalled")
class GhostscriptCrossCheckTest {

  @TempDir
  Path dir;

  static boolean ghostscriptInstalled() {
    try {
      return new ProcessBuilder("gs", "--version").start().waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  @Test
  void ghostscriptRendersTheOutputWithBlankMargins() throws Exception {
    DocumentConverter converter = DocumentConverter.builder().tempDirectory(dir).build();
    List<ConversionSource> sources = List.of(
        ConversionSource.of(Fixtures.mixedLayoutPdf(dir), "mixed-layout.pdf", "application/pdf"),
        ConversionSource.of(Fixtures.transparencyPdf(dir), "transparency.pdf",
            "application/pdf"),
        ConversionSource.of(Fixtures.largePhoto(dir), "large-photo.jpg", "image/jpeg"));
    for (ConversionSource source : sources) {
      try (ConvertedPdf pdf = converter.toPrintReadyPdf(source, PrintOptions.bulkPrint())) {
        Path out = Files.createDirectories(dir.resolve("gs-" + source.fileName()));
        Process gs = new ProcessBuilder("gs", "-q", "-dNOPAUSE", "-dBATCH", "-dSAFER",
            "-dPDFSTOPONERROR", "-sDEVICE=pnggray", "-r50",
            "-sOutputFile=" + out.resolve("page-%d.png"), pdf.file().toString())
            .redirectErrorStream(true).start();
        String log = new String(gs.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(gs.waitFor()).as("gs exit for %s: %s", source.fileName(), log).isZero();
        assertThat(log).as("gs complaints for %s", source.fileName()).isBlank();

        List<File> pages;
        try (Stream<Path> files = Files.list(out)) {
          pages = files.sorted().map(Path::toFile).toList();
        }
        assertThat(pages).hasSize(pdf.pageCount());
        for (File page : pages) {
          BufferedImage image = ImageIO.read(page);
          PrintRequirements.assertMarginBlank(image, 20, source.fileName() + " " + page.getName());
        }
      }
    }
  }
}
