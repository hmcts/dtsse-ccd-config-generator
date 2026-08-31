# Order editor architecture

## Approach

Business rules will declaratively build the complete target reference document from the current inputs:

```ts
function buildOrder(inputs: OrderInputs): ProseMirrorNode {
  const order = createOrderBuilder();

  order.clause("heading", "IT IS ORDERED THAT:");

  if (inputs.includeFoo) {
    order.clause("foo", buildRichText(inputs));
  }

  return order.build();
}

controller.render(buildOrder(inputs));
```

It is a pure function that builds a ProseMirror target node based on inputs.

### Generated node identity

Generated clauses and containers share one globally unique `id` attribute so
the same reconciliation algorithm can handle both. Values are namespaced, for
example `clause:order-text` and `container:order-clauses`. User-authored nodes
have no ID and are preserved.

Generated documents obey these invariants:

- Managed IDs are globally unique.
- An existing managed ID retains its node type and managed parent.
- Existing managed siblings retain their relative order.
- Only the document root and managed containers may gain or lose direct managed
  children.
- The managed descendants of an existing non-container are structurally stable;
  they may be modified, but may not be added or removed.

### Editing generated clauses

Users may edit the content of generated clauses but may not delete, reparent or
reorder them. This keeps the generated document structure authoritative while
allowing wording to be tailored. ID-less user-authored clauses may still be
indented and outdented.

These rules are enforced by a transaction filter rather than individual editor
commands, so they also apply to structural changes attempted through keyboard,
toolbar, paste or drag interactions. A transaction that would remove a managed
form value is rejected for the same reason.

Reconciliation transactions are explicitly exempt from this protection because
the newly generated target may legitimately add or remove managed children at
the document root or within managed containers.

## Reconciliation

When the inputs change the buildOrder function is invoked to derive an updated target node.

The reconciliation process then runs to update the view, comparing the existing
view state, the previous target and the new target:

- Replace a generated clause wholesale when the user has not edited it.
- Preserve an edited clause's ordinary content while updating its existing
  managed descendants.
- Insert and remove managed children at the document root and within managed
  containers.
- Preserve ID-less user-authored content.
