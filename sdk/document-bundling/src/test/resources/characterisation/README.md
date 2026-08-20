# Characterisation goldens

Golden rendering-parity baselines generated from **em-stitching-api commit
`7c269a4d1b22b15e10dfceaeff59e4a92be777e8`** by running the service's real
`PDFMerger`/`TableOfContents`/`PDFOutline`/`PDFWatermark` against its own fixtures.

Per scenario (17 scenarios):

* `facts.json` — the authoritative semantic golden, produced by
  `uk.gov.hmcts.ccd.sdk.bundling.testsupport.PdfSemantics`: page count, page labels, per-page
  position-sorted whitespace-normalised text, page-number stamps with coordinates
  (`pageNumberStamps`), per-page image facts with raster hashes (`images`), the outline tree
  with resolved target pages, and links with resolved target pages. Regression tests compare
  against this under the per-field policy in `CharacterisationRegressionTest`'s javadoc —
  never byte-for-byte against the PDF. Regeneration is byte-reproducible.
* `golden.pdf` — the raw em-stitching output for eyeballing;
  `PdfSemanticsTest` verifies it still extracts to exactly `facts.json`.

Do **not** edit these files by hand. Regenerate with the documented manual step in
`scripts/bundling-characterisation/README.md` (scenario definitions and the facts schema live
there too).
