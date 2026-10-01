# Document bundling: scope, delivery plan and requirements coverage

**Status:** living document, updated with each PR in the stack.
**Companion:** [document-bundling.md](document-bundling.md) (how to use the module).

## 1. Design goals

* A composable, extensible library. Service teams get a default bundling approach that matches
  today's `em-stitching-api` output, plus ways to extend it: more file types, customised cover
  and index pages, and whatever folder and file structure suits their case type.
* An asynchronous mechanism for bundle requests, for decentralised teams, that lives in the
  service's own database.
* Better observability and auditing than the shared service offers today: structured logs and
  metrics in the service's own telemetry, typed failures that name the document at fault, and a
  record of what was bundled, by whom and when.

## Design non-goals

* Changing how stitching works or the technology it uses. PDFBox and Docmosis stay; the rendering
  code is a port, and its output is held to the microservice's by characterisation goldens.
* Forcing services already integrated with em-stitching to change what they do in order to
  migrate. Moving should be at most a minor refactor and some extra configuration.

## 2. Delivery plan

| PR | Branch | Contents |
|---|---|---|
| 1 | `stitching-core` | Synchronous renderer and public API; PDF, image and Docmosis office handlers; contents, cover sheets, bookmarks from the request tree, pagination, confidential header, title and empty-section pages; limits and concurrency permit; Spring auto-configuration; e2e reference integration (render, upload to CDAM, attach, record). |
| 2 | `stitching-observability` | MDC keys, per-stage timings on the result, Micrometer meters. |
| 3 | `stitching-outbox` | Durable execution: `ccd_bundle_job` table and module-owned Flyway migration, `submit` in the caller's transaction, lease-based worker, bounded retry, progress listeners, request JSON round-trip, `BundleJobCompletionHandler` for the consumer's upload. |
| 4 | `stitching-rendering-features` | Everything the microservice renders that PR1 leaves out: Docmosis cover-page templates, approved image watermark presets, source-outline preservation, content-based media type detection, readability inspection (no-text warning), bounded Docmosis responses, and the new audio/video link pages. |
| later | | CDAM resolver and destination adapters; characterisation harness and full design history; section 4. |

### Feature matrix

| Feature | Where | Notes |
|---|---|---|
| Ordered sections and documents, nested folders | PR1 | `BundleRequest`/`BundleSection`/`BundleDocument` |
| Titles and dates in contents, cover sheets and bookmarks | PR1 | Drawn text is WinAnsi; Unicode font in a future PR |
| Clickable contents page, cover sheets, title page | PR1 | |
| Page numbers (`N`, `N of M`, three positions) | PR1 | `PageNumbers` presets |
| Confidential header per flagged document | PR1 | `ConfidentialMarking.APPROVED_HEADER` |
| Empty expected section page | PR1 | `EmptySectionPolicy.INCLUDE_PLACEHOLDER` |
| PDF passthrough with validation, image conversion | PR1 | |
| Office conversion through the shared Docmosis service | PR1 | `/rs/convert` |
| Per-media-type extension model | PR1 | `BundlingExtension`, `DocumentHandler` |
| Typed failures naming documents; no partial bundle | PR1 | `BundleGenerationException` |
| Limits (documents, bytes, pages) and render permit | PR1 | `BundleLimits`, `max-concurrent-renders` |
| Structured logging, timings, meters | PR2 | |
| Durable async execution, retries, progress events | PR3 | |
| Request JSON serialisation | PR3 | Moves to the core API in a future PR |
| Cover page from a Docmosis template | PR4 | 23 of 29 orchestrator configurations use one |
| Image watermark presets | PR4 | IAC configurations use the HMCTS logo |
| Source PDF bookmarks preserved under the document | PR4 | |
| Content-based media type detection | PR4 | Corrects lying document-store metadata |
| No-extractable-text warning (scanned evidence) | PR4 | |
| Audio/video link pages | PR4 | New; no microservice equivalent |
| Built-in CDAM resolver and destination | later | Consumer uploads today (e2e shows how) |
| Missing-document policy (omit and warn) | future PR | Section 4 |
| Audit record type | future PR | Section 4 |
| Regenerate a deleted bundle | future PR | Section 4 |
| Per-document progress | future PR | Section 4 |
| Accessibility baseline (tagged generated pages, metadata) | future PR | Section 4 |
| Free-form watermark coordinates, arbitrary overlays | not planned | Deliberately withheld |

## 3. One-click bundle requirements: coverage

The requirements describe the whole feature (service plus SDK). For each, the table says what the
SDK contributes, what remains the service's, and where the SDK still falls short ("Future PR"
rows are itemised in section 4).

