package uk.gov.hmcts.ccd.sdk.doctoolkit;

/**
 * A page the service writes in HTML, rendered to PDF in-process. Use it for anything the toolkit
 * cannot convert itself: a summary of a recording, a spreadsheet the service has its own view
 * of, a notice that a document is held elsewhere.
 *
 * <pre>{@code
 * DocSource source = SUPPORTED.contains(document.type())
 *     ? DocSource.of(document.file())
 *     : HtmlPage.builder().title(document.name()).body(summarise(document)).build();
 * }</pre>
 *
 * <p>The body may be any HTML fragment or document. Nothing is fetched while rendering: images
 * must be {@code data:} URIs, and links to stylesheets, fonts or images elsewhere are ignored.
 * Text is set in the PDF standard fonts, so it covers Latin script only.
 */
public final class HtmlPage implements DocSource {
  private final String title;
  private final String body;
  private final String css;

  private HtmlPage(Builder builder) {
    this.title = builder.title;
    this.body = Validate.requireNonNull(builder.body, "HtmlPage.body");
    this.css = builder.css;
  }

  /** A page with this HTML and no title. */
  public static HtmlPage of(String html) {
    return builder().body(html).build();
  }

  /** Starts a page. */
  public static Builder builder() {
    return new Builder();
  }

  /** The page's title, used as its bundle title and in the PDF's metadata. */
  public String title() {
    return title;
  }

  /** The HTML. */
  public String body() {
    return body;
  }

  /** Extra CSS applied to the page, or null. */
  public String css() {
    return css;
  }

  @Override
  public String name() {
    return title == null || title.isBlank() ? "Page" : title;
  }

  @Override
  public String toString() {
    return "HtmlPage[title=" + title + ", " + body.length() + " characters]";
  }

  /**
   * Builds an {@link HtmlPage}.
   */
  public static final class Builder {
    private String title;
    private String body;
    private String css;

    private Builder() {
    }

    /** The page's title. */
    public Builder title(String title) {
      this.title = title;
      return this;
    }

    /** The page's HTML: a fragment or a whole document. */
    public Builder body(String html) {
      this.body = Validate.requireNonNull(html, "HtmlPage.body");
      return this;
    }

    /**
     * CSS applied to the page, such as {@code "body { font-size: 11pt }"}.
     */
    public Builder css(String css) {
      this.css = css;
      return this;
    }

    /** Builds the page. */
    public HtmlPage build() {
      return new HtmlPage(this);
    }
  }
}
