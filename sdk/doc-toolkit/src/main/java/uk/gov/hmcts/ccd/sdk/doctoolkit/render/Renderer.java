package uk.gov.hmcts.ccd.sdk.doctoolkit.render;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.ccd.sdk.doctoolkit.BundleDocument;
import uk.gov.hmcts.ccd.sdk.doctoolkit.BundleRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocConversionRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocFailure;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocResult;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocSource;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkitException;
import uk.gov.hmcts.ccd.sdk.doctoolkit.DocToolkitException.Reason;
import uk.gov.hmcts.ccd.sdk.doctoolkit.HtmlPage;
import uk.gov.hmcts.ccd.sdk.doctoolkit.MissingDocuments;
import uk.gov.hmcts.ccd.sdk.doctoolkit.PrintOptions;
import uk.gov.hmcts.ccd.sdk.doctoolkit.cdam.CdamDocuments;
import uk.gov.hmcts.ccd.sdk.doctoolkit.cdam.CdamFetchException;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.ConversionException;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.ConversionSource;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.ConvertedPdf;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.DocumentConverter;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.HtmlToPdf;
import uk.gov.hmcts.ccd.sdk.doctoolkit.convert.PrintPart;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.AssemblyContent;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.AssemblyItem;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.AssemblyNode;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.AssemblyRequest;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.AssemblyResult;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.BundlePresentation;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.MissingDocumentPage;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.PdfBundleAssembler;
import uk.gov.hmcts.ccd.sdk.doctoolkit.pdf.PdfSource;

/**
 * Renders requests. Every document goes through the same steps: its content is fetched (or its
 * HTML rendered), converted to PDF by the {@link DocumentConverter}, then joined. A bundle is
 * assembled with its contents and page numbers and, when printed, laid out for print as a
 * whole; a conversion request with print options lays out each document for print and joins
 * them, so each can be padded to start on a new sheet.
 */
public final class Renderer {
  private static final Logger log = LoggerFactory.getLogger(Renderer.class);
  private static final String PDF = "application/pdf";

  /** The largest source fetched from CDAM. */
  static final long MAX_SOURCE_BYTES = 300L * 1024 * 1024;

  private final DocumentConverter converter;
  private final CdamDocuments cdam;
  private final Path tempDirectory;
  private final PdfBundleAssembler assembler = new PdfBundleAssembler();

  /**
   * Creates the renderer.
   *
   * @param cdam the CDAM client, or null when CDAM sources are not supported
   */
  public Renderer(DocumentConverter converter, CdamDocuments cdam, Path tempDirectory) {
    this.converter = converter;
    this.cdam = cdam;
    this.tempDirectory = tempDirectory;
  }

  /** Renders the request into a new temporary directory, which the result deletes on close. */
  public DocResult render(DocRequest request) {
    return render(request, true);
  }

  /**
   * Renders the request. When {@code finalAttempt} is false, a bundle document that is only
   * temporarily unavailable fails the render, so a queued job retries it, rather than becoming a
   * placeholder page.
   */
  public DocResult render(DocRequest request, boolean finalAttempt) {
    Path job;
    try {
      job = JobDirectory.create(tempDirectory);
    } catch (IOException e) {
      throw new DocToolkitException(Reason.IO_FAILURE,
          "Could not create a working directory under " + tempDirectory, true, e);
    }
    boolean handedOver = false;
    try {
      Rendered rendered = switch (request) {
        case BundleRequest bundle -> bundle(bundle, job, finalAttempt);
        case DocConversionRequest conversion -> conversion(conversion, job);
      };
      log.info("Rendered {}: {} pages, {} document(s) missing", request.fileName(),
          rendered.pages(), rendered.failures().size());
      DocResult result = new DocResult(rendered.file(), request.fileName(), rendered.pages(),
          rendered.failures(), () -> JobDirectory.deleteRecursively(job));
      handedOver = true;
      return result;
    } catch (IOException e) {
      throw new DocToolkitException(Reason.IO_FAILURE,
          "Local I/O failed while rendering " + request.fileName(), true, e);
    } finally {
      if (!handedOver) {
        JobDirectory.deleteRecursively(job);
      }
    }
  }

  private record Rendered(Path file, int pages, List<DocFailure> failures) {
  }