| # | Requirement | SDK contribution | Service contribution | Gap |
|---|---|---|---|---|
| 1 | Generate a bundle with one click | PR1 render; PR3 submit from the event | The CCD event, document selection, request building | No |
| 2 | Generated within an agreed SLA (1 minute) | PR2 timings and meters to measure it; PR1 permit bounds contention | SLA monitoring; sizing | Future PR: hard `maxElapsed` limit. PR3 leases bound a worker's run. |
| 3 | Status of generation (in progress, percentage, failed) | PR3 job states and progress events | The status field and UI | Future PR: per-document progress listener |
| 4 | Large bundles, high volume of files | Bounded memory model (64 MB scratch spill, one document spooled at a time); limits are configuration, defaults 100 documents / 1,000 pages / 1 GB | Set limits from evidence; pod sizing | No, provided the defaults are raised where needed. The 100-document default is conservative. |
| 5 | Automatic generation at 5am on the hearing day | PR3 submit from a scheduled task; same execution path as the user click | The scheduler and hearing lookup | No |
| 6 | Bundle accessible in case file view | Result carries the stored document reference the service records | Case model, case file view configuration | No. The SDK imposes no case-data shape (the e2e `CaseBundle` is one example). |
| 7 | Download as PDF | The artifact is a PDF | CDAM upload and links | No |
| 8 | Internal users only | None | ACLs on the bundle field and document classification | No. The e2e restricts the collection to caseworker and superuser. |
| 9 | Delete with confirmation, audited | None today | Event, confirmation, CDAM deletion, audit entry | Future PR: audit record, regenerate |
| 10 | Automatic deletion after retention | None today | Retention job | Future PR: retention guidance |
| 11 | Paginated, searchable, clickable contents | PR1 pagination and contents; text layers preserved | OCR before upload for scanned sources | No for text sources. Scanned PDFs are not searchable and the SDK cannot make them so; PR4 restores the warning that flags them. |
| 12 | Titles and dates preserved exactly | PR1 draws the supplied title and date | Supply them | Future PR: Unicode font. Drawn text is WinAnsi today, so characters outside it are dropped from contents and cover sheets (bookmarks keep the full title). |
| 13 | Everything in case file view, current at generation time, same order | Explicit ordered tree; every unique reference fetched once at render time | Build the request from the case file view | No |
| 14 | Organised by folder, chronological within folder | Nested sections; document order is the request order | Sort by date when building the request | No |
| 15 | Inform about missing documents and continue | **None.** PR1 fails closed by design: any unresolvable document fails the render and nothing is published | | Future PR: opt-in `MissingDocumentPolicy`. The design chose fail-closed to avoid silently incomplete court bundles. |
| 16 | Identify an empty folder | PR1 empty-section page and warning | Choose `INCLUDE_PLACEHOLDER` | No |
| 17 | Identify confidential documents | PR1 header on flagged documents' pages | Flag documents | No |
| 18 | Audit trail of generation (user, date, time) | PR3 rows hold request, initiator, timestamps, result and failure; PR1 result has everything an audit entry needs | Persist or emit the entry | Future PR: `BundleAuditRecord` |
| 19 | Audit trail of deletion | None | Audit entry at deletion | Future PR: `BundleAuditRecord` |
| 20 | Accessible PDF | Text layers preserved; structure-tree fallback | Accessible sources | Future PR: accessibility baseline |

## 4. To be addressed in future PRs

Requirements the stack does not yet cover, each with the SDK change that closes it:

| Requirement | SDK change | Notes |
|---|---|---|
| 18, 19 audit trail | `BundleAuditRecord`: one JSON-serialisable record built by the SDK from the request, context and result (or failure), with a `deleted(record, actor, at)` counterpart the service completes | Outbox persists it; synchronous consumers persist it or emit it as an event |
| 9, 10 delete and retention, with recovery | Request JSON serialisation moved from PR3 to the core API so the request can be stored with the bundle record; `regenerate` on the outbox and a synchronous equivalent that re-runs a stored request under a new id; guidance that job rows and audit records outlive PDF retention | A bundle is a deterministic function of its request and the source documents, so a deleted bundle is recoverable from the stored request alone |
| 15 inform about missing documents and continue | `MissingDocumentPolicy` on the request: default `FAIL` (today), opt-in `OMIT_WITH_PLACEHOLDER` rendering a standard "document unavailable" page and reporting omissions on the result; access-denied still fails closed | Should Have |
| 3 generation status with percentage | `RenderProgressListener` on the builder (`documentConverted(index, total)`, stage transitions), forwarded by the outbox as progress events | Status text "converting 7 of 42" derives from it |
| 2 one-minute SLA as a hard limit | `maxElapsed` limit checked between documents, `TIMED_OUT` code retried by the outbox | PR2 timings already measure it; PR3 leases bound a run |
| 12 titles exactly as recorded, non-Latin | Embedded Unicode TrueType font for generated text instead of WinAnsi standard fonts | Diverges from the goldens' page geometry; needed before services with non-Latin party names migrate |
| 20 accessible PDF | Tagged structure for generated pages, document title and language metadata, reading order for the contents page, preserve source tags through the merge rather than replacing a broken structure tree | Full PDF/UA conformance is a platform question; the merge cannot make inaccessible sources accessible |
| 4 large bundles | None required; raise the conservative defaults (100 documents, 1,000 pages) from evidence | Configuration |
| 11 searchable scanned sources | None in the SDK; OCR before upload. PR4's no-text warning flags them | |

## 5. Decisions taken in this stack and their rationale

* **The renderer returns the document; storage is the service's.** Removes the CDAM coupling from
  the core, keeps the renderer testable, and lets a service choose CDAM, dm-store or anything
  else. The e2e project shows the CDAM upload and case attachment in about 60 lines.
* **No case-data output shape.** The `CcdBundleDTO`-compatible echo was half-implemented and
  couples the SDK to XUI's bundle model; consumers map `BundleResult` to their own field.
* **Presentation is preset-based.** No free coordinates, fonts or overlays over evidence pages.
