/**
 * The stable public API of the document-bundling module: the renderer entry point, request and
 * result models, consumer ports, the per-media-type extension SPI, and the typed error catalogue.
 *
 * <p>The design is described in {@code docs/bundling-stitching/document-bundling-module-design.md}.
 * Built-in handler implementations live in {@code uk.gov.hmcts.ccd.sdk.bundling.convert}; this
 * package holds only the contracts a consuming service codes against.
 */
package uk.gov.hmcts.ccd.sdk.bundling.api;
