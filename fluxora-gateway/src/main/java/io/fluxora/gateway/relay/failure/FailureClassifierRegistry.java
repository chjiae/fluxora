package io.fluxora.gateway.relay.failure;

import io.fluxora.gateway.relay.orchestration.AttemptStateSnapshot;
import java.util.List;

/** 固定顺序的失败分类注册表；允许注册分类器，但不允许注册“会直接执行重试”的 Handler。 */
public final class FailureClassifierRegistry {
    private final List<FailureClassifier> classifiers;

    public FailureClassifierRegistry(List<FailureClassifier> classifiers) {
        this.classifiers = List.copyOf(classifiers);
    }

    public static FailureClassifierRegistry defaultRegistry() {
        return new FailureClassifierRegistry(List.of(
                new TransportFailureClassifier(),
                new OpenAiCompatibleFailureClassifier(),
                new AnthropicFailureClassifier(),
                new GenericHttpFailureClassifier()));
    }

    public FailureClassification classify(UpstreamSignal signal, AttemptStateSnapshot state) {
        for (FailureClassifier classifier : classifiers) {
            if (classifier.supports(signal)) {
                return classifier.classify(signal, state);
            }
        }
        return FailureClassification.unknown(state.executionCertainty());
    }
}
