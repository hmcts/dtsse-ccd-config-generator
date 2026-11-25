package uk.gov.hmcts.ccd.sdk.doctoolkit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import uk.gov.hmcts.ccd.sdk.type.Document;

/**
 * Where a document's content comes from.
 *
 * <p>Almost every document is in CDAM: pass the case's {@link Document} (or its id) and the
 * toolkit fetches it as the system user. Local files and bytes are for tests and for documents a
 * service generates itself; an {@link HtmlPage} is a page the service writes, for anything the
 * toolkit cannot convert.
 */
public sealed interface DocSource permits DocSource.Cdam, DocSource.LocalFile, DocSource.Bytes,
    HtmlPage {

  /** The name the document is reported by, and its bundle title when none is given. */
  String name();

  /** A CCD document field's value; the content is fetched from CDAM. */
  static DocSource of(Document document) {
    Validate.requireNonNull(document, "document");
    String url = Validate.requireNonBlank(document.getUrl(), "document.url");
    String id = url.substring(url.lastIndexOf('/') + 1);
    UUID documentId;
    try {
      documentId = UUID.fromString(id);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("document.url does not end in a CDAM document id: " + url);
    }
    return new Cdam(documentId, document.getFilename());
  }

  /** A CDAM document, by id. */
  static DocSource cdam(UUID documentId) {
    return new Cdam(documentId, null);
  }

  /**
   * A CDAM document, by id, reported by {@code name}.
   */
  static DocSource cdam(UUID documentId, String name) {
    return new Cdam(documentId, name);
  }

  /** A local file; its type is detected from the content and file name. */
  static DocSource file(Path file) {
    Validate.requireNonNull(file, "file");
    return new LocalFile(file, file.getFileName().toString(), null);
  }

  /** A local file with a declared media type, checked against the content. */
  static DocSource file(Path file, String name, String mediaType) {
    return new LocalFile(file, name, mediaType);
  }

  /** Content in memory; its type is detected from the content and name. */
  static DocSource bytes(byte[] content, String name) {
    return new Bytes(content, name, null);
  }

  /** Content in memory with a declared media type, checked against the content. */
  static DocSource bytes(byte[] content, String name, String mediaType) {
    return new Bytes(content, name, mediaType);
  }

  /**
   * A CDAM document. {@code name} may be null, when CDAM's file name is used.
   */
  record Cdam(UUID documentId, String name) implements DocSource {
    public Cdam {
      Validate.requireNonNull(documentId, "documentId");
    }

    @Override
    public String name() {
      return name == null || name.isBlank() ? documentId.toString() : name;
    }
  }

  /**
   * A local file. {@code mediaType} may be null, when it is detected.
   */
  record LocalFile(Path file, String name, String mediaType) implements DocSource {
    public LocalFile {
      Validate.requireNonNull(file, "file");
      Validate.requireNonBlank(name, "name");
      if (!Files.isRegularFile(file)) {
        throw new IllegalArgumentException(file + " is not a readable file");
      }
    }
  }

  /**
   * Content in memory. {@code mediaType} may be null, when it is detected.
   */
  record Bytes(byte[] content, String name, String mediaType) implements DocSource {
    public Bytes {
      Validate.requireNonNull(content, "content");
      Validate.requireNonBlank(name, "name");
      content = content.clone();
    }

    @Override
    public byte[] content() {
      return content.clone();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Bytes(byte[] otherContent, String otherName, String otherType)
          && Arrays.equals(content, otherContent) && name.equals(otherName)
          && java.util.Objects.equals(mediaType, otherType);
    }

    @Override
    public int hashCode() {
      return 31 * java.util.Objects.hash(name, mediaType) + Arrays.hashCode(content);
    }

    @Override
    public String toString() {
      return "Bytes[name=" + name + ", mediaType=" + mediaType + ", " + content.length + " bytes]";
    }
  }
}
