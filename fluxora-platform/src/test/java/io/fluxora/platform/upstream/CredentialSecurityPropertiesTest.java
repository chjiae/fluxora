package io.fluxora.platform.upstream;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.fluxora.platform.upstream.security.CredentialSecurityProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 凭证安全配置绑定测试。
 * 验证开发默认值可绑定、生产环境缺失或格式错误的主密钥/去重密钥会安全拒绝启动。
 */
class CredentialSecurityPropertiesTest {

    private static final String MASTER = java.util.Base64.getEncoder()
            .encodeToString("0123456789abcdef0123456789abcdef".getBytes());
    private static final String FINGERPRINT = java.util.Base64.getEncoder()
            .encodeToString("fedcba9876543210fedcba9876543210".getBytes());

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnableProps.class);

    @EnableConfigurationProperties(CredentialSecurityProperties.class)
    static class EnableProps {
    }

    @Test
    void localDefaultsAndValidOverridesShouldBind() {
        runner.withPropertyValues(
                "fluxora.security.credential.master-key=" + MASTER,
                "fluxora.security.credential.fingerprint-key=" + FINGERPRINT)
                .run(context -> {
                    assertThat(context).hasSingleBean(CredentialSecurityProperties.class);
                    CredentialSecurityProperties props = context.getBean(CredentialSecurityProperties.class);
                    assertThat(props.masterKeyBytes()).hasSize(32);
                    assertThat(props.fingerprintKeyBytes()).hasSize(32);
                });
    }

    @Test
    void missingKeysMustFailBinding() {
        // 生产 profile 未设置环境变量时同样缺失，绑定校验安全拒绝启动
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void malformedKeyMustFailBinding() {
        runner.withPropertyValues(
                "fluxora.security.credential.master-key=not-valid-base64!!",
                "fluxora.security.credential.fingerprint-key=" + FINGERPRINT)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void wrongLengthKeyMustFailBinding() {
        // 非 32 字节密钥应被拒绝
        String shortKey = java.util.Base64.getEncoder().encodeToString("only16bytesaaaa".getBytes());
        runner.withPropertyValues(
                "fluxora.security.credential.master-key=" + shortKey,
                "fluxora.security.credential.fingerprint-key=" + FINGERPRINT)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void importMaxCountOutOfRangeMustFailBinding() {
        runner.withPropertyValues(
                "fluxora.security.credential.master-key=" + MASTER,
                "fluxora.security.credential.fingerprint-key=" + FINGERPRINT,
                "fluxora.security.credential.import-max-count=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
