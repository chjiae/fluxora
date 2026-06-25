package io.fluxora.gateway.relay.failure;

import io.fluxora.gateway.relay.orchestration.AttemptStateSnapshot;
import io.fluxora.gateway.relay.orchestration.RequestWriteState;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;

/** 传输层分类优先级最高，因为它能最可靠地区分“请求是否还没写出”。 */
public final class TransportFailureClassifier implements FailureClassifier {
    @Override public boolean supports(UpstreamSignal signal) { return signal.kind() == UpstreamSignal.Kind.TRANSPORT; }

    @Override
    public FailureClassification classify(UpstreamSignal signal, AttemptStateSnapshot state) {
        if (signal.transportError() instanceof SSLException) {
            return new FailureClassification(FailureKind.TLS_FAILURE, FailureScope.PROVIDER_CHANNEL,
                    ExecutionCertainty.NOT_EXECUTED, CooldownAdvice.none());
        }
        if (signal.transportError() instanceof SocketTimeoutException) {
            return new FailureClassification(FailureKind.CONNECT_TIMEOUT, FailureScope.PROVIDER_CHANNEL,
                    signal.requestWriteState() == RequestWriteState.NOT_SENT
                            ? ExecutionCertainty.NOT_EXECUTED : ExecutionCertainty.POSSIBLY_EXECUTED,
                    CooldownAdvice.none());
        }
        if (signal.requestWriteState() == RequestWriteState.NOT_SENT || signal.transportError() instanceof ConnectException) {
            return new FailureClassification(FailureKind.NETWORK_PRE_SEND_FAILURE, FailureScope.PROVIDER_CHANNEL,
                    ExecutionCertainty.NOT_EXECUTED, CooldownAdvice.none());
        }
        return new FailureClassification(FailureKind.NETWORK_PARTIAL_SEND_FAILURE, FailureScope.UNKNOWN,
                ExecutionCertainty.POSSIBLY_EXECUTED, CooldownAdvice.none());
    }
}
