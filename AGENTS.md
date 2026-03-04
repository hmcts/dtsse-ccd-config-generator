# To run all tests

./gradlew check

# To run the sdk tests

./gradlew -i e2e:check

# To run the e2e tests

./gradlew -i e2e:cftlibTest

# Logging

gradlew `e2e:cftlibtest` runs a `CallbackLoggingFilter` that captures every HTTP request/response hitting the embedded CCD stack (callbacks plus persistence endpoints) and writes JSON lines to `build/logs/http-traffic.log`. Use that file when you need to inspect payloads instead of relying on stdout.

# To run all tests

./gradlew -i allTests

# Style

Max line length 120 chars

# Dirs

/sdk - contains the CCD SDK tooling

# Architecture

See `docs/agent-architecture-overview.md` for a detailed overview of the project structure, config generation pipeline, event model, type resolution, and field prefixing. Review and update that file when making architecturally significant changes that affect any of its content.

# CCD & Architecture

Services & CCD are cyclically dependent

* ccd-data-store-api makes callbacks to services based on their ccd definitions.
* services may initiate ccd events by calling ccd-data-store-api

