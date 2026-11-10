package interview.guide.modules.usercredential.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.usercredential.dto.CredentialTestResultDTO;
import interview.guide.modules.usercredential.dto.TestCredentialRequest;
import interview.guide.modules.usercredential.service.UserCredentialService.ProviderSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 连接测试服务：必须是真连通性验证，且绝不泄漏凭证。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("模型连接测试")
class CredentialConnectionTestServiceTest {

  private static final Long USER_ID = 1L;
  private static final String PROVIDER = "deepseek";
  private static final String MODEL = "deepseek-chat";
  private static final String API_KEY = "sk-verysecretkey-0123456789";

  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private UserCredentialService userCredentialService;

  private CredentialConnectionTestService service;

  @BeforeEach
  void setUp() {
    service = new CredentialConnectionTestService(llmProviderRegistry, userCredentialService);
  }

  @Test
  @DisplayName("测试成功时返回真实延迟与模型信息，且不包含 API Key")
  void successResultHasNoSecret() {
    when(userCredentialService.openChatSecret(USER_ID, PROVIDER))
        .thenReturn(new ProviderSecret(PROVIDER, "https://api.deepseek.com", MODEL, API_KEY, 1L));
    ChatClient client = mockChatClientReturning("OK");
    when(llmProviderRegistry.buildEphemeralChatClient(eq(PROVIDER), anyString(), anyString(), anyString()))
        .thenReturn(client);

    CredentialTestResultDTO result = service.testSaved(USER_ID, PROVIDER);

    assertThat(result.success()).isTrue();
    assertThat(result.modelName()).isEqualTo(MODEL);
    assertThat(result.message()).doesNotContain(API_KEY).doesNotContain("sk-");
    // 必须真实发起一次模型请求
    verify(client).prompt();
  }

  @Test
  @DisplayName("供应商认证失败时返回明确错误码，且错误信息已脱敏")
  void authFailureIsClassifiedAndRedacted() {
    when(userCredentialService.openChatSecret(USER_ID, PROVIDER))
        .thenReturn(new ProviderSecret(PROVIDER, "https://api.deepseek.com", MODEL, API_KEY, 1L));
    when(llmProviderRegistry.buildEphemeralChatClient(eq(PROVIDER), anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("401 Unauthorized: invalid api key " + API_KEY));

    CredentialTestResultDTO result = service.testSaved(USER_ID, PROVIDER);

    assertThat(result.success()).isFalse();
    assertThat(result.errorCode())
        .isEqualTo(String.valueOf(ErrorCode.PROVIDER_AUTHENTICATION_FAILED.getCode()));
    assertThat(result.message()).doesNotContain(API_KEY);
    assertThat(result.message()).contains("认证失败");
  }

  @Test
  @DisplayName("通用错误信息中的疑似密钥片段会被脱敏")
  void genericErrorIsRedacted() {
    when(userCredentialService.openChatSecret(USER_ID, PROVIDER))
        .thenReturn(new ProviderSecret(PROVIDER, "https://api.deepseek.com", MODEL, API_KEY, 1L));
    when(llmProviderRegistry.buildEphemeralChatClient(eq(PROVIDER), anyString(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("connect failed with token sk-abcdefghijklmnop"));

    CredentialTestResultDTO result = service.testSaved(USER_ID, PROVIDER);

    assertThat(result.success()).isFalse();
    assertThat(result.message()).doesNotContain("sk-abcdefghijklmnop");
    assertThat(result.message()).contains("***");
  }

  @Test
  @DisplayName("未配置凭证时返回配置错误，不会使用平台全局 Key")
  void notConfiguredFailsFast() {
    when(userCredentialService.openChatSecret(USER_ID, PROVIDER))
        .thenThrow(new BusinessException(ErrorCode.USER_CREDENTIAL_NOT_CONFIGURED));

    CredentialTestResultDTO result = service.testSaved(USER_ID, PROVIDER);

    assertThat(result.success()).isFalse();
    assertThat(result.errorCode())
        .isEqualTo(String.valueOf(ErrorCode.USER_CREDENTIAL_NOT_CONFIGURED.getCode()));
  }

  @Nested
  @DisplayName("临时凭证测试")
  class Transient {

    @Test
    @DisplayName("使用未保存的临时凭证做真实测试，且不写入数据库")
    void transientProbeUsesRequestKey() {
      when(userCredentialService.openTransientSecret(eq(PROVIDER), eq(API_KEY)))
          .thenReturn(new ProviderSecret(PROVIDER, "https://api.deepseek.com", MODEL, API_KEY, 0L));
      ChatClient client = mockChatClientReturning("OK");
      when(llmProviderRegistry.buildEphemeralChatClient(eq(PROVIDER), anyString(), anyString(), anyString()))
          .thenReturn(client);

      CredentialTestResultDTO result = service.testTransient(USER_ID,
          new TestCredentialRequest(PROVIDER, API_KEY, MODEL));

      assertThat(result.success()).isTrue();
      verify(userCredentialService).openTransientSecret(PROVIDER, API_KEY);
      // 临时凭证不会走任何持久化方法
      verify(userCredentialService, org.mockito.Mockito.never())
          .save(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("请求对象的 toString 不暴露 API Key")
    void requestToStringIsMasked() {
      TestCredentialRequest request = new TestCredentialRequest(PROVIDER, API_KEY, MODEL);
      assertThat(request.toString()).doesNotContain(API_KEY).contains("***");
    }
  }

  private ChatClient mockChatClientReturning(String content) {
    ChatClient client = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
    when(client.prompt()).thenReturn(spec);
    when(spec.user(anyString())).thenReturn(spec);
    when(spec.call()).thenReturn(callSpec);
    when(callSpec.content()).thenReturn(content);
    return client;
  }
}
