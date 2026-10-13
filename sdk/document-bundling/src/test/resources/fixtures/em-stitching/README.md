# em-stitching-api test fixtures

Copied verbatim from `em-stitching-api/src/test/resources` (`test-files/` plus the loose
`one-page.pdf`, `flying-pig.jpg`, and `wordDocument2.docx`) at commit
`7c269a4d1b22b15e10dfceaeff59e4a92be777e8`.

These are the current stitching service's own fixtures; they carry the accumulated history of
past rendering defects (action/named-destination outlines, office formats, images) and anchor
the regression baseline described in `docs/bundling-stitching/document-bundling.md` → *Testing*.
`outlined.pdf`, `outline_with_actions.pdf` and `outline_with_named.pdf` are the sources of the
outline-preservation goldens; `FL-FRM-GOR-ENG-12345.pdf` is the Docmosis-rendered cover page the
`toc-coverpage` goldens were generated with; `schmcts.png` is the `image-watermark` golden's
watermark image.

Do not edit these files. To refresh, re-copy from a newer em-stitching-api commit and update the
SHA here and in the characterisation goldens.
