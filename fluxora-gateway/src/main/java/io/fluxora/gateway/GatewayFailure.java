package io.fluxora.gateway;

/** 仅在 Gateway 内部流转的失败分类；HTTP 层只映射为安全中文文案。 */
public final class GatewayFailure extends RuntimeException {
    public enum Type { INVALID_REQUEST, INVALID_API_KEY, ACCOUNT_UNAVAILABLE, RUNTIME_UNAVAILABLE, MODEL_UNAVAILABLE,
        INSUFFICIENT_BALANCE, UNSUPPORTED }

    private final Type type;

    private GatewayFailure(Type type) {
        super(type.name(), null, false, false);
        this.type = type;
    }

    public Type type() { return type; }
    public static GatewayFailure invalidRequest() { return new GatewayFailure(Type.INVALID_REQUEST); }
    public static GatewayFailure invalidApiKey() { return new GatewayFailure(Type.INVALID_API_KEY); }
    public static GatewayFailure accountUnavailable() { return new GatewayFailure(Type.ACCOUNT_UNAVAILABLE); }
    public static GatewayFailure runtimeUnavailable() { return new GatewayFailure(Type.RUNTIME_UNAVAILABLE); }
    public static GatewayFailure modelUnavailable() { return new GatewayFailure(Type.MODEL_UNAVAILABLE); }
    public static GatewayFailure insufficientBalance() { return new GatewayFailure(Type.INSUFFICIENT_BALANCE); }
    public static GatewayFailure unsupported() { return new GatewayFailure(Type.UNSUPPORTED); }
}
