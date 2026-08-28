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

Stable, unique clause IDs identify the same logical clause.
User authored nodes are inserted by prosemirror and will lack IDs.

In v1, existing reference clauses retain their relative order. Targets may add or remove clauses, but may not reorder existing clauses.

## Reconciliation

When the inputs change the buildOrder function is invoked to derive an updated target node.

The reconciliation process then runs to update the view, diffing the existing view state with the new target node:

- Insert newly present or replace differing - reference is authoritative in v1
- Remove clauses no longer present.
- Preserve ID-less user-authored content.
