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
