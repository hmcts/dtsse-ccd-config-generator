# Editor architecture

## Approach

Business rules declaratively build a `DocWeaveDocument` from the current
inputs. It exposes an immutable logical clause view for consumers while
Docweave privately retains the ProseMirror target node and runtime-only
interaction metadata:

```ts
function buildCurrentDocument(inputs: Inputs): DocWeaveDocument {
  return buildDoc((doc) => {
    doc.paragraph("heading", "IT IS ORDERED THAT:");

    if (inputs.includeFoo) {
      doc.paragraph("foo", buildRichText(inputs));
    }

    doc.orderedList("clauses", (list) => {
      list.item("parent", "Parent clause", (item) => {
        item.orderedList("subclauses", (subclauses) => {
          subclauses.item("first-subclause", "First subclause");
        });
      });
    });
  });
}

controller.render(buildCurrentDocument(inputs));
```

It is a pure function that builds a ProseMirror target node and its ephemeral
interaction metadata based on the inputs.

### Read-only inspection

Consumers inspect generated wording without traversing ProseMirror nodes:

```ts
document.textContent;
document.children.map((clause) => clause.textContent);
document.getClause("parent")?.textContent;
document.getClause("parent")?.children;
```

Paragraphs and list items are logical clauses. Their public IDs are exactly the
IDs supplied to `paragraph()` and `item()`, and must be globally unique within
the document. Ordered-list containers are omitted from the public hierarchy:
top-level list items appear in `document.children`, and nested list items appear
in their parent clause's `children`.

A clause's `textContent` contains its own wording and excludes nested clauses.
`document.textContent` contains the wording of the complete generated target,
using ProseMirror's plain-text serialization with newline block separators.
The document, clause objects and child arrays are immutable, and repeated
`getClause()` calls return the same clause view.

### Facts and source controls

Immutable generated values are declared as facts. A fact may identify the DOM
element that supplies its value:

```ts
content
  .text("The defence must be filed by ")
  .fact("defence-date", inputs.defenceDate, {
    sourceId: "adj-defence-date",
  });
```

The builder records source IDs beside the ProseMirror node in the opaque
`DocWeaveDocument`; they are not node attributes. The editor uses this sidecar
metadata to make a fact navigate to its source control. A composite source
scrolls into view and its first enabled form control receives focus.

Editor state is persisted separately as a `DocWeaveSnapshot`, obtained with
`controller.getSnapshot()` and restored with `initialSnapshot`. Snapshot JSON
contains only the current and generated ProseMirror documents, never source
control IDs.

