package interview.guide.modules.usercredential.service;

import interview.guide.common.config.ByokProperties;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.CurrentUserProvider;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import interview.guide.modules.usercredential.dto.SaveCredentialRequest;
import interview.guide.modules.usercredential.dto.UserCredentialDTO;
import interview.guide.modules.usercredential.model.UserLlmCredentialEntity;
import interview.guide.modules.usercredential.repository.UserLlmCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("用户级 AI 凭证服务")
class UserCredentialServiceTest {

    private static final String PLAINTEXT_KEY = "sk-abcdefghijklmnop-1234";
    private static final Long USER_A = 101L;
    private static final Long USER_B = 202L;

    @Mock
    private UserLlmCredentialRepository repository;

    @Mock
    private CurrentUserProvider currentUserProvider;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private UserCredentialService service;

    @BeforeEach
    void setUp() {
        LlmProviderProperties aiProperties = new LlmProviderProperties();
        LlmProviderProperties.SecurityConfig security = new LlmProviderProperties.SecurityConfig();
        security.setApiKeyEncryptionKey("unit-test-encryption-key-32-bytes!");
        aiProperties.setSecurity(security);

        LlmProviderProperties.ProviderConfig deepseek = new LlmProviderProperties.ProviderConfig();
        deepseek.setBaseUrl("https://api.deepseek.com");
        deepseek.setModel("deepseek-chat");
        aiProperties.setProviders(Map.of("deepseek", deepseek));

        ApiKeyEncryptionService encryptionService = new ApiKeyEncryptionService(aiProperties);
        ReflectionTestUtils.invokeMethod(encryptionService, "init");

        ByokProperties byokProperties = new ByokProperties();
        byokProperties.setAllowedProviders(List.of("deepseek"));

        ProviderPresetRegistry presetRegistry =
            new ProviderPresetRegistry(byokProperties, aiProperties);

        service = new UserCredentialService(
            repository, encryptionService, presetRegistry, byokProperties,
            org.mockito.Mockito.mock(interview.guide.modules.usercredential.repository.UserCapabilityRepository.class),
            currentUserProvider, eventPublisher);
    }

    @Nested
    @DisplayName("加密存储")
    class Encryption {

