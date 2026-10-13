# Docweave template picker review

The review covers the desktop picker layout, keyboard selection and insertion,
the blank-line `/` shortcut, and immediate searches with one request in flight
and only the latest query queued. Existing backend, migration and build changes
in the working directory are outside this change.

## Findings and fixes

### P2: Insertion and subsequent typing shared an undo step — fixed

Insert a template and immediately type more wording. Previously, Undo could
remove both the new wording and the template. Closing ProseMirror history on
the insertion transaction separated it from earlier edits, but left its history
group open for subsequent typing.

`frontend/docweave/src/client.ts` now closes history after insertion with a
metadata-only transaction as well. The public editor tests exercise both an
empty paragraph and an empty numbered clause: the first Undo removes follow-up
typing, and the second removes the template while preserving the original blank
paragraph or numbered clause.

### P2: Saving could select unrelated wording — fixed

Search for “Possession”, create a template named “Costs”, save, then insert.
Previously, saving refreshed the old query and selected its first result, so
“Possession” could be inserted instead of the newly saved template. Renaming an
existing template away from the current query had the same problem.

`frontend/docweave/src/templates/dialog.ts` now searches for the saved title and
selects the saved ID after refreshing. Ordinary user searches still select their
first result. If the provider does not return the saved ID, insertion remains
disabled rather than selecting unrelated wording. Tests using the real
in-memory provider cover creation and renaming, preview selection, and insertion
with Enter.

## Simplifications applied

Search scheduling now returns `void`. Previously it returned a Promise that
could resolve before a queued search had even started; removing the unnecessary
awaits makes its scheduling role explicit. Only the function executing a provider
request remains asynchronous.

The result-clearing helper no longer resets pagination controls. Search
scheduling already invalidates them, and successful response handling sets their
new state. The request generation, pending/queued state and stale-result guard
remain separate because they handle distinct lifecycle cases.

## Validation and limits

The follow-up review found no outstanding actionable findings. Final verification
on the PR checkout passed all 119 tests, the production build and staged whitespace
checks.

Regression coverage includes immediate searches, replacement of queued queries,
stale successes and failures, pagination, closing, reopening and editing while
requests are pending, first-result selection, keyboard navigation, focus
restoration, slash insertion and undo. Controllable provider promises are used
for request-order tests; normal flows use the real in-memory provider.

Run `npm test` and `npm run build` from `frontend/docweave`. The PR is validated
on a separate checkout based on `origin/master` so unrelated local backend work
cannot conceal a dependency.

One request in flight limits concurrency, not requests per second. A server that
responds between keystrokes can still receive a request for every keystroke. A
queued query waits for the current provider request to settle; this change adds
neither a request timeout nor a result cache.
