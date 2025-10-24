Goals:

Why this is low‑risk

Incremental — apps remain callback‑driven; you’re changing where the JSON is persisted, not how callbacks work.

HLD CCD - 4.0_7012a001702c48e58…

Reversible — the copy leaves CCD’s centralised rows intact; the routing change is a single configuration toggle.

1. Minimise risk
   2. Incremental - your app remains callback driven; you're changing where its JSON is persisted, not how callbacks work. 
   3. Reversible — the migration is non-destructive; rollback is possible with a single CCD configuration toggle.

## Migration steps

1. Service is shuttered/made readonly.
2. Case type's case_data and case_event are copied from ccd's database into the ccd.case_data, ccd.case_event schema defined in the application.
   3. A migration script is provided for this purpose ([migrate-ccd-data.sh](../scripts/migrate-ccd-data.sh))
3. Tell CCD your case type is decentralised
   4. Example: for the `NFD` case type add `ccd.decentralised.case-type-service-urls[nfd]=https://nfd-case-service.platform.hmcts.net` so CCD routes that prefix to the decentralised service.
5. Smoke test to include verification of:
   6. Case viewing for migrated cases
   7. Editing of an existing migrated case
   7. Creation of a new case
   8. Elasticsearch indexing
   9. Servicebus message publishing (if applicable)
6. Go?
   7. Unshutter
8. No-go?
   9. Rollback CCD configuration change
   10. Unshutter

Here’s a cleaned‑up, concise rewrite you can drop into your doc:

---

2. Incremental - your app remains callback driven; you're changing where its JSON is persisted, not how callbacks work.
3. Reversible — the migration is non-destructive; rollback is possible with a single CCD configuration toggle.


# Goals

**Minimise risk**

* **Incremental** — your app remains callback driven; you're changing where its JSON is persisted, not how callbacks work.
* **Reversible** — the migration is non-destructive; rollback is possible with a single CCD configuration toggle.

---

## Migration steps

1. **Shutter the service.** Pause writes and/or place the service in read‑only mode.

2. **Copy data.** For the target case type, copy `case_data` and `case_event` from CCD’s central database into the application’s `ccd.case_data` and `ccd.case_event` schema.
   Use the provided script: [migrate-ccd-data.sh](../scripts/migrate-ccd-data.sh)

3. **Switch routing to the decentralised service.** Tell CCD the case type is decentralised. Example for an `NFD` case type:

   ```
   ccd.decentralised.case-type-service-urls[nfd]=https://nfd-case-service.platform.hmcts.net
   ```

4. **Smoke test.** Verify:

   * Viewing of migrated cases
   * Editing an existing migrated case
   * Creation of a new case
   * Elasticsearch indexing
   * Service Bus message publishing (if applicable)

5. **Go / No‑go.**

   * **Go:** Unshutter the service.
   * **No‑go:** Roll back the CCD configuration change, then unshutter.
