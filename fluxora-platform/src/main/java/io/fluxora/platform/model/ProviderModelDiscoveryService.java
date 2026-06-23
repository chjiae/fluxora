package io.fluxora.platform.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fluxora.common.error.BusinessErrorCode;
import io.fluxora.platform.upstream.credential.ProviderCredential;
import io.fluxora.platform.upstream.credential.ProviderCredentialMapper;
import io.fluxora.platform.upstream.provider.ProviderException;
import io.fluxora.platform.upstream.security.CredentialCryptoService;
import io.fluxora.platform.upstream.security.EncryptedCredential;
import io.fluxora.platform.upstream.security.ModelDiscoverySsrfGuard;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

/** OpenAI 协议模型发现；请求不跟随重定向，响应限量，凭证明文仅存活在本方法局部变量。 */
@Service
public class ProviderModelDiscoveryService {
 private final ProviderCredentialMapper credentialMapper; private final CredentialCryptoService crypto; private final ObjectMapper json=new ObjectMapper();
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 @Value("${fluxora.model-discovery.mock-enabled:false}") private boolean mockEnabled;
 @Value("${fluxora.model-discovery.mock-models:gpt-4o,gpt-4o-mini,text-embedding-3-small}") private String mockModels;
 public ProviderModelDiscoveryService(ProviderCredentialMapper credentialMapper,CredentialCryptoService crypto){this.credentialMapper=credentialMapper;this.crypto=crypto;}
 @Transactional(readOnly=true) public ModelDiscoveryResult discoverOpenAiModels(Long channelId,String baseUrl,Long requestedCredentialId){
  URI root=ModelDiscoverySsrfGuard.validate(baseUrl);
  // 开发 Mock 仍执行 URL 安全校验，但不读取凭证、不发出网络请求，便于本地端到端验收。
  if(mockEnabled){List<String> ids=new ArrayList<>();List<SyncItemResult> failures=new ArrayList<>();for(String raw:mockModels.split(",",-1)){String id=raw.trim();if(id.isBlank()||id.length()>256)failures.add(new SyncItemResult(null,"FAILED","模型标识为空或超过 256 个字符"));else ids.add(id);}return new ModelDiscoveryResult(ids,failures);}
  ProviderCredential credential=requestedCredentialId==null?credentialMapper.findFirstEnabledInternalByChannel(channelId).orElseThrow(()->new ProviderException(BusinessErrorCode.VALIDATION_ERROR,"当前通道没有可用凭证，无法同步模型")):credentialMapper.findInternalById(requestedCredentialId).filter(c->c.isEnabled()&&channelId.equals(c.getProviderChannelId())).orElseThrow(()->new ProviderException(BusinessErrorCode.VALIDATION_ERROR,"请选择当前通道可用凭证"));
  String path=root.getPath()==null?"":root.getPath(); URI endpoint=URI.create(root.getScheme()+"://"+root.getAuthority()+(path.endsWith("/")?path:path+"/")+"models");
  String secret=crypto.decrypt(new EncryptedCredential(credential.getCiphertext(),credential.getInitializationVector(),credential.getEncryptionVersion()));
  try { HttpRequest request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+secret).header("Accept","application/json").GET().build(); HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8)); if(response.statusCode()<200||response.statusCode()>=300||response.body().length()>1_000_000)throw new ProviderException(BusinessErrorCode.VALIDATION_ERROR,"模型同步失败，请检查通道、凭证和接入地址后重试"); JsonNode data=json.readTree(response.body()).path("data"); List<String> ids=new ArrayList<>();List<SyncItemResult> failures=new ArrayList<>(); if(data.isArray())for(JsonNode n:data){String id=n.path("id").asText();if(!id.isBlank()&&id.length()<=256)ids.add(id);else failures.add(new SyncItemResult(null,"FAILED","上游返回了无效模型标识"));} return new ModelDiscoveryResult(ids,failures); }
  catch(ProviderException e){throw e;} catch(Exception e){throw new ProviderException(BusinessErrorCode.VALIDATION_ERROR,"模型同步失败，请检查通道、凭证和接入地址后重试");}
  finally { secret=null; }
 }
}
