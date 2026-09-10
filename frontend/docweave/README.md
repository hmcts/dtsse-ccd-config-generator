# Docweave

Docweave facilitates rapid generation of documents that remain user customisable and machine-readable.

Read the [interactive documentation](https://hmcts.github.io/dtsse-ccd-config-generator/docweave/):
every code block on it runs in your browser.

## API

Build the reference document in code, then render it whenever the inputs
change. Docweave reconciles each new version with the reader's edits instead of
replacing them:

```ts
import { buildOrder, createOrderEditor } from "@hmcts-cft/docweave";
import "@hmcts-cft/docweave/styles/docweave.css";

const controller = createOrderEditor({ mount: "#editor" });

const buildMyDocument = () =>
  buildOrder((order) => {
    order.paragraph("ordered", "It is ordered that:");
    if (biscuits.checked) {
      order.paragraph("biscuits", "Biscuits shall be served.");
    }
    if (cake.checked) {
      order.paragraph("cake", "Cake shall be served.");
    }
  });

form.addEventListener("input", () => controller.render(buildMyDocument()));
controller.render(buildMyDocument());
```

Every clause has an ID, unique within the document. It is how Docweave knows the
cake clause is the same clause after biscuits are toggled, so edits inside it
survive.
Call `getSnapshot()` to persist the current and generated documents, pass that
snapshot back as `initialSnapshot` when restoring an editor, and call
`destroy()` when the editor is removed.

Omit `mount` to run the same ProseMirror state and reconciliation headlessly.
`getDocument()` returns the last `DocWeaveDocument` passed to `render()`, and
`document.textContent` uses ProseMirror's plain-text serialization with newline
block separators.

Both `import` and `require()` load the same ES module. Node.js consumers need
20.19 or later in the 20.x series, or 22.12 or later.

The compiled stylesheet is available from
`@hmcts-cft/docweave/styles/docweave.css`. Sass consumers can use
`@hmcts-cft/docweave/styles/editor`.

## Saved templates

Enable the personal template library with a same-origin endpoint and CSRF
token:

```ts
createOrderEditor({
  mount: "#editor",
  templates: {
    url: "/docweave/templates",
    csrfToken: document.querySelector<HTMLInputElement>(
      'input[name="_csrf"]',
    )?.value,
  },
});
```

## Development

```sh
npm ci
npm test
npm run build
```

Run the documentation and the court-order playground with:

```sh
npm run dev
```

The documentation is served at <http://127.0.0.1:8000> and the playground at
<http://127.0.0.1:8000/playground/>. Their sources are under `examples/docs/`
and `examples/court-order/`; library source remains under `src/`. The
documentation's code blocks live in `examples/docs/sections.ts` and are run by
the tests, so they cannot drift from the library. See
[`architecture.md`](architecture.md) for the reconciliation model and generated
document invariants.

## Installation

```sh
npm install @hmcts-cft/docweave
```
