/**
 * Document-to-PDF conversion, on its own or laid out for print.
 *
 * <p>{@link uk.gov.hmcts.ccd.sdk.conversion.DocumentConverter} is the entry point. To convert a
 * media type it does not support, implement {@link uk.gov.hmcts.ccd.sdk.conversion.PdfConverter}
 * and register it; document bundling uses the same converter, so a type added here can also be
 * bundled.
 */
package uk.gov.hmcts.ccd.sdk.conversion;
