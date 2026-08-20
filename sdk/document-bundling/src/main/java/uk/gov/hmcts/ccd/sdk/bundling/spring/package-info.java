/**
 * Opt-in Spring auto-configuration and properties for the document-bundling module.
 *
 * <p>{@link uk.gov.hmcts.ccd.sdk.bundling.spring.BundlingAutoConfiguration} assembles a
 * {@link uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer} from
 * {@link uk.gov.hmcts.ccd.sdk.bundling.spring.BundlingProperties} ({@code ccd.bundling.*}) and
 * the beans the consuming service defines, and orders itself before
 * {@link uk.gov.hmcts.ccd.sdk.bundling.job.BundleJobAutoConfiguration} so the durable job
 * worker's renderer condition sees it. Nothing here is mandatory: every bean backs off to a
 * consumer-defined one, the whole configuration switches off with
 * {@code ccd.bundling.enabled=false}, and the manual
 * {@link uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer#builder()} path remains for non-Spring
 * and test use.
 */
package uk.gov.hmcts.ccd.sdk.bundling.spring;
