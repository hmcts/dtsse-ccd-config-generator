# Docweave

Docweave is a TypeScript library for building generated court orders in a
ProseMirror editor while preserving compatible user edits when source data
changes.

## Installation

```sh
npm install @hmcts-cft/docweave
```

## Public API

Build a document from typed application data, create an editor, and render each
new generated version through the controller:

```ts
import {
  buildOrder,
  createOrderEditor,
} from "@hmcts-cft/docweave";
import "@hmcts-cft/docweave/styles/docweave.css";

const controller = createOrderEditor({ mount: "#editor" });

controller.render(buildOrder((order) => {
  order.paragraph("heading", "IT IS ORDERED THAT:");
  order.orderedList("clauses", (clauses) => {
    clauses.item("possession", (content) => {
      content
        .text("The defendant must give possession by ")
        .fact("date", "6 September 2026", { sourceId: "possession-date" })
        .text(".");
    });
  });
}));
```

Call `getSnapshot()` to persist the current and generated documents, pass that
snapshot back as `initialSnapshot` when restoring an editor, and call
`destroy()` when the editor is removed.

The compiled stylesheet is available from
`@hmcts-cft/docweave/styles/docweave.css`. Sass consumers can use
`@hmcts-cft/docweave/styles/editor`.

## Development

```sh
npm ci
npm test
npm run build
```

Run the court-order example with:

```sh
npm run dev
```

The demo is served at <http://127.0.0.1:8000>. Its source is under
`examples/court-order/`; library source remains under `src/`. See
[`architecture.md`](architecture.md) for the reconciliation model and generated
document invariants.
