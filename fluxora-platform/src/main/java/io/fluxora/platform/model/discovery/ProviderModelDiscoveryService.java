package io.fluxora.platform.model.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.model.ModelException;
import io.fluxora.platform.upstream.credential.ProviderCredential;
import io.fluxora.platform.upstream.credential.ProviderCredentialMapper;
import io.fluxora.platform.upstream.security.CredentialCryptoService;
import io.fluxora.platform.upstream.security.EncryptedCredential;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 上游模型发现。
 *
 * 遵循原则：
 * - 只读取当前通道已启用凭证；凭证明文仅在方法局部变量存活，不进入日志/DB/响应/前端。
 * - SSRF 防护在每次 HTTP 请求前执行；重定向目标再次校验。
 * - 支持协议：OPENAI（/models 端点）；ANTHROPIC 无公开模型列表，返回空（走手工新增）。
 * - Mock 模式用于本地演示和 Playwright 验收；生产不启用。
 */
@Service
public class ProviderModelDiscoveryService {

    private static final int CONNECT_TIMEOUT_SECONDS = 5;
    private static final int READ_TIMEOUT_SECONDS = 15;
    private static final int MAX_RESPONSE_BODY_BYTES = 1_000_000;

    private final ProviderCredentialMapper credentialMapper;
    private final CredentialCryptoService crypto;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client;

    @Value("${fluxora.model-discovery.mock-enabled:false}")
    private boolean mockEnabled;

    @Value("${fluxora.model-discovery.mock-models:gpt-4o,gpt-4o-mini,text-embedding-3-small}")
    private String mockModels;

    public ProviderModelDiscoveryService(ProviderCredentialMapper credentialMapper,
                                          CredentialCryptoService crypto) {
        this.credentialMapper = credentialMapper;
        this.crypto = crypto;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * 发现通道的上游候选列表。
     *
     * @param channelId       通道 ID
     * @param rawBaseUrl      规范化基础 URL（由调用方从 provider_base_url 读取）
     * @param protocol        协议（OPENAI / ANTHROPIC）
     * @param requestedCredentialId 指定凭证 ID（可选；null 时自动选第一个已启用凭证）
     * @return 同步结果（不含原始上游响应或凭证）
     */
    public DiscoveryResult discover(Long channelId, String rawBaseUrl, String protocol,
                                     Long requestedCredentialId) {
        if ("ANTHROPIC".equalsIgnoreCase(protocol)) {
            return new DiscoveryResult(List.of(), List.of());
        }

        // Mock 模式：不读取凭证、不发出网络请求、不执行 SSRF 校验
        if (mockEnabled) {
            // 仍需基本格式校验
            if (rawBaseUrl == null || rawBaseUrl.isBlank()) {
                throw new ModelException(BusinessErrorCode.VALIDATION_ERROR, "同步地址不能为空");
            }
            return mockDiscovery();
        }

        // 真实模式：SSRF 校验
        ModelDiscoverySsrfGuard.validate(rawBaseUrl);
        URI safeUri = URI.create(rawBaseUrl);

        // 选取凭证
        ProviderCredential credential = resolveCredential(channelId, requestedCredentialId);
        String secret = null;
        try {
            // 凭证明文仅在局部变量使用
            secret = crypto.decrypt(new EncryptedCredential(
                    credential.getCiphertext(),
                    credential.getInitializationVector(),
                    credential.getEncryptionVersion()));
            URI endpoint = buildEndpoint(safeUri);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(READ_TIMEOUT_SECONDS))
                    .header("Authorization", "Bearer " + secret)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModelException(BusinessErrorCode.VALIDATION_ERROR,
                        "模型同步失败，上游返回异常状态");
            }
            String body = response.body();
            if (body == null || body.length() > MAX_RESPONSE_BODY_BYTES) {
                throw new ModelException(BusinessErrorCode.VALIDATION_ERROR,
                        "模型同步失败，上游响应过大或为空");
            }
            return parseModels(body);
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException(BusinessErrorCode.VALIDATION_ERROR,
                    "模型同步失败，请检查通道、凭证和接入地址后重试");
        } finally {
            secret = null;
        }
    }

    private DiscoveryResult mockDiscovery() {
        List<String> ids = new ArrayList<>();
        List<SyncItemResult> failures = new ArrayList<>();
        for (String raw : mockModels.split(",", -1)) {
            String id = raw.trim();
            if (id.isBlank() || id.length() > 256) {
                failures.add(new SyncItemResult(null, "FAILED", "模型标识无效"));
            } else {
                ids.add(id);
            }
        }
        return new DiscoveryResult(ids, failures);
    }

    private ProviderCredential resolveCredential(Long channelId, Long requestedCredentialId) {
        if (requestedCredentialId != null) {
            return credentialMapper.findInternalById(requestedCredentialId)
                    .filter(c -> c.isEnabled() && channelId.equals(c.getProviderChannelId()))
                    .orElseThrow(() -> new ModelException(BusinessErrorCode.VALIDATION_ERROR,
                            "请选择当前通道可用凭证"));
        }
        return credentialMapper.findFirstEnabledInternalByChannel(channelId)
                .orElseThrow(() -> new ModelException(BusinessErrorCode.VALIDATION_ERROR,
                        "当前通道没有可用凭证，无法同步模型"));
    }

    private URI buildEndpoint(URI root) {
        String p = root.getPath() == null ? "" : root.getPath().replaceAll("/+$", "");
        return URI.create(root.getScheme() + "://" + root.getAuthority() + p + "/models");
    }

    private DiscoveryResult parseModels(String body) {
        try {
            JsonNode data = json.readTree(body).path("data");
            List<String> ids = new ArrayList<>();
            List<SyncItemResult> failures = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode n : data) {
                    String id = n.path("id").asText();
                    if (!id.isBlank() && id.length() <= 256) {
                        ids.add(id);
                    } else {
                        failures.add(new SyncItemResult(null, "FAILED",
                                "上游返回了无效模型标识"));
                    }
                }
            }
            return new DiscoveryResult(ids, failures);
        } catch (Exception e) {
            throw new ModelException(BusinessErrorCode.VALIDATION_ERROR,
                    "模型同步失败，上游响应格式异常");
        }
    }

    public record DiscoveryResult(List<String> upstreamIds, List<SyncItemResult> failures) {}
}