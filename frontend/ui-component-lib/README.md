# UI Component Library

`@hmcts-cft/cft-ui-component-lib` provides shared HMCTS header and footer
components as server-rendered nunjucks macros with scoped CSS.

The header uses declarative shadow DOM for style isolation.

## Install

Configure the HMCTS Azure Artifacts npm feed:

```ini
@hmcts-cft:registry=https://pkgs.dev.azure.com/hmcts/Artifacts/_packaging/hmcts-lib/npm/registry/
```

```bash
npm install @hmcts-cft/cft-ui-component-lib
```

## Setup

### 1. Copy assets at build time

Copy the package styles and nunjucks templates into your application's public
and views directories. The source files are at:

- `node_modules/@hmcts-cft/cft-ui-component-lib/src/styles`
- `node_modules/@hmcts-cft/cft-ui-component-lib/src/nunjucks`

For example, with CopyWebpackPlugin:

```js
const packageJson = require.resolve('@hmcts-cft/cft-ui-component-lib/package.json');
const root = path.dirname(packageJson);

new CopyWebpackPlugin({
  patterns: [
    { from: path.resolve(root, 'src', 'styles'), to: 'assets/ui-component-lib' },
    { from: path.resolve(root, 'src', 'nunjucks'), to: '../views/ui-component-lib' },
  ],
});
```

### 2. Build the models

```ts
import { buildHeaderModel, buildFooterModel } from '@hmcts-cft/cft-ui-component-lib';

const headerModel = buildHeaderModel({
  xuiBaseUrl: process.env.XUI_BASE_URL,
  environment: process.env.NODE_ENV === 'production' ? 'prod' : 'aat',
  user: { roles: ['caseworker-civil'] },
});

const footerModel = buildFooterModel();
```

### 3. Render in templates

```njk
{% from "ui-component-lib/xui-header/macro.njk" import hmctsXuiHeader %}
{% from "ui-component-lib/footer/macro.njk" import hmctsUiFooter %}
<link rel="stylesheet" href="/assets/ui-component-lib/ui-component-lib.css">

{{ hmctsXuiHeader(headerModel) }}
{{ hmctsUiFooter(footerModel) }}
```

## Header

`buildHeaderModel(context)` takes the current user and returns a complete
header model with central theme and navigation rules resolved.

The package owns the role-to-theme and role-to-XUI-link policy. In particular,
judicial users always receive the red judicial variant. Hosts supply only the
XUI URL, deployment environment and user roles. The asset mount
(`/assets/ui-component-lib`) and logout route (`/logout`) are fixed. Service banners,
language controls, current-page state and service-specific navigation remain
the responsibility of the host service.

### `HeaderContext` input

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `xuiBaseUrl` | `string` | yes | XUI root URL, used to build all nav links |
| `environment` | `'prod' \| 'aat'` | no | Selects production or non-production navigation rules; omitted uses the legacy AAT overlay |
| `user.roles` | `string[]` | yes | User roles, drives theme and menu filtering |

### Header variants

The header resolves its theme from user roles:

- **Default** — standard caseworker header
- **Judicial** — red header with judicial crest, triggered by
  judge/judiciary/panelmember roles
- **MyHMCTS** — PUI case manager variant

## Footer

`buildFooterModel()` returns the standard root-relative footer links. Pass an
XUI base URL when the links should resolve directly to XUI.

### `FooterContext` input

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `xuiBaseUrl` | `string` | no | Optional XUI root URL used to build absolute footer links |

## Consumer compatibility

From the repository root, run:

```bash
./gradlew :ui-component-lib:check
```

The Gradle check builds and packs this package, installs the tarball into a
disposable copy of the pinned `test-projects/pcs-frontend` submodule, then runs
PCS's server TypeScript build, production Webpack build and real
legal-representative page template. The submodule checkout is never modified.

For manual browser testing, start that prepared PCS application and its local
dependencies with `./gradlew :ui-component-lib:pcsConsumerRun`.

The gitlink tracks PCS frontend `master`, but remains pinned until deliberately
updated and committed:

```bash
git -C test-projects/pcs-frontend fetch origin master
git -C test-projects/pcs-frontend checkout --detach origin/master
git add test-projects/pcs-frontend
```
