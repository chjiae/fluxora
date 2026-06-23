package io.fluxora.platform.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 运行时投影的集中配置；Gateway 不读取本类，也不共享控制面连接信息。 */
@ConfigurationProperties(prefix = "fluxora.runtime")
public class RuntimeProperties {
    private boolean enabled = true;
    private int projectorBatchSize = 100;
    private long projectorDelayMs = 500L;
    private long timeScanDelayMs = 30_000L;
    private long recoveryDelayMs = 30_000L;
    private String invalidationChannel = "fluxora:runtime:v1:invalidation";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getProjectorBatchSize() { return projectorBatchSize; }
    public void setProjectorBatchSize(int projectorBatchSize) { this.projectorBatchSize = projectorBatchSize; }
    public long getProjectorDelayMs() { return projectorDelayMs; }
    public void setProjectorDelayMs(long projectorDelayMs) { this.projectorDelayMs = projectorDelayMs; }
    public long getTimeScanDelayMs() { return timeScanDelayMs; }
    public void setTimeScanDelayMs(long timeScanDelayMs) { this.timeScanDelayMs = timeScanDelayMs; }
    public long getRecoveryDelayMs() { return recoveryDelayMs; }
    public void setRecoveryDelayMs(long recoveryDelayMs) { this.recoveryDelayMs = recoveryDelayMs; }
    public String getInvalidationChannel() { return invalidationChannel; }
    public void setInvalidationChannel(String invalidationChannel) { this.invalidationChannel = invalidationChannel; }
}
