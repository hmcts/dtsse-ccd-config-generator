/**
 * PDFBox-based bundle assembly, adapted from {@code em-stitching-api}'s {@code pdf} package.
 *
 * <p>The entry point is {@link uk.gov.hmcts.ccd.sdk.bundling.pdf.PdfBundleAssembler}, which takes
 * an {@link uk.gov.hmcts.ccd.sdk.bundling.pdf.AssemblyRequest} — a clean internal model carrying
 * exactly what the rendering pipeline hands over after conversion (an ordered tree of folders and
 * already-converted PDF items, plus generated-page specs) — and produces the merged bundle PDF
 * with table of contents, cover sheets, bookmarks, pagination, confidential markings and
 * watermarks.
 *
 * <p>The assembler never mutates source files, never mutates global JVM state, preserves source
 * page dimensions and rotations, and merges under a bounded-heap PDFBox stream cache that spills
 * to the caller-supplied working directory.
 */
package uk.gov.hmcts.ccd.sdk.bundling.pdf;