  private Rendered bundle(BundleRequest request, Path job, boolean finalAttempt)
      throws IOException {
    Map<BundleDocument, AssemblyContent> contents = new IdentityHashMap<>();
    List<DocFailure> failures = new ArrayList<>();
    for (BundleDocument document : request.allDocuments()) {
      if (contents.containsKey(document)) {
        continue;
      }
      try {
        contents.put(document, new PdfSource(toPdf(document.source(), job)));
      } catch (DocumentFailure failure) {
        DocFailure reported = failure.reported(document.title());
        log.warn("{} could not be included: {} - {}", document.title(), reported.reason(),
            reported.detail());
        failures.add(reported);
        contents.put(document, new MissingDocumentPage(reported.reason().message()));
      }
    }
    if (!failures.isEmpty() && request.missingDocuments() == MissingDocuments.FAIL) {
      throw documentsFailed(request.fileName(), failures);
    }
    if (!finalAttempt && failures.stream().anyMatch(failure -> failure.reason().isTransient())) {
      // Worth another attempt before settling for placeholder pages.
      DocToolkitException failed = documentsFailed(request.fileName(), failures);
      throw new DocToolkitException(Reason.DOCUMENTS_FAILED, failed.getMessage(), true, failures,
          null);
    }
    Optional<Path> cover = Optional.empty();
    if (request.coverPage().isPresent()) {
      DocSource coverPage = request.coverPage().get();
      try {
        cover = Optional.of(toPdf(coverPage, job));
      } catch (DocumentFailure failure) {
        throw documentsFailed(request.fileName(), List.of(failure.reported(coverPage.name())));
      }
    }
    AssemblyRequest assembly = AssemblyMapping.map(request, contents, cover,
        AssemblyMapping.watermark(request.watermark(), job), "bundle.pdf");
    if (assembly.items().isEmpty()) {
      throw new DocToolkitException(Reason.INVALID_REQUEST,
          request.fileName() + " has no documents and no placeholder pages", false, null);
    }
    AssemblyResult assembled = assemble(assembly, job, request.fileName());
    if (request.print().isEmpty()) {
      return new Rendered(assembled.outputPdf(), assembled.totalPages(), failures);
    }
    ConversionSource bundle = new ConversionSource(assembled.outputPdf(), "bundle.pdf", PDF);
    return print(request.title(), List.of(PrintPart.convert(bundle)), request.print().get(), job,
        failures);
  }

  private Rendered conversion(DocConversionRequest request, Path job) throws IOException {
    Optional<PrintOptions> print = request.print();
    if (print.isPresent()) {
      List<PrintPart> parts = new ArrayList<>();
      for (DocConversionRequest.Part part : request.parts()) {
        parts.add(printPart(part, request, job));
      }
      return print(request.title(), parts, print.get(), job, List.of());
    }
    List<AssemblyNode> items = new ArrayList<>();
    for (DocConversionRequest.Part part : request.parts()) {
      Path pdf;
      try {
        pdf = toPdf(part.source(), job);
      } catch (DocumentFailure failure) {
        throw documentsFailed(request.fileName(), List.of(failure.reported(part.source().name())));
      }
      items.add(new AssemblyItem(part.source().name(), Optional.empty(), false,
          new PdfSource(pdf)));
    }
    AssemblyRequest joined = new AssemblyRequest(request.title(), "document.pdf",
        Optional.empty(), BundlePresentation.bare(), false, Optional.empty(),
        AssemblyMapping.watermark(request.watermark(), job), items);
    AssemblyResult assembled = assemble(joined, job, request.fileName());
    return new Rendered(assembled.outputPdf(), assembled.totalPages(), List.of());
  }

  private PrintPart printPart(DocConversionRequest.Part part, DocConversionRequest request,
      Path job) throws IOException {
    try {
      if (part.unchanged()) {
        return PrintPart.asIs(prepare(part.source(), job).file(), part.source().name());
      }
      if (request.watermark().isPresent()) {
        Path pdf = toPdf(part.source(), job);
        Path marked = PdfBundleAssembler.watermark(pdf,
            AssemblyMapping.watermark(request.watermark(), job).orElseThrow(), job);
        return PrintPart.convert(new ConversionSource(marked, part.source().name(), PDF));
      }
      return PrintPart.convert(prepare(part.source(), job));
    } catch (DocumentFailure failure) {
      throw documentsFailed(request.fileName(), List.of(failure.reported(part.source().name())));
    }
  }

  private Rendered print(String title, List<PrintPart> parts, PrintOptions options, Path job,
      List<DocFailure> failures) throws IOException {
    try (ConvertedPdf letter = converter.toPrintReadyLetter(title, parts, options)) {
      return new Rendered(adopt(letter, job), letter.pageCount(), failures);
    } catch (ConversionException e) {
      throw switch (e.reason()) {
        case OUTPUT_TOO_LARGE -> new DocToolkitException(Reason.OUTPUT_TOO_LARGE, e.getMessage(),
            false, e);
        case IO_FAILURE -> new DocToolkitException(Reason.IO_FAILURE, e.getMessage(), true, e);
        case CONVERTER_NOT_CONFIGURED -> new DocToolkitException(Reason.NOT_CONFIGURED,
            e.getMessage(), false, e);
        default -> documentsFailed(title, List.of(new DocFailure(title, reason(e),
            e.getMessage())), e);
      };
    }
  }

  private AssemblyResult assemble(AssemblyRequest request, Path job, String fileName) {
    try {
      return assembler.assemble(request, job.resolve("assembly"));
    } catch (IOException | RuntimeException e) {
      throw new DocToolkitException(Reason.ASSEMBLY_FAILED,
          fileName + " could not be assembled: " + e.getMessage(), false, e);
    }
  }

