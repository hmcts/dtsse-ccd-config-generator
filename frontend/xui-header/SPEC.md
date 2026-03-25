# XUI Header Specification

## Purpose

`frontend/xui-header` will contain a shared npm package for rendering the XUI-style
header across multiple HMCTS frontends.

The package is intended to become part of the organisation's frontend strategy.
It must:

- render consistently across services
- apply the same visibility and theming rules everywhere
- work without framework coupling
- support server-side rendering
- fit naturally into Nunjucks-based applications using GOV.UK Frontend

This document defines the intended package shape and the current XUI behaviour to
clone.

## Source of truth

The current reference implementation lives in `rpx-xui-webapp` and is Angular-based.
The files used to derive this specification are:

- `src/app/components/hmcts-global-header/hmcts-global-header.component.ts`
- `src/app/components/hmcts-global-header/hmcts-global-header.component.html`
- `src/app/components/hmcts-global-header/hmcts-global-header.component.scss`
- `src/app/containers/app-header/app-header.component.ts`
- `src/app/app-utils.ts`
- `src/environments/environment.ts`
- `api/configuration/menuConfigs/base-config.ts`
- `api/configuration/menuConfigs/aat-diffs.ts`

The new package must preserve the behaviour and visual contract of those files
without depending on Angular, NgRx, or Angular Router.

## Product shape

The package should be structured around three concerns.

### 1. Policy

Pure TypeScript functions that convert application context into a normalized header
model.

Input examples:

- user roles
- authentication state
- feature flags
- current route
- environment name
- optional header/menu configuration overrides

Output:

- a `HeaderModel` that can be rendered identically on the server and client

### 2. SSR rendering

Nunjucks macros and templates that render the header from `HeaderModel` using
GOV.UK Frontend and HMCTS visual conventions.

This is the primary rendering path.

### 3. Client enhancement

Optional browser-side JavaScript for progressive enhancement only.

This layer must not be required to decide what the user can see. The server must
be able to render the same result without waiting for client code to run.

## Non-goals

The package should not:

- depend on Angular, React, Vue, or any other framework runtime
- fetch user or feature data for itself
- own application-specific side effects such as logout dispatches
- own routing implementations
- read application stores directly

## Core design rule

The package must own shared header policy, but it must expose side effects back to
the host application as semantic events or form submissions.

Examples:

- "sign out" is a host concern
- "navigate to `/noc`" is a host concern
- "clear local search state" is a host concern
- "reset application-specific NoC state" is a host concern

The package may decide which controls appear, but the host app decides what happens
after user interaction.

## Rendering modes

The package should support two usage modes.

### High-level mode

The host application passes `HeaderContext`, and the shared policy derives the
header model.

This should be the default for estate-wide consistency.

### Low-level mode

The host application passes a precomputed `HeaderModel`.

This is useful for:

- services that already have their own entitlement layer
- tests and story fixtures
- migrations where policy and rendering are adopted separately

## SSR requirements

SSR is a hard requirement.

Implications:

- policy evaluation must be available in Node/server runtime
- rendering must be deterministic from serialized input
- server-rendered markup must be valid without client hydration
- client enhancement must preserve server-rendered structure rather than replacing
  it with a different DOM shape

The server must be able to compute:

`HeaderContext -> HeaderModel -> HTML`

## Visual fidelity requirements

Visual parity with the current XUI header is a hard requirement for the first
delivery.

The initial target is pixel-for-pixel equivalence with the reference XUI header
for agreed viewport sizes and agreed user scenarios.

This includes:

- layout
- spacing
- colours
- typography
- borders
- hover, focus, and active treatments
- judicial and non-judicial variants
- search/link variants in the right-hand area

The source reference for parity testing is the current `rpx-xui-webapp`
implementation running locally.

The package must be verified side by side against the reference implementation in
the browser, not just by reading code or comparing screenshots manually.

## Visual regression and parity testing

Implementation must include side-by-side comparison testing using Chrome MCP
against the live reference header.

Minimum expected verification flow:

1. run the reference XUI header locally
2. run the new `frontend/xui-header` implementation locally
3. open both in Chrome MCP
4. compare them side by side for agreed scenarios and viewport sizes
5. adjust until the rendered output matches the reference

The agreed source reference is the current XUI implementation in `rpx-xui-webapp`.

The initial comparison scenarios should include at least:

- default staff header
- PUI case manager header
- judicial header
- primary nav visible
- primary nav hidden
- right-side link mode
- right-side case-reference-search mode

The initial comparison viewport sizes should include:

- desktop
- a narrower mobile-sized viewport

The package should also support adding automated screenshot comparison later, but
manual side-by-side MCP verification is mandatory during initial implementation.

## Reference behaviour from XUI

## Header composition

The current XUI header has these visible regions:

1. skip link
2. top banner/header shell
3. product/service branding area
4. account navigation in the top-right
5. primary navigation bar
6. right-aligned search or search-link area
7. phase banner, outside the global header component but visually adjacent

The new package should own items 1 to 6.

