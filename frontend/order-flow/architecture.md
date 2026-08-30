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

In v1, existing reference clauses retain their relative order. Targets may add or remove clauses, but may not reorder existing clauses.

## Reconciliation

When the inputs change the buildOrder function is invoked to derive an updated target node.

The reconciliation process then runs to update the view, comparing the existing
view state, the previous target and the new target:

- Preserve edited clauses whose generated content has not changed.
- Replace an existing clause when its generated content has changed - reference is authoritative in v1.
- Update changed nested managed values without replacing unchanged surrounding content.
- Insert newly present clauses.
- Remove clauses no longer present.
- Preserve ID-less user-authored content.
