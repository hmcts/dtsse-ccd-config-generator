/**
 * The stable public API of the document-bundling module: the renderer entry point, request and
 * result models, consumer ports, and the typed error catalogue.
 *
 * <p>Converting documents to PDF is document-conversion's job: to bundle a file type it does not
 * support, register a {@code uk.gov.hmcts.ccd.sdk.conversion.PdfConverter} with the
 * {@code DocumentConverter} the renderer uses.
 */
package uk.gov.hmcts.ccd.sdk.bundling.api;
