package uk.gov.hmcts.ccd.sdk.bundling.job;

import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleExecutionContext;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleGenerationException;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleOutcome;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRenderer;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleRequest;
import uk.gov.hmcts.ccd.sdk.bundling.api.BundleResult;

/**
 * The fake renderer boundary for durable-job tests: real Postgres and real JDBC around it, this
 * only replaces the rendering pipeline. Behaviour is a queue of per-render functions; when the
 * queue is empty every render succeeds with {@link BundleJobFixtures#resultFor}.
 */
final class FakeBundleRenderer implements BundleRenderer {

  private final Queue<Function<BundleRequest, BundleResult>> behaviours =
      new ConcurrentLinkedQueue<>();
  final List<BundleRequest> renders = new CopyOnWriteArrayList<>();

  /** Queues one behaviour for the next render. */
  FakeBundleRenderer onNextRender(Function<BundleRequest, BundleResult> behaviour) {
    behaviours.add(behaviour);
    return this;
  }

  /** A behaviour that throws the given generation failure. */
  static Function<BundleRequest, BundleResult> failure(BundleGenerationException failure) {
    return request -> {
      throw failure;
    };
  }

  @Override
  public BundleResult render(BundleRequest request, BundleExecutionContext context) {
    renders.add(request);
    Function<BundleRequest, BundleResult> behaviour = behaviours.poll();
    if (behaviour == null) {
      return BundleJobFixtures.resultFor(request, BundleOutcome.COMPLETED);
    }
    return behaviour.apply(request);
  }

  @Override
  public Set<String> handledMediaTypes() {
    return Set.of("application/pdf");
  }
}
