package interview.guide.modules.usercredential.service;

import interview.guide.common.ai.AiExecutionContext;
import interview.guide.common.ai.UserCredentialChangedEvent;
import interview.guide.common.config.ByokProperties;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.CurrentUserProvider;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import interview.guide.modules.usercredential.dto.CredentialStatusDTO;
import interview.guide.modules.usercredential.dto.SaveCredentialRequest;
import interview.guide.modules.usercredential.dto.UserCredentialDTO;
import interview.guide.modules.usercredential.model.CredentialStatus;
import interview.guide.modules.usercredential.model.AiCapability;
import interview.guide.modules.usercredential.repository.UserCapabilityRepository;
import interview.guide.modules.usercredential.model.UserLlmCredentialEntity;
import interview.guide.modules.usercredential.repository.UserLlmCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户级模型上下文解析规则验证（Chat）。
 *
 * <p>这是 BYOK 阶段二「不允许回退平台全局 Key」的核心行为测试。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户级 AI 上下文解析")
class AiCredentialResolutionTest {

  private static final Long USER_ID = 7L;
  private static final String DEEPSEEK = "deepseek";
  private static final String DASHSCOPE = "dashscope";

  @Mock
  private UserLlmCredentialRepository repository;
  @Mock
  private ApiKeyEncryptionService encryptionService;
  @Mock
  private CurrentUserProvider currentUserProvider;
  @Mock
  private ApplicationEventPublisher eventPublisher;

  @Mock
  private UserCapabilityRepository capabilityRepository;

  private UserCredentialService service;
  private final Map<String, UserLlmCredentialEntity> store = new HashMap<>();

