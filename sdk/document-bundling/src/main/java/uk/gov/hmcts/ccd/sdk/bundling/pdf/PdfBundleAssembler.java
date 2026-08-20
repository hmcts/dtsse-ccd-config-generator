package uk.gov.hmcts.ccd.sdk.bundling.pdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.io.RandomAccess;
import org.apache.pdfbox.io.RandomAccessStreamCache;
import org.apache.pdfbox.io.RandomAccessStreamCache.StreamCacheCreateFunction;
import org.apache.pdfbox.io.ScratchFile;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleWarning;
import uk.gov.hmcts.ccd.sdk.bundling.api.ConfidentialMarking;
import uk.gov.hmcts.ccd.sdk.bundling.api.PageNumbers;

/**
 * Assembles one bundle PDF from an {@link AssemblyRequest}, ported from
 * {@code em-stitching-api}'s {@code PDFMerger}: optional generated title page, clickable
 * multi-page table of contents, folder and document cover sheets with "Back to index" links,
 * preserved source outlines nested beneath each document's bookmark, pagination from the approved
 * {@link PageNumbers} presets, approved confidential header markings, approved watermark presets,
 * and generated media link and empty-section pages.
 *
 * <p><b>Memory.</b> One assembly uses one bounded PDFBox scratch buffer
 * ({@link MemoryUsageSetting#setupMixed(long)}, {@value #MAX_MAIN_MEMORY_BYTES} bytes of heap)
 * whose spill files live in the caller's working directory. The single budget is <em>shared</em>
 * by the merged document and every loaded source — never a fresh budget per document, so the
 * assembly-wide heap ceiling does not grow with the number of documents — and never global JVM
 * state: the current service's {@code System.setProperty("pdfbox.fontcache", ...)} is
 * deliberately not replicated, and is unnecessary here because assembly only ever uses the
 * standard 14 PDF fonts (a fresh set per assembly, as {@code PDType1Font} instances are not
 * safe to share across concurrent assemblies) and never rasterises pages, so PDFBox's
 * system-font scanning is never triggered.
 *
 * <p><b>Sources.</b> Source files are read, never written. Page dimensions and rotations are
 * preserved by the merge; watermarking happens on an intermediate file under the working
 * directory, which is removed before the assembler returns — on success and on failure. A failed
 * assembly also deletes the output path, so a stale bundle from an earlier run can never survive
 * at {@code workDir/outputFileName}.
 *
 * <p><b>Generated text placement.</b> Stamps and generated text are positioned against each
 * page's crop box, so the confidential header and page numbers stay inside the visible area of
 * cropped (typically scanned) evidence pages; the current service positions against the media
 * box and can stamp outside the visible area.
 *
 * <p><b>Titles.</b> A title whose characters are all outside WinAnsi (for example fully
 * Cyrillic) cannot be drawn by the ported text pipeline and would leave an unlabelled row in a
 * court index; the assembler draws the deterministic fallback {@code "Document <n>"} (render
 * order) instead and emits {@link #WARNING_TITLE_NOT_RENDERABLE}. Partially non-WinAnsi titles
 * are drawn with the unsupported characters dropped, exactly as the current service does; the
 * bookmark always keeps the full title.
 *
 * <p><b>The structure-tree fallback, scrutinised.</b> The current service retries a failed
 * {@code appendDocument} after installing an empty {@link PDStructureTreeRoot} on the source:
 * PDFBox's merge can throw {@link IndexOutOfBoundsException} for documents whose tagged-PDF
 * structure tree is malformed or references pages inconsistently. That fallback is kept here —
 * failing an entire court bundle for a broken accessibility tree in one source is the worse
 * outcome, and accessibility conformance is explicitly outside this library's scope — but it is
 * no longer silent, and it is made safe: any pages the failed first attempt already appended are
 * removed before the retry (the service could duplicate them), and the retry emits a
 * {@link BundleWarning} with code {@link #WARNING_STRUCTURE_TREE_REPLACED} naming the document,
 * because discarding a structure tree conceals real accessibility damage in that document's
 * merged pages and the consumer must be able to see it happened.
 */
public final class PdfBundleAssembler {

  /** Warning code: a source's malformed structure tree was replaced to complete the merge. */
  public static final String WARNING_STRUCTURE_TREE_REPLACED = "STRUCTURE_TREE_REPLACED";

  /** Warning code: a visible empty-section placeholder page was included. */
  public static final String WARNING_EMPTY_SECTION_PAGE = "EMPTY_SECTION_PAGE_INCLUDED";

