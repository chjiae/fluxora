package io.fluxora.gateway.relay.failure;

/** Retry-After 或本地默认冷却建议；0 表示没有明确等待建议。 */
public record CooldownAdvice(long retryAfterMs) {
    public static CooldownAdvice none() { return new CooldownAdvice(0L); }
    public static CooldownAdvice retryAfter(long millis) { return new CooldownAdvice(Math.max(0L, millis)); }
}