  @BeforeEach
  void setUp() {
    ByokProperties byok = new ByokProperties();
    LlmProviderProperties llm = new LlmProviderProperties();
    llm.setDefaultProvider(DASHSCOPE);

    Map<String, LlmProviderProperties.ProviderConfig> providers = new HashMap<>();
    providers.put(DEEPSEEK, provider("https://api.deepseek.com", "deepseek-chat"));
    providers.put(DASHSCOPE, provider("https://dashscope.aliyuncs.com/compatible-mode", "qwen-plus"));
    providers.put("glm", provider("https://open.bigmodel.cn/api/paas/v4", "glm-4"));
    llm.setProviders(providers);

    service = new UserCredentialService(repository, encryptionService,
        new ProviderPresetRegistry(byok, llm), byok, capabilityRepository, currentUserProvider, eventPublisher);

    // 简单的内存仓储行为
    org.mockito.Mockito.lenient().when(repository.findByUserIdAndProviderKey(any(), any()))
        .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(1))));
    org.mockito.Mockito.lenient().when(repository.findAllByUserIdOrderByUpdatedAtDesc(any()))
        .thenAnswer(inv -> new ArrayList<>(store.values()));
    org.mockito.Mockito.lenient().when(repository.findByUserIdAndChatEnabledTrueOrderByUpdatedAtDesc(any()))
        .thenAnswer(inv -> store.values().stream()
            .filter(e -> Boolean.TRUE.equals(e.getChatEnabled())).toList());
    org.mockito.Mockito.lenient().when(repository.save(any())).thenAnswer(inv -> {
      UserLlmCredentialEntity e = inv.getArgument(0);
      if (e.getUpdatedAt() == null) {
        e.setUpdatedAt(LocalDateTime.now());
      }
      if (e.getId() == null) {
        e.setId((long) store.size() + 1);
      }
      store.put(e.getProviderKey(), e);
      return e;
    });
    org.mockito.Mockito.lenient().when(encryptionService.encrypt(any()))
        .thenAnswer(inv -> new ApiKeyEncryptionService.EncryptedValue("cipher", "nonce"));
    org.mockito.Mockito.lenient().when(encryptionService.decrypt(any(), any())).thenReturn("sk-plaintext-key");
  }

  private UserCapabilityRepository.Selection selection(String key) {
    var entity = store.get(key);
    return new UserCapabilityRepository.Selection(key, entity.getId(), entity.getModelName());
  }

  private LlmProviderProperties.ProviderConfig provider(String baseUrl, String model) {
    LlmProviderProperties.ProviderConfig config = new LlmProviderProperties.ProviderConfig();
    config.setBaseUrl(baseUrl);
    config.setModel(model);
    return config;
  }

  @Test
  @DisplayName("没有任何凭证时 Chat 上下文解析失败，错误码为 USER_CREDENTIAL_NOT_CONFIGURED")
  void noCredentialRejectsChat() {
    assertThatThrownBy(() -> service.chatContextForUser(USER_ID, null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo(ErrorCode.USER_CREDENTIAL_NOT_CONFIGURED.getCode());
  }

  @Test
  @DisplayName("保存凭证后仍须明确选择文本模型")
  void dashscopeCoversChatAndSpeech() {
    service.save(USER_ID, new SaveCredentialRequest(DASHSCOPE, "sk-dashscope-0123456789", "qwen-plus"));

    assertThatThrownBy(() -> service.chatContextForUser(USER_ID, null)).isInstanceOf(BusinessException.class);
    when(capabilityRepository.find(USER_ID, AiCapability.TEXT)).thenReturn(Optional.of(selection(DASHSCOPE)));
    AiExecutionContext chat = service.chatContextForUser(USER_ID, null);

    assertThat(chat.providerKey()).isEqualTo(DASHSCOPE);
    assertThat(chat.modelName()).isEqualTo("qwen-plus");
    assertThat(service.status(USER_ID).chatReady()).isTrue();
  }

  @Test
  @DisplayName("保存凭证时 credentialVersion 递增，并发布凭证变更事件")
  void saveBumpsVersionAndPublishesEvent() {
    service.save(USER_ID, new SaveCredentialRequest(DASHSCOPE, "sk-dashscope-0123456789", "qwen-plus"));
    long first = store.get(DASHSCOPE).getCredentialVersion();

    service.save(USER_ID, new SaveCredentialRequest(DASHSCOPE, "sk-dashscope-9876543210", "qwen-plus"));
    long second = store.get(DASHSCOPE).getCredentialVersion();

    assertThat(second).isGreaterThan(first);
    verify(eventPublisher, atLeastOnce()).publishEvent(any(UserCredentialChangedEvent.class));
  }

  @Test
  @DisplayName("删除凭证会发布事件以清理用户级客户端缓存")
  void deletePublishesEvent() {
    service.save(USER_ID, new SaveCredentialRequest(DASHSCOPE, "sk-dashscope-0123456789", "qwen-plus"));
    store.get(DASHSCOPE).setStatus(CredentialStatus.ACTIVE.name());

    when(repository.findByUserIdAndProviderKey(USER_ID, DASHSCOPE))
        .thenReturn(Optional.of(store.get(DASHSCOPE)));

    service.delete(USER_ID, DASHSCOPE);

    verify(eventPublisher, atLeastOnce()).publishEvent(new UserCredentialChangedEvent(USER_ID, DASHSCOPE));
  }

  @Test
  @DisplayName("凭证 DTO 不包含密文、nonce 与 Base URL")
  void dtoHasNoSecretFields() {
    UserCredentialDTO dto = service.save(USER_ID,
        new SaveCredentialRequest(DASHSCOPE, "sk-dashscope-0123456789", "qwen-plus"));

    assertThat(dto.providerKey()).isEqualTo(DASHSCOPE);
    assertThat(dto.keyTail()).isEqualTo("6789");
    // 记录类只暴露白名单字段
    List<String> components = new ArrayList<>();
    for (var component : UserCredentialDTO.class.getRecordComponents()) {
      components.add(component.getName());
    }
    assertThat(components).containsExactlyInAnyOrder(
        "providerKey", "configured", "modelName", "keyTail", "updatedAt", "chatEnabled");
  }

  @Test
  @DisplayName("状态接口分别暴露 Chat / 语音就绪状态")
  void statusReportsEachCapability() {
    service.save(USER_ID, new SaveCredentialRequest(DASHSCOPE, "sk-dashscope-0123456789", "qwen-plus"));
    service.save(USER_ID, new SaveCredentialRequest("volcengine-asr", "test-voice-0123456", "volc.seedasr.sauc.duration"));
    when(capabilityRepository.find(USER_ID, AiCapability.TEXT)).thenReturn(Optional.of(selection(DASHSCOPE)));
    when(capabilityRepository.find(USER_ID, AiCapability.ASR)).thenReturn(Optional.of(selection("volcengine-asr")));
    CredentialStatusDTO status = service.status(USER_ID);

    assertThat(status.chatReady()).isTrue();
    assertThat(status.speechReady()).isTrue();
    assertThat(status.chatModelName()).isEqualTo("qwen-plus");
  }

  @Test
  @DisplayName("模型绑定按能力隔离，已失效的任务凭证不会自动回退")
  void capabilitySelectionDoesNotFallBack() {
    service.save(USER_ID, new SaveCredentialRequest(DEEPSEEK, "sk-deepseek-0123456789", "deepseek-chat"));
    service.save(USER_ID, new SaveCredentialRequest("volcengine-asr", "test-voice-0123456", "volc.seedasr.sauc.duration"));

    assertThatThrownBy(() -> service.selectCapability(USER_ID, AiCapability.ASR, DEEPSEEK))
        .isInstanceOf(BusinessException.class);
    service.selectCapability(USER_ID, AiCapability.TEXT, DEEPSEEK);
    service.selectCapability(USER_ID, AiCapability.ASR, "volcengine-asr");
    verify(capabilityRepository).save(USER_ID, AiCapability.TEXT, selection(DEEPSEEK));
    verify(capabilityRepository).save(USER_ID, AiCapability.ASR, selection("volcengine-asr"));

    when(capabilityRepository.find(USER_ID, AiCapability.TEXT)).thenReturn(Optional.of(selection(DEEPSEEK)));
    String reference = service.requireSelectedReference(USER_ID, AiCapability.TEXT);
    assertThat(service.chatContextForUser(USER_ID, reference).providerKey()).isEqualTo(DEEPSEEK);
    service.save(USER_ID, new SaveCredentialRequest(DEEPSEEK, "sk-deepseek-9876543210", "deepseek-chat"));
    assertThatThrownBy(() -> service.chatContextForUser(USER_ID, reference))
        .isInstanceOf(BusinessException.class).hasMessageContaining("配置已变更");
    assertThat(service.selectedReference(USER_ID, AiCapability.TTS)).isEmpty();
  }

  @Test
  @DisplayName("openChatSecret 只在需要时解密，返回对象 toString 屏蔽 Key")
  void providerSecretMasksKey() {
    service.save(USER_ID, new SaveCredentialRequest(DASHSCOPE, "sk-dashscope-0123456789", "qwen-plus"));

    var secret = service.openChatSecret(USER_ID, DASHSCOPE);

    assertThat(secret.apiKey()).isEqualTo("sk-plaintext-key");
    assertThat(secret.toString()).doesNotContain("sk-plaintext-key").contains("***");
  }
}
