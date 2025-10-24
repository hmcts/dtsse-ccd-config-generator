# Goals

**Minimise risk**

* **Incremental** — your application remains callback‑driven; we change where your case_data JSON is persisted, not how callbacks work
* **Reversible** — the migration is non-destructive and can be rolled back with a CCD configuration change

---

## Migration steps

1. **Shutter the service (or make it read-only).**

2. **Copy data.** For the target case type, copy `case_data` and `case_event` from CCD’s central database into the application’s `ccd.case_data` and `ccd.case_event` schema. 
   3. Use the provided script: [migrate-ccd-data.sh](../scripts/migrate-ccd-data.sh)

3. **Tell CCD your case type is now decentralised with an ENV var configuration change:** 

   ```
   # This tells CCD where it should go to fetch & persist cases of the 'nfd' case type
   ccd.decentralised.case-type-service-urls[nfd]=https://nfd-case-service.platform.hmcts.net
   ```

4. **Smoke test.** Verify:

   * Viewing a migrated cases
   * Editing a migrated case
   * Creation of a new case
   * Elasticsearch indexing
   * Service Bus message publishing (if applicable)

5. **Go / No‑go.**

   * **Go:** Unshutter the service.
   * **No‑go:**
     * Roll back the CCD configuration change
     * Unshutter.