        @Test
        @DisplayName("保存时数据库中只有密文，且返回对象不含明文 Key")
        void shouldStoreCiphertextOnly() {
            when(repository.findByUserIdAndProviderKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
            when(repository.save(any(UserLlmCredentialEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            UserCredentialDTO dto = service.save(USER_A,
                new SaveCredentialRequest("deepseek", PLAINTEXT_KEY, "deepseek-chat"));

            ArgumentCaptor<UserLlmCredentialEntity> captor =
                ArgumentCaptor.forClass(UserLlmCredentialEntity.class);
            verify(repository).save(captor.capture());
            UserLlmCredentialEntity saved = captor.getValue();

            // 关键断言：落库内容中不出现明文
            assertThat(saved.getApiKeyCiphertext()).isNotBlank().isNotEqualTo(PLAINTEXT_KEY);
            assertThat(saved.getApiKeyCiphertext()).doesNotContain(PLAINTEXT_KEY);
            assertThat(saved.getApiKeyNonce()).isNotBlank();

            // 对外视图不含 Key 与密文
            assertThat(dto.providerKey()).isEqualTo("deepseek");
            assertThat(dto.configured()).isTrue();
            assertThat(dto.modelName()).isEqualTo("deepseek-chat");
            assertThat(dto.keyTail()).isEqualTo("1234");
        }

        @Test
        @DisplayName("同一明文两次加密得到不同密文（nonce 随机）")
        void shouldUseRandomNonce() {
            when(repository.findByUserIdAndProviderKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
            when(repository.save(any(UserLlmCredentialEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            service.save(USER_A, new SaveCredentialRequest("deepseek", PLAINTEXT_KEY, "m1"));
            service.save(USER_B, new SaveCredentialRequest("deepseek", PLAINTEXT_KEY, "m1"));

            ArgumentCaptor<UserLlmCredentialEntity> captor =
                ArgumentCaptor.forClass(UserLlmCredentialEntity.class);
            verify(repository, org.mockito.Mockito.times(2)).save(captor.capture());
            List<UserLlmCredentialEntity> saved = captor.getAllValues();

            assertThat(saved.get(0).getApiKeyNonce()).isNotEqualTo(saved.get(1).getApiKeyNonce());
            assertThat(saved.get(0).getApiKeyCiphertext())
                .isNotEqualTo(saved.get(1).getApiKeyCiphertext());
        }

        @Test
        @DisplayName("解密可以还原原始 Key，供后续模型调用使用")
        void shouldDecryptBackToOriginal() {
            when(repository.save(any(UserLlmCredentialEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

            UserCredentialDTO dto = service.save(USER_A,
                new SaveCredentialRequest("deepseek", PLAINTEXT_KEY, "deepseek-chat"));
            assertThat(dto.configured()).isTrue();

            ArgumentCaptor<UserLlmCredentialEntity> captor =
                ArgumentCaptor.forClass(UserLlmCredentialEntity.class);
            verify(repository).save(captor.capture());
            when(repository.findByUserIdAndProviderKey(USER_A, "deepseek"))
                .thenReturn(Optional.of(captor.getValue()));

            UserCredentialService.ProviderSecret resolved =
                service.openChatSecret(USER_A, "deepseek");

            assertThat(resolved.apiKey()).isEqualTo(PLAINTEXT_KEY);
            assertThat(resolved.baseUrl()).isEqualTo("https://api.deepseek.com");
            assertThat(resolved.modelName()).isEqualTo("deepseek-chat");
        }

        @Test
        @DisplayName("解密快照的 toString 不泄露 Key")
        void shouldNotLeakKeyInToString() {
            var snapshot = new UserCredentialService.ProviderSecret(
                "deepseek", "https://api.deepseek.com", "deepseek-chat", PLAINTEXT_KEY, 1L);

            assertThat(snapshot.toString()).doesNotContain(PLAINTEXT_KEY);
            assertThat(snapshot.toString()).contains("***");
        }
    }

    @Nested
    @DisplayName("供应商白名单")
    class ProviderWhitelist {

        @Test
        @DisplayName("不在白名单内的供应商被拒绝")
        void shouldRejectUnknownProvider() {
            assertThatThrownBy(() -> service.save(USER_A,
                new SaveCredentialRequest("openai", PLAINTEXT_KEY, "gpt-4o")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.USER_CREDENTIAL_PROVIDER_NOT_ALLOWED.getCode());

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("Base URL 只能来自服务端配置，用户无法指定")
        void shouldResolveBaseUrlFromServer() {
            assertThat(service.listProviders(USER_A).stream().filter(preset -> preset.providerKey().equals("deepseek")).toList())
                .singleElement()
                .satisfies(preset -> {
                    assertThat(preset.providerKey()).isEqualTo("deepseek");
                    assertThat(preset.baseUrl()).isEqualTo("https://api.deepseek.com");
                });
        }
    }

    @Nested
    @DisplayName("用户隔离")
    class UserIsolation {

        @Test
        @DisplayName("未配置时返回 configured=false，且不回退任何全局凭证")
        void shouldReturnNotConfigured() {
            when(repository.findByUserIdAndProviderKey(USER_A, "deepseek"))
                .thenReturn(Optional.empty());

            UserCredentialDTO dto = service.get(USER_A, "deepseek");

            assertThat(dto.configured()).isFalse();
            assertThat(dto.keyTail()).isNull();
        }

        @Test
        @DisplayName("未配置时解析明文抛出明确的未配置错误码")
        void shouldThrowWhenResolvingMissing() {
            when(repository.findByUserIdAndProviderKey(USER_B, "deepseek"))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.openChatSecret(USER_B, "deepseek"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.USER_CREDENTIAL_NOT_CONFIGURED.getCode());
        }

        @Test
        @DisplayName("删除只作用于当前用户自己的凭证")
        void shouldDeleteOnlyOwnCredential() {
            when(repository.findByUserIdAndProviderKey(USER_A, "deepseek"))
                .thenReturn(Optional.of(UserLlmCredentialEntity.builder()
                    .userId(USER_A).providerKey("deepseek").build()));

            service.delete(USER_A, "deepseek");

            verify(repository).deleteByUserIdAndProviderKey(USER_A, "deepseek");
            verify(repository, never()).deleteByUserIdAndProviderKey(USER_B, "deepseek");
        }
    }

    @Nested
    @DisplayName("入参校验")
    class Validation {

        @Test
        @DisplayName("包含空白的 API Key 被拒绝")
        void shouldRejectWhitespaceInKey() {
            assertThatThrownBy(() -> service.save(USER_A,
                new SaveCredentialRequest("deepseek", "sk-abc def ghijklmn", "deepseek-chat")))
                .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("非法模型名被拒绝")
        void shouldRejectIllegalModelName() {
            assertThatThrownBy(() -> service.save(USER_A,
                new SaveCredentialRequest("deepseek", PLAINTEXT_KEY, "model with space")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.USER_CREDENTIAL_MODEL_NOT_ALLOWED.getCode());
        }
    }
}
