# Docweave

Docweave is a collaborative editor allowing a user to compose documents that are part machine created and part user, whilst remaining readable to both.

Try the [demo]

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

Generated documents expose a read-only logical view without exposing
ProseMirror nodes or Docweave's internal managed IDs:

```ts
const document = buildOrder((order) => {
  order.paragraph("heading", "IT IS ORDERED THAT:");
  order.orderedList("clauses", (clauses) => {
    clauses.item("suspended-condition", "The order is suspended while:", (item) => {
      item.orderedList("payment-terms", (terms) => {
        terms.item("monthly-payment", "The defendant must pay £25 each month.");
      });
    });
  });
});

document.textContent;
document.children.map((clause) => clause.textContent);
document.getClause("suspended-condition")?.textContent;
document.getClause("suspended-condition")?.children;
```

Clause IDs are exactly those supplied to `paragraph()` and `item()`. They must
be globally unique within a generated document. A clause's `textContent`
contains its own wording; direct nested clauses are available through
`children`. Documents and clauses are immutable inspection views.

Call `getSnapshot()` to persist the current and generated documents, pass that
snapshot back as `initialSnapshot` when restoring an editor, and call
`destroy()` when the editor is removed.

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

Express applications can proxy the browser route to the consuming API without
exposing user or service tokens:

```ts
import { createTemplateProxy } from "@hmcts-cft/docweave/express";

app.use("/docweave/templates", createTemplateProxy({
  upstream: "http://api/docweave/templates",
  getUserToken: request => request.session.user?.accessToken,
  getServiceToken: () => serviceTokens.getToken(),
}));
```

Apply the host application's authentication, authorization and CSRF middleware
before the proxy. The upstream is fixed in server configuration; browser input
cannot select a host or arbitrary route.

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

## Published demo

The Docweave check workflow builds the static demo on pull requests and publishes
it to [GitHub Pages](https://hmcts.github.io/dtsse-ccd-config-generator/docweave/) after
successful checks for changes on `master`. It can also be run manually on
`master`. Repository Pages settings must use **GitHub Actions** as the source.

Build the standalone demo locally with `npm run build:demo`. The output in
`dist/public` can be served by any static web server, including beneath a URL
prefix. CI places it in `dist/pages/docweave` and publishes `dist/pages` as the
Pages site, so the demo lives at `/docweave/` beneath the repository URL.
No Express or Java backend is needed: demo templates are held in browser
memory and disappear when the page reloads. The initial order date is the build
date and remains editable in the demo.