  /** Warning code: a source outline was partially dropped (cycle or excessive nesting). */
  public static final String WARNING_OUTLINE_TRUNCATED = "OUTLINE_TRUNCATED";

  /** Warning code: a title has no drawable characters; the fallback row text was used. */
  public static final String WARNING_TITLE_NOT_RENDERABLE = "TITLE_NOT_RENDERABLE";

  /** The bookmark title of the generated bundle title page. */
  public static final String TITLE_PAGE_BOOKMARK = "Title Page";

  /** The heap budget shared by one whole assembly's stream caches. */
  static final long MAX_MAIN_MEMORY_BYTES = 64L * 1024 * 1024;

  /**
   * Assembles the bundle described by {@code request}.
   *
   * <p>All scratch output — PDFBox stream-cache spill files, watermark intermediates and the
   * finished PDF itself — is created under {@code workDir} only, and everything except the
   * finished PDF is removed before returning. On failure the output path is deleted too.
   *
   * @param request the assembly request
   * @param workDir the job-scoped working directory; created if absent
   * @return the produced PDF with its page map and warnings
   * @throws IOException if a source cannot be processed; the message names the document
   */
  public AssemblyResult assemble(AssemblyRequest request, Path workDir) throws IOException {
    Checks.requireNonNull(request, "request");
    Checks.requireNonNull(workDir, "workDir");
    if (request.items().stream().noneMatch(TocRenderer::hasRenderableItems)) {
      throw new IllegalArgumentException("AssemblyRequest contains no renderable items");
    }
    Files.createDirectories(workDir);
    Path output = workDir.resolve(request.outputFileName());
    Files.deleteIfExists(output);
    MemoryUsageSetting memory =
        MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES).setTempDir(workDir.toFile());
    try (ScratchFile scratch = new ScratchFile(memory)) {
      return new Run(request, workDir, output, sharedStreamCache(scratch)).merge();
    } catch (Exception e) {
      Files.deleteIfExists(output);
      throw e;
    }
  }

  /**
   * A stream-cache function whose every {@code create()} shares one underlying scratch buffer,
   * so the merged document and all loaded sources draw on a single bounded budget. Closing a
   * returned cache is a no-op — the scratch buffer is owned and closed by {@code assemble()};
   * per-document buffers are still released individually as each document closes.
   *
   * @param scratch the assembly's single scratch buffer
   * @return the sharing create function
   */
  static StreamCacheCreateFunction sharedStreamCache(ScratchFile scratch) {
    return () -> new SharedScratchCache(scratch);
  }

  private record SharedScratchCache(ScratchFile scratch) implements RandomAccessStreamCache {

    @Override
    public RandomAccess createBuffer() throws IOException {
      return scratch.createBuffer();
    }

    @Override
    public void close() {
      // The scratch buffer is owned by assemble(); documents must not close it.
    }
  }

  private record PageRange(int start, int endExclusive) {
  }

  private static final class Run {

    private final Logger log = LoggerFactory.getLogger(Run.class);
    private final AssemblyRequest request;
    private final BundlePresentation presentation;
    private final Path workDir;
    private final Path output;
    private final StreamCacheCreateFunction streamCache;
    private final PdfFonts fonts = new PdfFonts();
    private final PDFMergerUtility merger = new PDFMergerUtility();
    private final List<PDDocument> openSourceDocuments = new ArrayList<>();
    private final List<Path> intermediateFiles = new ArrayList<>();
    private final List<AssembledItem> assembledItems = new ArrayList<>();
    private final List<BundleWarning> warnings = new ArrayList<>();
    private final List<PageRange> numberedRanges = new ArrayList<>();
    private final List<PageRange> confidentialRanges = new ArrayList<>();
    private PDDocument document;
    private OutlineBuilder outline;
    private TocRenderer toc;
    private int currentPage;
    private int itemOrdinal;

    private Run(AssemblyRequest request, Path workDir, Path output,
        StreamCacheCreateFunction streamCache) {
      this.request = request;
      this.presentation = request.presentation();
      this.workDir = workDir;
      this.output = output;
      this.streamCache = streamCache;
    }

    private AssemblyResult merge() throws IOException {
      try (PDDocument merged = new PDDocument(streamCache)) {
        this.document = merged;
        this.outline = new OutlineBuilder(merged, request.bundleTitle());

        if (request.titlePage()) {
          GeneratedPages.addTitlePage(merged, request, fonts);
          outline.addItem(outline.root(), TITLE_PAGE_BOOKMARK, currentPage);
          currentPage++;
        }
        if (presentation.tableOfContents()) {
          this.toc = new TocRenderer(merged, request, fonts);
          outline.addItem(outline.root(), TocRenderer.INDEX_PAGE, currentPage);
          currentPage += toc.pageCount();
        }

        renderNodes(request.items(), outline.root());
        stampPageNumbers();
        stampConfidentialMarkings();
        outline.setRootDestination();

        merged.save(output.toFile());
        return new AssemblyResult(output, merged.getNumberOfPages(), assembledItems, warnings);
      } finally {
        closeSourceDocuments();
        deleteIntermediateFiles();
      }
    }

    private void renderNodes(List<AssemblyNode> nodes, PDOutlineItem parentOutline)
        throws IOException {
      for (AssemblyNode node : nodes) {
        if (node instanceof AssemblyFolder folder) {
          if (!TocRenderer.hasRenderableItems(folder)) {
            continue;
          }
          PDOutlineItem folderOutline = parentOutline;
          if (presentation.sectionCoverSheets()) {
            int coverIndex = addCoverSheet(folder.title(), true);
            folderOutline = outline.addItem(parentOutline, folder.title(), coverIndex);
          }
          renderNodes(folder.children(), folderOutline);
          if (toc != null) {
            toc.setEndOfFolder(true);
          }
        } else {
          renderItem((AssemblyItem) node, parentOutline);
        }
      }
    }

    private void renderItem(AssemblyItem item, PDOutlineItem parentOutline) throws IOException {
      itemOrdinal++;
      String drawnTitle = TocRenderer.drawnItemTitle(item.title(), itemOrdinal);
      if (!drawnTitle.equals(item.title())) {
        warnings.add(BundleWarning.forDocument(WARNING_TITLE_NOT_RENDERABLE,
            "The title of document " + itemOrdinal + " has no drawable characters; the"
                + " fallback row text '" + drawnTitle + "' was used in the contents and on"
                + " generated pages. The bookmark keeps the original title.",
            item.title()));
      }
      int coverIndex = -1;
      if (presentation.documentCoverSheets()) {
        coverIndex = addCoverSheet(drawnTitle, false);
      }
      if (item.content() instanceof PdfSource source) {
        appendSourceDocument(item, drawnTitle, source, parentOutline, coverIndex);
      } else if (item.content() instanceof MediaLinkPage media) {
        final int pageIndex = currentPage;
        GeneratedPages.addMediaLinkPage(document, drawnTitle, item.date(), media, fonts);
        currentPage++;
        finishGeneratedItem(item, drawnTitle, parentOutline, coverIndex, pageIndex);
      } else {
        final int pageIndex = currentPage;
        GeneratedPages.addEmptySectionPage(document, drawnTitle, fonts);
        currentPage++;
        warnings.add(BundleWarning.forDocument(WARNING_EMPTY_SECTION_PAGE,
            "The expected section '" + item.title() + "' contains no documents;"
                + " the standard visible placeholder page was included.",
            item.title()));
        finishGeneratedItem(item, drawnTitle, parentOutline, coverIndex, pageIndex);
      }
    }

    private void appendSourceDocument(AssemblyItem item, String drawnTitle, PdfSource source,
        PDOutlineItem parentOutline, int coverIndex) throws IOException {
      try {
        Path file = source.path();
        if (request.watermark().isPresent()) {
          file = WatermarkRenderer.apply(file, request.watermark().get(), workDir, streamCache,
              fonts);
          intermediateFiles.add(file);
        }
        log.debug("Processing PDF, docTitle:{}, filename:{}", item.title(), file.getFileName());
        PDDocument newDoc = Loader.loadPDF(file.toFile(), streamCache);
        openSourceDocuments.add(newDoc);
        final PDDocumentOutline sourceOutline = newDoc.getDocumentCatalog().getDocumentOutline();
        newDoc.getDocumentCatalog().setDocumentOutline(null);
        appendWithStructureTreeFallback(item, newDoc);

        int startIndex = currentPage;
        int pages = newDoc.getNumberOfPages();
        if (toc != null) {
          toc.addDocument(drawnTitle, item.date(), startIndex);
        }
        PDOutlineItem documentOutlineItem = outline.addItem(parentOutline, item.title(),
            coverIndex >= 0 ? coverIndex : startIndex);
        if (outline.copySourceOutline(documentOutlineItem, sourceOutline,
            newDoc.getDocumentCatalog(), startIndex)) {
          warnings.add(BundleWarning.forDocument(WARNING_OUTLINE_TRUNCATED,
              "The document '" + item.title() + "' has an outline with a circular reference or"
                  + " nesting deeper than " + OutlineBuilder.MAX_COPY_DEPTH
                  + " levels; the excess was dropped from the bundle's bookmarks.",
              item.title()));
        }
        recordPlacement(item, coverIndex, startIndex, pages);
        currentPage += pages;
      } catch (IOException | RuntimeException e) {
        throw new IOException(String.format(
            "Error processing, document title: %s, file name: %s",
            item.title(), source.path().getFileName()), e);
      }
    }

    private void appendWithStructureTreeFallback(AssemblyItem item, PDDocument newDoc)
        throws IOException {
      int pagesBefore = document.getNumberOfPages();
      try {
        merger.appendDocument(document, newDoc);
      } catch (IndexOutOfBoundsException e) {
        while (document.getNumberOfPages() > pagesBefore) {
          document.removePage(document.getNumberOfPages() - 1);
        }
        newDoc.getDocumentCatalog().setStructureTreeRoot(new PDStructureTreeRoot());
        log.warn("Replacing malformed PDF structure tree of {}", item.title());
        merger.appendDocument(document, newDoc);
        warnings.add(BundleWarning.forDocument(WARNING_STRUCTURE_TREE_REPLACED,
            "The document '" + item.title() + "' has a malformed accessibility structure tree;"
                + " it was replaced with an empty one to complete the merge, so its pages lose"
                + " their tagged-PDF structure in the bundle.",
            item.title()));
      }
    }

    private void finishGeneratedItem(AssemblyItem item, String drawnTitle,
        PDOutlineItem parentOutline, int coverIndex, int pageIndex) throws IOException {
      if (toc != null) {
        toc.addDocument(drawnTitle, item.date(), pageIndex);
      }
      outline.addItem(parentOutline, item.title(), coverIndex >= 0 ? coverIndex : pageIndex);
      recordPlacement(item, coverIndex, pageIndex, 1);
    }

    private int addCoverSheet(String title, boolean folder) throws IOException {
      PDPage page = new PDPage();
      document.addPage(page);
      int pageIndex = currentPage;
      if (toc != null) {
        if (folder) {
          toc.addFolder(title, pageIndex);
        }
        // Divergence 10: drawn at the visible top-right and clickable. The service passes
        // swapped x/y arguments here, so its link renders off the page and cannot be clicked.
        PdfUtility.addRightLink(document, page, toc.getPage(), "Back to index", 40f,
            fonts.helvetica(), 12);
      }
      PdfUtility.addCenterText(document, page, title, 330, fonts.helveticaBold(), 14);
      currentPage++;
      return pageIndex;
    }

    private void recordPlacement(AssemblyItem item, int coverIndex, int startIndex, int pages) {
      assembledItems.add(new AssembledItem(item.title(), startIndex + 1, pages));
      numberedRanges.add(new PageRange(startIndex, startIndex + pages));
      if (item.confidential()) {
        int from = coverIndex >= 0 ? coverIndex : startIndex;
        confidentialRanges.add(new PageRange(from, startIndex + pages));
      }
    }

    private void stampPageNumbers() throws IOException {
      PageNumbers preset = presentation.pageNumbers();
      if (preset == PageNumbers.NONE) {
        return;
      }
      int totalPages = document.getNumberOfPages();
      for (PageRange range : numberedRanges) {
        for (int i = range.start(); i < range.endExclusive(); i++) {
          PdfUtility.addPageNumber(document, preset, i, totalPages, fonts.helveticaBold());
        }
      }
    }

    private void stampConfidentialMarkings() throws IOException {
      if (presentation.confidentialMarking() != ConfidentialMarking.APPROVED_HEADER) {
        return;
      }
      for (PageRange range : confidentialRanges) {
        for (int i = range.start(); i < range.endExclusive(); i++) {
          PdfUtility.addCenterText(document, document.getPage(i), "CONFIDENTIAL", 25,
              fonts.helveticaBold(), 14);
        }
      }
    }

    private void closeSourceDocuments() {
      for (PDDocument openDocument : openSourceDocuments) {
        try {
          openDocument.close();
        } catch (Exception e) {
          log.info("Closing source document failed, skipping");
        }
      }
    }

    private void deleteIntermediateFiles() {
      for (Path intermediate : intermediateFiles) {
        try {
          Files.deleteIfExists(intermediate);
        } catch (IOException e) {
          log.info("Could not delete intermediate file {}", intermediate);
        }
      }
    }
  }
}
