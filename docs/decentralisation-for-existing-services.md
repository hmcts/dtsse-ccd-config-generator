# Decentralisation for existing services

## Audience: service teams migrating existing CCD‑based applications

### Goal: enable a safe, incremental path to decentralise data ownership without a big‑bang rewrite


* You keep your existing CCD callbacks exactly as they are.

* The SDK rewires the data path around your callbacks, moving case_data and case_event out of CCD and into your service database

* Start with a JSON blob for speed; evolve to a structured schema at your own pace

* Your callbacks remain the same; this SDK handles the plumbing
