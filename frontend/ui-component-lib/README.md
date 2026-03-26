# UI Component Library Developer Manual

`@hmcts/cft-ui-component-lib` is an SSR-first package for shared HMCTS shell
components. The first shipped components are the XUI-style global header and
footer, including the judicial red variant.

This manual is for application developers integrating the library into a real
service.

## What the library provides

- shared header policy in plain TypeScript
- shared footer model builder in plain TypeScript
- Nunjucks macros for SSR rendering
- package-owned CSS, fonts, images, and SVG assets
- a local preview harness for library development
- a screenshot diff helper for parity testing

The library is intentionally framework-agnostic. It does not depend on Angular,
React, Vue, NgRx, or a client router.

## Current scope

The package currently owns:

- XUI-style global header
- XUI-style footer

The package does not own:

- application page content between header and footer
- logout implementation
- route handling
- feature-flag fetching
- auth/session fetching
- application-specific state resets

## Exports

JavaScript and TypeScript exports:

- `@hmcts/cft-ui-component-lib`
- `@hmcts/cft-ui-component-lib/policy`
- `@hmcts/cft-ui-component-lib/footer`
- `@hmcts/cft-ui-component-lib/client`

Template and style exports:

- `@hmcts/cft-ui-component-lib/nunjucks/macro.njk`
- `@hmcts/cft-ui-component-lib/nunjucks/footer.njk`
- `@hmcts/cft-ui-component-lib/styles/ui-component-lib.css`

## Runtime requirements

- Node `>= 20`
- Nunjucks-compatible SSR application
- a way to serve static assets from the package CSS directory

## Install

For local development inside this repository, the PCS demo uses a file
dependency:

```json
{
  "dependencies": {
    "@hmcts/cft-ui-component-lib": "file:../../frontend/ui-component-lib"
  }
}
```

In a consuming application outside this repo, use the published npm package when
available.

## Integration model

The recommended flow is:

1. the host app collects auth, roles, flags, and route state
2. the host app builds a `HeaderModel` and `FooterModel`
3. the host app renders the Nunjucks macros on the server
4. the host app serves the library stylesheet and image/font assets

The server should be able to compute:

`context -> model -> HTML`

Do not push entitlement logic into browser-only code.

## Header API

Build a header model with `buildHeaderModel(context)`.

```ts
import { buildHeaderModel } from '@hmcts/cft-ui-component-lib';

const headerModel = buildHeaderModel({
  user: {
    isAuthenticated: true,
    roles: ['caseworker', 'caseworker-divorce', 'caseworker-divorce-judge']
  },
  route: {
    path: '/cases'
  },
  features: {},
  environment: 'local'
});
```

The main input type is `HeaderContext`.

Key fields:

- `user.isAuthenticated`
- `user.roles`
- `user.roleCategory`
- `user.bookable`
- `route.path`
- `features`
- `environment`
- `config` for local overrides

The main output type is `HeaderModel`.

Key output sections:

- `theme`
- `title`
- `skipLink`
- `accountNav`
- `primaryNav`
- `search`
- `phaseBanner`

## Footer API

Build a footer model with `buildFooterModel(context?)`.

```ts
import { buildFooterModel } from '@hmcts/cft-ui-component-lib';

const footerModel = buildFooterModel({
  termsAndConditionsFeatureEnabled: false,
  welshLanguageToggleEnabled: false
});
```

Use the footer context to control:

- footer help content
- navigation overrides
- Welsh language toggle
- terms and conditions URL selection

## Nunjucks setup

The macros live in the package `src/nunjucks` directory, so your application
must add that directory to the Nunjucks search path.

Express example:

```ts
import express from 'express';
import nunjucks from 'nunjucks';
import path from 'node:path';

const app = express();

const uiComponentLibTemplatesPath = path.dirname(
  require.resolve('@hmcts/cft-ui-component-lib/nunjucks/footer.njk')
);

nunjucks.configure(
  [
    path.join(__dirname, 'views'),
    uiComponentLibTemplatesPath
  ],
  {
    autoescape: true,
    express: app
  }
);
```

In your template:

```njk
{% from "macro.njk" import hmctsXuiHeader %}
{% from "footer.njk" import hmctsUiFooter %}
```

## Serving CSS and assets

The stylesheet, fonts, and images are package-owned and must be served by the
host application.

Express example:

