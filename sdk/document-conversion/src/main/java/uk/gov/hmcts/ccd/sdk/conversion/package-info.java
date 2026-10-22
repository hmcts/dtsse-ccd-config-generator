/**
 * Document-to-PDF conversion, on its own or laid out for print.
 *
 * <p>{@link uk.gov.hmcts.ccd.sdk.conversion.DocumentConverter} is the entry point. Its building
 * blocks, {@link uk.gov.hmcts.ccd.sdk.conversion.ImageToPdf} and
 * {@link uk.gov.hmcts.ccd.sdk.conversion.PdfChecks}, are public so document bundling can reuse
 * them inside its own handler pipeline.
 */
package uk.gov.hmcts.ccd.sdk.conversion;
