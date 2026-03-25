# XUI Header Package

`frontend/xui-header` is the start of a shared frontend package for rendering the
XUI-style header across HMCTS services.

The package is intended to:

- preserve current XUI behaviour and visual output
- support SSR-first rendering through Nunjucks
- keep header policy framework-agnostic
- own its styling boundary even where GOV.UK/XUI visual patterns are reused
- own any fonts, icons, and images needed to hit parity
- enable side-by-side parity testing against the live XUI reference

## Current status

This package is in early scaffolding.

Implemented so far:

- package specification in [SPEC.md](./SPEC.md)
- initial TypeScript package structure
- initial policy types
- initial theme and navigation helper functions
- initial SSR macro
- local preview server for rendering package scenarios side by side
- local screenshot-diff helper for parity checks against captured reference images

Not implemented yet:

- Nunjucks macro output
- full XUI menu config port
- case-reference-search rendering
- parity harness
- MCP side-by-side comparison workflow

## Planned layout

```text
frontend/xui-header/
  package.json
  tsconfig.json
  README.md
  SPEC.md
  src/
    client/
    nunjucks/
    policy/
    styles/
```
