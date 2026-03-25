# Goal 1: Task Creation

This branch is the Task Management API spec; it contains red tests that serve as the API contract.

Our goal is to get those tests green by implementing the task creation API.

Running the e2e tests should pass. The failing test today is:
- `apiFirstTaskShouldBeCreatedViaOutboxAndPoller`

It fails because task creation is not yet implemented on the Task Management API side.

## How to run the red tests locally
Clone with submodules:
```bash
git clone --recurse-submodules git@github.com:hmcts/dtsse-ccd-config-generator.git
```

If you already cloned without submodules:
```bash
git submodule update --init --recursive
```

Run the tests:
```bash
./gradlew -i e2e:cftlibTest
```