```ts
import path from 'node:path';

const uiComponentLibStylesPath = path.dirname(
  require.resolve('@hmcts/cft-ui-component-lib/styles/ui-component-lib.css')
);

app.use('/assets/ui-component-lib', express.static(uiComponentLibStylesPath));
```

Then include the stylesheet:

```html
<link rel="stylesheet" href="/assets/ui-component-lib/ui-component-lib.css">
```

## Important asset-path rule

The header macro expects a render-time `assetsPath` so it can find the bundled
fonts and images, especially the judicial crest.

The package preview server defaults this to `/styles`, but a real application
should normally pass `/assets/ui-component-lib`.

Example:

```ts
const headerModel = buildHeaderModel(context);

const viewHeaderModel = {
  ...headerModel,
  assetsPath: '/assets/ui-component-lib'
};
```

Render `viewHeaderModel`, not the raw `headerModel`, when you are using the
library CSS and assets from a host app.

## Full SSR example

Route handler:

```ts
import { buildFooterModel, buildHeaderModel } from '@hmcts/cft-ui-component-lib';

app.get('/demo/ui-component-lib', (_req, res) => {
  const headerModel = buildHeaderModel({
    user: {
      isAuthenticated: true,
      roles: ['caseworker', 'caseworker-divorce', 'caseworker-divorce-judge']
    },
    route: {
      path: '/cases'
    },
    features: {},
    environment: 'local'
  });

  const footerModel = buildFooterModel({
    termsAndConditionsFeatureEnabled: false
  });

  res.render('uiComponentLibraryDemo', {
    headerModel: {
      ...headerModel,
      assetsPath: '/assets/ui-component-lib'
    },
    footerModel,
    pageTitle: 'UI component library demo',
    mainTitle: 'Case list'
  });
});
```

Template:

```njk
{% from "macro.njk" import hmctsXuiHeader %}
{% from "footer.njk" import hmctsUiFooter %}
<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>{{ pageTitle }}</title>
    <link rel="stylesheet" href="/assets/ui-component-lib/ui-component-lib.css">
  </head>
  <body>
    {{ hmctsXuiHeader(headerModel) }}
    <main id="content">
      <h1>{{ mainTitle }}</h1>
    </main>
    {{ hmctsUiFooter(footerModel) }}
  </body>
</html>
```

## Visual variants

The current header policy supports these XUI variants:

- default staff
- PUI case manager
- judicial

The judicial variant is triggered by judge-like roles and renders:

- red shell
- judicial crest
- `Judicial Case Manager`

## Host responsibilities

The host app is responsible for:

- user/session lookup
- feature-flag lookup
- route lookup
- mapping sign-out to a real URL or action
- serving the package stylesheet and assets
- deciding whether to use the high-level policy builder or a precomputed model

## Library responsibilities

The library is responsible for:

- header theme resolution
- menu filtering from roles and flags
- active-navigation state
- footer content modelling
- SSR markup
- scoped styling and owned assets
- XUI visual parity

## Local development

Build the package:

```bash
cd frontend/ui-component-lib
npm run build
```

Run the preview server:

```bash
cd frontend/ui-component-lib
npm run preview
```

That serves:

- index: `http://localhost:3100/`
- scenarios: `http://localhost:3100/scenario/<id>`

The preview server serves package assets from `/styles`. Real apps should use
their own mounted asset path instead.

## Parity workflow

Pixel parity with live XUI is a hard requirement for this package.

The expected verification flow is:

1. run the live XUI reference on `http://localhost:3000`
2. run either the package preview or a real host app consuming the package
3. open both in Chrome MCP
4. compare them side by side
5. iterate until they match

The repo also includes a screenshot diff helper:

```bash
cd frontend/ui-component-lib
npm run compare:screenshots -- <reference.png> <candidate.png> [diff.png]
```

Minimum scenarios to verify:

- default header
- judicial header
- PUI case manager header
- footer
- hover, focus, and active states for important controls

Do not rely on source-code reading alone for parity work. Use browser rendering
and screenshot diffs.

## Client enhancements

The package currently exports:

```ts
import { initUiComponentLibEnhancements } from '@hmcts/cft-ui-component-lib/client';
```

This is a placeholder for future progressive enhancement. It is not required for
the current SSR header and footer output.

## Design and behaviour reference

If you need the full behaviour and parity requirements, read:

- [SPEC.md](./SPEC.md)

`SPEC.md` is the detailed implementation contract. This README is the practical
integration manual.
