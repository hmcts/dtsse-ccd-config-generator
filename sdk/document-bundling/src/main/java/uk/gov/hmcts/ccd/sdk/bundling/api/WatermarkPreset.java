package uk.gov.hmcts.ccd.sdk.bundling.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An approved image watermark: a named image registered on the renderer through
 * {@code BundleRendererBuilder.watermarkImage(name, path)}, centred on the first or every page of
 * each source document, drawn over or behind the content. There are deliberately no free
 * coordinates, scaling or text; a service with a new legal marking registers a new image.
 *
 * @param imageName the name the image was registered under on the renderer
 * @param scope which pages of each document receive the watermark
 * @param rendering which layer the watermark is drawn on
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WatermarkPreset(
    @JsonProperty("imageName") String imageName,
    @JsonProperty("scope") Scope scope,
    @JsonProperty("rendering") Rendering rendering) {

  /** Which pages of each source document receive the watermark. */
  public enum Scope {
    /** Only the first page of each document. */
    FIRST_PAGE,
    /** Every page of each document. */
    ALL_PAGES
  }

  /** Which layer the watermark is drawn on, mirroring em-stitching's image rendering options. */
  public enum Rendering {
    /** Drawn over the page content. */
    OPAQUE,
    /** Drawn behind the page content. */
    TRANSLUCENT
  }

  public WatermarkPreset {
    Validate.requireNonBlank(imageName, "WatermarkPreset.imageName");
    Validate.requireNonNull(scope, "WatermarkPreset.scope");
    Validate.requireNonNull(rendering, "WatermarkPreset.rendering");
  }

  /** The named image on every page of every document, drawn over the content. */
  public static WatermarkPreset allPages(String imageName) {
    return new WatermarkPreset(imageName, Scope.ALL_PAGES, Rendering.OPAQUE);
  }
}
