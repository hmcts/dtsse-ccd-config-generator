# em-stitching-api test fixtures

Copied verbatim from `em-stitching-api/src/test/resources` (`test-files/` plus the loose
`one-page.pdf`, `flying-pig.jpg`, and `wordDocument2.docx`) at commit
`7c269a4d1b22b15e10dfceaeff59e4a92be777e8`.

These are the current stitching service's own fixtures; they carry the accumulated history of
past rendering defects (action/named-destination outlines, office formats, images) and anchor
the regression baseline described in
`docs/bundling-stitching/document-bundling-module-design.md` →
"Regression baseline: characterisation against the local em-stitching-api".

Do not edit these files. To refresh, re-copy from a newer em-stitching-api commit and update the
SHA here and in the characterisation goldens.
