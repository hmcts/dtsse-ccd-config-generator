package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Semantic PDF extraction for assertions: page counts, per-page text, the outline tree, and link
 * annotations. Never byte-for-byte.
 */
final class Pdfs {

  private Pdfs() {
  }

  static int pageCount(Path pdf) {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      return document.getNumberOfPages();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String allText(Path pdf) {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      return new PDFTextStripper().getText(document);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Extracts the text of one 1-based page. */
  static String pageText(Path pdf, int page) {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setStartPage(page);
      stripper.setEndPage(page);
      return stripper.getText(document);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The whole text with all whitespace runs collapsed to single spaces. */
  static String normalisedText(Path pdf) {
    return allText(pdf).replaceAll("\\s+", " ").trim();
  }

  /**
   * Flattens the outline tree into entries of the form
   * {@code "<2 spaces per depth><title> -> <1-based page>"}; entries without a resolvable
   * destination have no {@code " -> N"} suffix.
   */
  static List<String> outline(Path pdf) {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      List<String> entries = new ArrayList<>();
      PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
      if (outline != null) {
        for (PDOutlineItem child = outline.getFirstChild(); child != null;
            child = child.getNextSibling()) {
          appendOutline(document, child, 0, entries);
        }
      }
      return entries;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The 1-based destination pages of all internal links on a 1-based page, in order. */
  static List<Integer> internalLinkTargets(Path pdf, int page) {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      List<Integer> targets = new ArrayList<>();
      PDPage pdPage = document.getPage(page - 1);
      for (var annotation : pdPage.getAnnotations()) {
        if (annotation instanceof PDAnnotationLink link) {
          int target = resolveLinkPage(link);
          if (target >= 0) {
            targets.add(target + 1);
          }
        }
      }
      return targets;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The URIs of all external links on a 1-based page, in order. */
  static List<String> uriLinks(Path pdf, int page) {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      List<String> uris = new ArrayList<>();
      PDPage pdPage = document.getPage(page - 1);
      for (var annotation : pdPage.getAnnotations()) {
        if (annotation instanceof PDAnnotationLink link
            && link.getAction() instanceof PDActionURI uriAction) {
          uris.add(uriAction.getURI());
        }
      }
      return uris;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Whether the 1-based page's resources contain any XObject (for example an overlay image). */
  static boolean hasXobject(Path pdf, int page) {
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      PDPage pdPage = document.getPage(page - 1);
      return pdPage.getResources() != null
          && pdPage.getResources().getXObjectNames().iterator().hasNext();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static int count(String text, String find) {
    int index = 0;
    int occurrences = 0;
    while ((index = text.indexOf(find, index)) != -1) {
      index += find.length();
      occurrences++;
    }
    return occurrences;
  }

  private static void appendOutline(PDDocument document, PDOutlineItem item, int depth,
      List<String> entries) throws IOException {
    StringBuilder entry = new StringBuilder("  ".repeat(depth)).append(item.getTitle());
    int page = resolveItemPage(item);
    if (page >= 0) {
      entry.append(" -> ").append(page + 1);
    }
    entries.add(entry.toString());
    for (PDOutlineItem child = item.getFirstChild(); child != null;
        child = child.getNextSibling()) {
      appendOutline(document, child, depth + 1, entries);
    }
  }

  private static int resolveItemPage(PDOutlineItem item) throws IOException {
    PDDestination destination = item.getDestination();
    if (destination == null && item.getAction() instanceof PDActionGoTo goTo) {
      destination = goTo.getDestination();
    }
    if (destination instanceof PDPageDestination pageDestination) {
      return pageDestination.retrievePageNumber();
    }
    return -1;
  }

  private static int resolveLinkPage(PDAnnotationLink link) throws IOException {
    PDDestination destination = link.getDestination();
    if (destination == null && link.getAction() instanceof PDActionGoTo goTo) {
      destination = goTo.getDestination();
    }
    if (destination instanceof PDPageDestination pageDestination) {
      return pageDestination.retrievePageNumber();
    }
    return -1;
  }
}
