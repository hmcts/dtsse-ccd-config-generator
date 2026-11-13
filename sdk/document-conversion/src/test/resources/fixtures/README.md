# Conversion fixtures

Real-world inputs, copied verbatim from `document-bundling/src/test/resources/fixtures/em-stitching`
(themselves from em-stitching-api at commit `7c269a4d1b22b15e10dfceaeff59e4a92be777e8`):
`flying-pig.jpg`, `schmcts.png`, `one-page.pdf`, `Potential_Energy_PDF.pdf`, `wordDocument.doc`,
`wordDocument2.docx`, `TestExcelConversion.xlsx`.

Edge-case inputs (multi-frame TIFFs, alpha PNGs, transparency, full-bleed and rotated pages,
oversized photos, encrypted PDFs) are generated deterministically by
`testsupport/Fixtures.java` rather than committed, so each one says in code what it exercises.
