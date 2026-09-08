# Order editor architecture

## Approach

Business rules declaratively build a `DocWeaveDocument` from the current
inputs. It exposes an immutable logical clause view for consumers while
Docweave privately retains the ProseMirror target node and runtime-only
interaction metadata:

```ts
function buildCurrentOrder(inputs: OrderInputs): DocWeaveDocument {
  return buildOrder((order) => {
    order.paragraph("heading", "IT IS ORDERED THAT:");

    if (inputs.includeFoo) {
      order.paragraph("foo", buildRichText(inputs));
    }

    order.orderedList("clauses", (list) => {
      list.item("parent", "Parent clause", (item) => {
        item.orderedList("subclauses", (subclauses) => {
          subclauses.item("first-subclause", "First subclause");
        });
      });
    });
  });
}

controller.render(buildCurrentOrder(inputs));
```

It is a pure function that builds a ProseMirror target node and its ephemeral
interaction metadata based on the inputs.

### Read-only inspection

Consumers inspect generated wording without traversing ProseMirror nodes:

```ts
document.textContent;
document.toText();
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
`document.textContent` contains the wording of the complete generated target.
`document.toText()` uses ProseMirror's text serialization and separates blocks
with newlines.
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

### Generated node identity

Internally, generated paragraphs, list items, ordered lists and generated text
share one globally unique managed `id` attribute so the same reconciliation
algorithm can handle them. These private values use node-specific namespaces,
for example `paragraph:order-text`, `ordered-list:order-clauses`,
`item:give-possession` and
`generated-text:item:give-possession:deadline`. They are distinct from the
prefix-free public clause IDs. User-authored nodes have no managed ID and are
preserved.

Generated documents obey these invariants:

- Managed IDs are globally unique.
- An existing managed ID retains its node type and managed parent.
- Existing managed siblings retain their relative order.
- Only the document root, managed lists and managed list items may gain or lose
  direct managed children.
- The managed descendants of an existing non-container are structurally stable;
  they may be modified, but may not be added or removed.

ProseMirror's schema validates every generated and restored document.
`buildOrder` additionally rejects duplicate managed IDs. Before reconciliation,
the editor validates both generated documents and rejects any transition that
violates the remaining identity and structure invariants. Restored documents
are also checked against their generated baseline before the editor is created.

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

When the inputs change, `buildOrder` derives an updated `DocWeaveDocument`.

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
