package io.fluxora.platform.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.fluxora.platform.upstream.credential.ProviderCredentialMapper;
import io.fluxora.platform.upstream.security.CredentialCryptoService;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

/** 开发 Mock 不读取凭证、不访问上游，同时仍执行 SSRF 地址检查。 */
class ProviderModelDiscoveryServiceTest {
    @Test void developmentMockReturnsConfiguredModelsWithoutCredentialAccess() throws Exception {
        ProviderCredentialMapper credentials = mock(ProviderCredentialMapper.class);
        CredentialCryptoService crypto = mock(CredentialCryptoService.class);
        ProviderModelDiscoveryService service = new ProviderModelDiscoveryService(credentials, crypto);
        set(service, "mockEnabled", true);
        set(service, "mockModels", "mock-chat,mock-vision,mock-chat");

        assertThat(service.discoverOpenAiModels(9L, "https://8.8.8.8/v1", null).modelIds())
                .containsExactly("mock-chat", "mock-vision", "mock-chat");
        verifyNoInteractions(credentials, crypto);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