  /** Converts a source to PDF in the job directory. */
  private Path toPdf(DocSource source, Path job) throws DocumentFailure, IOException {
    ConversionSource prepared = prepare(source, job);
    try (ConvertedPdf pdf = converter.toPdf(prepared)) {
      pdf.warnings().forEach(warning -> log.info("{}", warning.message()));
      return adopt(pdf, job);
    } catch (ConversionException e) {
      throw failure(e);
    }
  }

  /** Puts the source's content in a file the converter can read. */
  private ConversionSource prepare(DocSource source, Path job) throws DocumentFailure, IOException {
    return switch (source) {
      case DocSource.LocalFile file ->
          new ConversionSource(file.file(), file.name(), file.mediaType());
      case DocSource.Bytes bytes -> {
        Path file = JobDirectory.createFile(job, ".source");
        Files.write(file, bytes.content());
        yield new ConversionSource(file, bytes.name(), bytes.mediaType());
      }
      case HtmlPage page -> {
        Path file = JobDirectory.createFile(job, ".pdf");
        try {
          HtmlToPdf.render(page.title(), page.body(), page.css(), file);
        } catch (ConversionException e) {
          throw new DocumentFailure(DocFailure.Reason.CONVERSION_FAILED, e.getMessage(), e);
        }
        yield new ConversionSource(file, page.name() + ".pdf", PDF);
      }
      case DocSource.Cdam document -> {
        if (cdam == null) {
          throw new DocToolkitException(Reason.NOT_CONFIGURED, document.name() + " is a CDAM"
              + " document, and CDAM is not configured: the toolkit needs the service's"
              + " CaseDocumentClientApi, AuthTokenGenerator and a DocToolkitSystemUser bean.",
              false, null);
        }
        try {
          CdamDocuments.Fetched fetched = cdam.fetch(document.documentId(),
              JobDirectory.createFile(job, ".source"), MAX_SOURCE_BYTES);
          String name = document.name() != null && !document.name().equals(
              document.documentId().toString()) ? document.name() : fetched.fileName();
          yield new ConversionSource(fetched.file(), name, fetched.mediaType());
        } catch (CdamFetchException e) {
          throw new DocumentFailure(e.reason(), e.getMessage(), e);
        }
      }
    };
  }

  private static Path adopt(ConvertedPdf pdf, Path job) throws IOException {
    // The converter's result lives in its own directory, which closing the result deletes.
    Path owned = JobDirectory.createFile(job, ".pdf");
    Files.move(pdf.file(), owned, StandardCopyOption.REPLACE_EXISTING);
    return owned;
  }

  private static DocumentFailure failure(ConversionException e) {
    return switch (e.reason()) {
      case CONVERTER_NOT_CONFIGURED -> throw new DocToolkitException(Reason.NOT_CONFIGURED,
          e.getMessage(), false, e);
      case IO_FAILURE -> throw new DocToolkitException(Reason.IO_FAILURE, e.getMessage(), true, e);
      default -> new DocumentFailure(reason(e), e.getMessage(), e);
    };
  }

  private static DocFailure.Reason reason(ConversionException e) {
    return switch (e.reason()) {
      case UNSUPPORTED_MEDIA_TYPE -> DocFailure.Reason.UNSUPPORTED_FORMAT;
      case MEDIA_TYPE_CONFLICT, INVALID_CONTENT, ENCRYPTED -> DocFailure.Reason.UNREADABLE;
      case CONVERTER_FAILED -> e.isTransientFailure()
          ? DocFailure.Reason.UNAVAILABLE : DocFailure.Reason.CONVERSION_FAILED;
      case OUTPUT_TOO_LARGE -> DocFailure.Reason.TOO_LARGE;
      default -> DocFailure.Reason.CONVERSION_FAILED;
    };
  }

  private static DocToolkitException documentsFailed(String fileName, List<DocFailure> failures) {
    return documentsFailed(fileName, failures, null);
  }

  private static DocToolkitException documentsFailed(String fileName, List<DocFailure> failures,
      Throwable cause) {
    StringBuilder message = new StringBuilder(fileName).append(" could not be rendered: ");
    for (int i = 0; i < failures.size(); i++) {
      DocFailure failure = failures.get(i);
      message.append(i == 0 ? "" : "; ").append(failure.title()).append(" (")
          .append(failure.reason()).append(')');
    }
    boolean allTransient = failures.stream().allMatch(failure -> failure.reason().isTransient());
    return new DocToolkitException(Reason.DOCUMENTS_FAILED, message.toString(), allTransient,
        failures, cause);
  }

  /** One document could not be included. */
  private static final class DocumentFailure extends Exception {
    private final DocFailure.Reason reason;

    DocumentFailure(DocFailure.Reason reason, String detail, Throwable cause) {
      super(detail, cause);
      this.reason = reason;
    }

    DocFailure reported(String title) {
      return new DocFailure(title, reason, getMessage());
    }
  }
}