A snapshot is opaque to services: they store it and hand it back, and ask
Docweave about it rather than reading its JSON. `renderHtml(snapshot)` renders
the reader's document as the final HTML. `describeChanges(snapshot)` counts the
clauses the reader wrote and the generated clauses they changed, in wording or
formatting, so a service can tell someone reviewing it what changed; it needs
no DOM, so it runs on a server. `renderHtml(snapshot, { changes: true })` marks
those clauses as the editor marks them for the reader: each carries the
editor's `docweave-editor__clause--inserted` or `--modified` class and the
words the editor says before it for a screen reader, so the editor's
stylesheet shows someone else, statically, what the reader saw. A clause is
the same unit the editor marks and reverts, described under
[Editing generated clauses](#editing-generated-clauses).

### Generated node identity

Internally, generated paragraphs, list items, ordered lists and generated text
share one globally unique managed `id` attribute so the same reconciliation
algorithm can handle them. These private values use node-specific namespaces,
for example `paragraph:order-text`, `ordered-list:order-clauses`,
`item:give-possession` and
`generated-text:item:give-possession:deadline`. They are distinct from the
prefix-free public clause IDs. User-authored nodes have no managed ID and are
preserved while the generated clause or top-level list containing them remains.

Generated documents obey these invariants:

- Managed IDs are globally unique.
- An existing managed ID retains its node type and managed parent.
- Existing managed siblings retain their relative order.
- Only the document root, managed lists and managed list items may gain or lose
  direct managed children.
- The managed descendants of an existing non-container are structurally stable;
  they may be modified, but may not be added or removed.

ProseMirror's schema validates every generated and restored document.
`buildDoc` additionally rejects duplicate managed IDs. Before reconciliation,
the editor validates both generated documents and rejects any transition that
violates the remaining identity and structure invariants. Restored documents
are also checked against their generated baseline before the editor is created.

### Accessibility

The editor is used by judges, some of whom read with a screen reader or work
from the keyboard alone, so nothing the editor shows only in colour or only to
the mouse is the whole story:

- The ProseMirror surface is exposed as a named multiline text box; the name
  comes from the `label` editor option.
- A fact with a source control is a link with the role description "generated
  field", and its `aria-details` names the source control so a screen reader
  can read the control's label without leaving the document. Its
  `aria-description` is the field's name: the fact's `label` option, or else
  the label or fieldset legend of its source control on the page (a radio or
  checkbox is named after its legend, not its own option label).
- Alt+Shift+Down and Alt+Shift+Up select the next or previous fact, wrapping
  round, and announce its name, value and position ("Field 2 of 5").
- Each `render()` after the first reports which facts changed value, and the
  editor announces it: "Order updated: Possession deadline is now 2 October
  2026.
- Going to a source control offers a way back: a "Return to document" button
  inserted after the whole control (its fieldset, group or GOV.UK form group,
  so a radio keeps its label beside it), and Mod+Alt+D anywhere on the page. Either
  reselects the fact by its managed ID, which reconciliation preserves, so the
  reader lands on the same field with its new value announced.
- Inserted and modified clauses carry a visually hidden marker ("Inserted
  clause.", "Modified clause.") before their wording, beside the gutter button
  that reverts them. The button works from the keyboard, and the shortcut
  Mod+Alt+Z reverts the clause at the cursor without tabbing to it.
- A polite live region under the surface announces an edit the invariants
  refused, and a clause that was reverted.
- Tab and Shift+Tab indent and outdent a numbered clause the reader added, and
  a refused outdent there is announced. Anywhere else a move is refused or
  there is nothing to indent beneath, including every generated clause, the
  editor leaves them to the browser, so Tab always moves on to the next control
  and the document is never a keyboard trap.
- The toolbar follows the toolbar pattern: one Tab stop, arrow keys between
  buttons, Alt+F10 to reach it from the document. Alt+0 opens a dialog listing
  every shortcut.

The interactive documentation runs axe-core over every section with its editor
mounted, so a regression in the markup fails the build.

### Editing generated clauses

Users may edit the content of generated clauses but may not delete, reparent or
reorder them. This keeps the generated document structure authoritative while
allowing wording to be tailored. ID-less user-authored clauses may still be
indented and outdented.

These rules are enforced by a transaction filter rather than individual editor
commands, so they also apply to structural changes attempted through keyboard,
toolbar, paste or drag interactions. An ordinary transaction must retain the
exact managed ID set, node types, parents and sibling positions. A transaction
that would add managed content or remove managed generated text is rejected for
the same reason.

Reconciliation transactions are explicitly exempt from this protection because
the newly generated target may legitimately add or remove managed children at
the document root or within managed containers.

## Reconciliation

When the inputs change, `buildDoc` derives an updated `DocWeaveDocument`.

The reconciliation process then runs to update the view, comparing the existing
view state, the previous target and the new target:

- Replace a generated clause's ordinary content when its newly built reference
  wording differs from the previous target.
- Otherwise, preserve direct edits to the clause's ordinary content while
  updating its managed descendants.
- Insert and remove managed children at the document root and within managed
  containers.
- Preserve user-authored content where it belongs to clauses still present in
  the document.

Removing a generated clause also removes any user-authored content inside it.
Removing a top-level generated list removes the whole list, including independent
clauses the reader added between its generated items. This also happens when its
last generated item is removed: the builder omits the now-empty generated list.
This is intentional; the reader's additions belong to that part of the document.
Reconciliation is not an undoable edit, so neither Undo nor generating that list
again restores the removed wording. Applications should keep a generated item in
the list if the reader's additions must survive changes to its optional items.

When only a nested generated list is removed and its parent clause remains,
user-authored subclauses remain under that parent in an unmanaged list.

## Template search

The HTTP template provider extracts wording from the saved content and sends it
in the backend's `searchableText` field on create and update. Formatting within a
word does not split it, block boundaries separate words, and unresolved dates are
left out. The backend combines that wording with the title and tags.

Templates saved by older clients without searchable wording need to be saved
again before searches can find them by their wording.
