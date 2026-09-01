# Order editor architecture

## Approach

Business rules will declaratively build the complete target reference document from the current inputs:

```ts
function buildCurrentOrder(inputs: OrderInputs): ProseMirrorNode {
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

It is a pure function that builds a ProseMirror target node based on inputs.

### Generated node identity

Generated paragraphs, list items, ordered lists and generated text share one
globally unique `id` attribute so the same reconciliation algorithm can handle
them. Values use node-specific namespaces, for example `paragraph:order-text`,
`ordered-list:order-clauses`, `item:give-possession` and
`generated-text:item:give-possession:deadline`. User-authored nodes have no ID
and are preserved.

Generated documents obey these invariants:

- Managed IDs are globally unique.
- An existing managed ID retains its node type and managed parent.
- Existing managed siblings retain their relative order.
- Only the document root, managed lists and managed list items may gain or lose
  direct managed children.
- The managed descendants of an existing non-container are structurally stable;
  they may be modified, but may not be added or removed.

### Editing generated clauses

Users may edit the content of generated clauses but may not delete, reparent or
reorder them. This keeps the generated document structure authoritative while
allowing wording to be tailored. ID-less user-authored clauses may still be
indented and outdented.

These rules are enforced by a transaction filter rather than individual editor
commands, so they also apply to structural changes attempted through keyboard,
toolbar, paste or drag interactions. A transaction that would remove managed
generated text is rejected for the same reason.

Reconciliation transactions are explicitly exempt from this protection because
the newly generated target may legitimately add or remove managed children at
the document root or within managed containers.

## Reconciliation

When the inputs change the buildOrder function is invoked to derive an updated target node.

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