The phase banner should remain a separate concern unless there is a clear platform
need to absorb it later.

## Visual variants

The current XUI header has three effective visual variants based on role matching.

### Judicial variant

- theme key: `judicial`
- background colour: `#8d0f0e`
- title text: `Judicial Case Manager`
- branding: judicial crest image plus title link
- intended audience: users matching judicial role patterns such as `judge`,
  `judiciary`, or `panelmember`

### PUI case manager variant

- theme key: `myhmcts`
- background colour: `#202020`
- title text: `Manage Cases`
- branding: MyHMCTS wordmark plus title link
- intended audience: users matching `pui-case-manager`

### Default staff variant

- theme key: `none` in current XUI, rendered as a default header treatment
- background colour: `#202020`
- title text: `Manage Cases`
- branding: service title link without the MyHMCTS wordmark in the normal path

## Current theme selection logic

Theme selection is currently regex-based over user roles.

Current defaults:

- `(judge)|(judiciary)|(panelmember)` -> judicial theme
- `pui-case-manager` -> MyHMCTS theme
- `.+` -> default theme

The shared package should preserve regex-driven matching, but the regex map should
be treated as configuration rather than hard-coded DOM logic.

## Account navigation

Current XUI shows top-right account navigation labelled `Account navigation`.

In the common authenticated path it contains one item:

- `Sign out`

The new package should support multiple account items even though the initial use
case only needs one.

## Primary navigation visibility

Current XUI hides primary navigation when the route contains:

- `accept-terms-and-conditions`
- `terms-and-conditions`

The new package should support a configurable predicate for "show primary nav"
rather than hard-coding those route strings deep inside rendering logic.

The default policy should preserve current behaviour.

## Navigation model

Navigation items are currently filtered and rendered with these properties:

- `text`
- `href`
- `active`
- `roles`
- `notRoles`
- `flags`
- `notFlags`
- `align`
- `ngClass`

For the new package:

- Angular-specific properties such as `ngClass` should be replaced by semantic
  equivalents
- host-facing config should use stable, framework-neutral names

Suggested normalized navigation item shape:

```ts
type HeaderNavItem = {
  id: string;
  text: string;
  href?: string;
  active?: boolean;
  align?: "left" | "right";
  kind?: "link" | "case-reference-search";
  classes?: string[];
  action?: string;
  roles?: string[];
  notRoles?: string[];
  flags?: HeaderFlagDefinition[];
  notFlags?: HeaderFlagDefinition[];
};
```

## Current menu filtering behaviour

The current XUI header:

- filters items in by matching any required role
- filters items out if any excluded role is present
- filters items in when all required flags match
- filters items out when excluded flags match
- splits items into left and right groups based on `align === "right"`

The package policy layer must preserve that behaviour.

## Current active-link behaviour

The current XUI tab activation rules are not a simple `href === currentPath`.

Current special cases:

- `/tasks` also matches `/tasks/list` and `/tasks/available`
- `/cases/case-search` is treated as a full match special case
- `/cases` is skipped during partial matching to avoid confusing tab selection
- when multiple partial matches exist, the longest matching href wins

The new package should preserve this behaviour in policy, not in templates.

## Current search behaviour

The right-hand side of the primary navigation behaves differently depending on user
type and feature flags.

### Case manager or global search disabled

Render a normal right-aligned link, typically "Find case".

### Non-case-manager and global search enabled

Render a dedicated case-reference search box instead of a plain link.

Current XUI treats the following roles as "case manager" for this decision:

- `pui-case-manager`
- `caseworker-ia-legalrep-solicitor`
- `caseworker-ia-homeofficeapc`
- `caseworker-ia-respondentofficer`
- `caseworker-ia-homeofficelart`
- `caseworker-ia-homeofficepou`

The new package should preserve this decision logic but expose it through clear
policy helpers rather than burying it in templates.

## Current click side effects

In XUI, clicking a primary nav item also triggers application state changes:

- clears stored search parameters
- removes search-box error decoration
- resets NoC store state when navigating to `/noc`

These side effects are application-specific and must not be embedded as direct
state mutations in the shared package.

Required replacement approach:

- render normal links/forms for baseline behaviour
- emit semantic client-side hooks only as optional enhancement
- document host responsibilities for app-specific cleanup

## Current special booking rule

When the current route contains `booking` and the user is a bookable judicial user,
XUI removes all primary navigation items.

The new package should include this as a policy rule, guarded by explicit context.

## Current menu cohorts

Current XUI menu configuration is grouped into three main cohorts.

### Judicial cohort

Potential items include:

- `My work`
- `All work`
- `Case list`
- `Find case`
- `Search`
- `Work access`
- a right-aligned 16-digit reference search entry

These are role- and flag-gated.

### PUI case manager cohort

Potential items include:

- `Case list`
- `Create case`
- `Notice of change`
- `Find case`

### Default staff cohort

Potential items include:

- `My work`
- `All work`
- `Task list`
- `Task manager`
- `Case list`
- `Create case`
- `Find case`
- `Search`
- `Refunds`
- `Staff`

These are role- and flag-gated.

## Environment-specific differences

Current XUI applies AAT/preview-specific menu differences in addition to the base
config.

The new package should support environment-aware config overlays, with the initial
behaviour cloned from:

- base config
- AAT differences

This should remain data-driven rather than compiled into templates.

## Proposed public API

## Policy input

Suggested high-level policy input:

```ts
type HeaderContext = {
  user: {
    isAuthenticated: boolean;
    roles: string[];
    roleCategory?: string;
    displayName?: string;
    bookable?: boolean;
  };
  route: {
    path: string;
  };
  features: Record<string, boolean | string>;
  environment?: "local" | "aat" | "preview" | "prod";
  config?: {
    themes?: HeaderThemeConfig[];
    menuConfig?: HeaderMenuConfig;
    accountItems?: HeaderAccountItem[];
  };
};
```

## Policy output

Suggested normalized render model:

```ts
type HeaderModel = {
  theme: {
    key: "judicial" | "myhmcts" | "default";
    backgroundColor: string;
    logo: "judicial" | "myhmcts" | "none";
  };
  title: {
    text: string;
    href: string;
  };
  skipLink: {
    text: string;
    href: string;
  };
  accountNav: {
    label: string;
    items: HeaderAccountItem[];
  };
  primaryNav: {
    visible: boolean;
    leftItems: HeaderNavItem[];
    rightItems: HeaderNavItem[];
  };
  search?: {
    mode: "link" | "case-reference";
    href?: string;
    label?: string;
    action?: string;
    name?: string;
    value?: string;
    invalid?: boolean;
    errorMessage?: string;
  };
};
```

## Nunjucks API

The package should expose a macro that accepts the normalized model rather than raw
roles and flags.

Suggested primary macro:

```njk
{% from "@hmcts/xui-header/macro.njk" import hmctsXuiHeader %}
{{ hmctsXuiHeader(model) }}
```

The package may also expose a helper for services that want policy + rendering
together, but the macro API should stay model-based.

## HTML contract

The rendered HTML should:

- use GOV.UK Frontend conventions where possible
- preserve a stable DOM structure across services
- avoid framework-only attributes
- degrade gracefully without JavaScript
- support links and forms as the baseline interaction mechanism

## Styling contract

The package should:

- provide its own minimal stylesheet for XUI-specific differences
- reuse GOV.UK Frontend utility and component classes where possible
- internalize any reused GOV.UK/HMCTS header styling so hosts do not need to
  import a leaking global stylesheet for this package to render correctly
- keep judicial red and default dark theme values configurable
- avoid inline style mutation at runtime unless necessary

The intended styling boundary is:

- the package may visually clone GOV.UK/XUI treatments
- the package should own the CSS it needs for that clone
- package styles should be scoped to the header root and must not leak into the
  host page
- host page styles should not be required to make the header look correct

## Accessibility requirements

The package must:

- render a skip link
- provide labelled account navigation
- provide labelled primary navigation
- correctly set `aria-current="page"` on active items
- support keyboard use without JavaScript
- provide accessible search error handling when the case-reference search is invalid
- avoid inaccessible click-only pseudo-links

## Package contents

The initial package should aim to contain:

- `policy/`
- `nunjucks/`
- `client/`
- `styles/`
- `SPEC.md`

Suggested future file layout:

```text
frontend/xui-header/
  package.json
  README.md
  SPEC.md
  src/
    policy/
    nunjucks/
    client/
    styles/
  test/
```

## Initial delivery plan

### Phase 1

Write the package spec and confirm the public contract.

### Phase 2

Implement pure policy functions that reproduce current XUI behaviour from config.

### Phase 3

Implement Nunjucks rendering and minimal CSS using GOV.UK Frontend-compatible
markup.

### Phase 4

Create a parity test harness so the reference XUI header and the new package can
be run side by side in the browser.

### Phase 5

Use Chrome MCP to compare both implementations side by side and iterate until the
new package matches the reference for agreed scenarios and viewport sizes.

### Phase 6

Add progressive enhancement for the case-reference search and any required client
events.

### Phase 7

Document host integration for Nunjucks services and non-Nunjucks services.

## Open design decisions

These points still need confirmation before implementation:

- final npm package name
- whether the package ships compiled CSS or source Sass as well
- whether client enhancement is plain JavaScript or TypeScript bundled output
- whether menu configuration lives inside the package or is fully injected by hosts
- whether account navigation stays generic or includes a first-class sign-out form
- whether the 16-digit search is included in the first cut or stubbed behind the
  same policy surface

## Recommended implementation direction

Recommended first implementation:

- build the policy layer first
- keep menu and theme rules data-driven
- render through Nunjucks first
- verify against the live XUI reference side by side in Chrome MCP throughout
- add client enhancement second
- treat a web component, if added later, as an enhancement surface rather than the
  primary SSR path

This preserves SSR, keeps the rules reusable, and avoids recreating the Angular
coupling of the current XUI implementation.
