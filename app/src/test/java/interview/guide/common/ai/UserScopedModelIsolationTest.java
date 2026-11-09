package interview.guide.common.ai;

import interview.guide.common.config.ByokProperties;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.usercredential.service.UserCredentialService;
import interview.guide.modules.usercredential.service.UserCredentialService.ProviderSecret;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BYOK 阶段二核心安全验证：<b>用户级模型客户端缓存隔离</b>。
 *
 * <p>构造用户 A / 用户 B，都使用 DeepSeek 与相同模型名，验证：</p>
 * <ol>
 *   <li>两者创建的 ChatClient 不同实例；</li>
 *   <li>两者实际使用的 API Key 不同（各自解密自己的凭证）；</li>
 *   <li>相同模型名不会造成缓存共享；</li>
 *   <li>并发首次创建不会串用凭证；</li>
 *   <li>A 修改 Key（credentialVersion 递增）后其旧缓存失效，B 不受影响；</li>
 *   <li>删除凭证后缓存被清理，旧客户端不会被后续请求复用；</li>
 *   <li>缓存 Key 中不含任何 API Key 明文。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户级模型客户端缓存隔离")
class UserScopedModelIsolationTest {

  private static final String PROVIDER = "deepseek";
  private static final String MODEL = "deepseek-chat";
  private static final String KEY_A = "sk-userA-aaaaaaaaaaaaaaaaaaaa";
  private static final String KEY_B = "sk-userB-bbbbbbbbbbbbbbbbbbbb";

  private static final AiExecutionContext CTX_A = new AiExecutionContext(1L, PROVIDER, MODEL, 1L);
  private static final AiExecutionContext CTX_B = new AiExecutionContext(2L, PROVIDER, MODEL, 1L);

  @Mock
  private UserCredentialService userCredentialService;

  private LlmProviderRegistry registry;

  @BeforeEach
  void setUp() {
    LlmProviderProperties properties = new LlmProviderProperties();
    registry = new LlmProviderRegistry(properties, new ByokProperties(), null, null, null,
        userCredentialService, null, null, null);

    lenientSecret(1L, KEY_A);
    lenientSecret(2L, KEY_B);
  }

  private void lenientSecret(Long userId, String apiKey) {
    org.mockito.Mockito.lenient()
        .when(userCredentialService.openChatSecret(userId, PROVIDER))
        .thenReturn(new ProviderSecret(PROVIDER, "https://api.deepseek.com", MODEL, apiKey, 1L));
  }

  @Test
  @DisplayName("不同用户即使供应商与模型名相同，也各自持有独立 ChatClient")
  void differentUsersGetIndependentClients() {
    ChatClient clientA = registry.getChatClient(CTX_A);
    ChatClient clientB = registry.getChatClient(CTX_B);

    assertThat(clientA).isNotSameAs(clientB);
    // 各自的 Key 都被独立解密一次，说明没有串用
    verify(userCredentialService).openChatSecret(1L, PROVIDER);
    verify(userCredentialService).openChatSecret(2L, PROVIDER);
  }

  @Test
  @DisplayName("同一用户同一版本重复获取命中缓存，只解密一次")
  void sameUserSameVersionHitsCache() {
    ChatClient first = registry.getChatClient(CTX_A);
    ChatClient second = registry.getChatClient(CTX_A);

    assertThat(first).isSameAs(second);
    verify(userCredentialService, times(1)).openChatSecret(1L, PROVIDER);
  }

  @Test
  @DisplayName("缓存 Key 只含 userId/供应商/模型/版本，不含 API Key 明文")
  void cacheKeyNeverContainsApiKey() {
    registry.getChatClient(CTX_A);
    registry.getChatClient(CTX_B);

    Set<String> keys = cacheKeys();
    assertThat(keys).isNotEmpty();
    assertThat(keys).allSatisfy(key -> {
      assertThat(key).doesNotContain(KEY_A);
      assertThat(key).doesNotContain(KEY_B);
      assertThat(key).doesNotContain("sk-");
    });
    assertThat(keys).allMatch(key -> key.startsWith("1::") || key.startsWith("2::"));
  }

  @Test
  @DisplayName("用户修改 Key（credentialVersion 递增）后自身旧缓存失效")
  void versionBumpInvalidatesOnlyThatUser() {
    ChatClient clientABefore = registry.getChatClient(CTX_A);
    ChatClient clientB = registry.getChatClient(CTX_B);

    AiExecutionContext ctxAV2 = new AiExecutionContext(1L, PROVIDER, MODEL, 2L);
    ChatClient clientAAfter = registry.getChatClient(ctxAV2);

    assertThat(clientAAfter).isNotSameAs(clientABefore);
    // 版本不同 → 缓存 Key 不同，两代客户端并存直到显式失效
    assertThat(cacheKeys()).anyMatch(key -> key.startsWith("1::") && key.endsWith("::1:full"));
    assertThat(cacheKeys()).anyMatch(key -> key.startsWith("1::") && key.endsWith("::2:full"));

    // 凭证变更事件后，A 的全部缓存被清理，B 不受影响
    registry.onCredentialChanged(UserCredentialChangedEvent.of(1L, PROVIDER));

    assertThat(cacheKeys()).noneMatch(key -> key.startsWith("1::"));
    assertThat(cacheKeys()).anyMatch(key -> key.startsWith("2::"));
    assertThat(registry.getChatClient(CTX_B)).isSameAs(clientB);
  }

  @Test
  @DisplayName("删除凭证后旧客户端缓存被清理，不会继续被后续请求复用")
  void deleteCredentialEvictsCache() {
    registry.getChatClient(CTX_A);
    assertThat(cacheKeys()).anyMatch(key -> key.startsWith("1::"));

    registry.onCredentialChanged(UserCredentialChangedEvent.of(1L, PROVIDER));

    assertThat(cacheKeys()).noneMatch(key -> key.startsWith("1::"));
  }

  @Test
  @DisplayName("并发首次创建同一用户客户端只会构建一次，且不会串用其他用户凭证")
  void concurrentFirstCreationIsSafe() throws Exception {
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    ChatClient[] results = new ChatClient[threads];
    try {
      for (int i = 0; i < threads; i++) {
        final int idx = i;
        pool.submit(() -> {
          start.await();
          results[idx] = registry.getChatClient(CTX_A);
          return null;
        });
      }
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      pool.shutdownNow();
    }

    for (ChatClient result : results) {
      assertThat(result).isNotNull().isSameAs(results[0]);
    }
    // 多个线程并发，但只构建一次模型（其余命中缓存）
    verify(userCredentialService, times(1)).openChatSecret(1L, PROVIDER);
    verify(userCredentialService, never()).openChatSecret(2L, PROVIDER);
  }

  @Test
  @DisplayName("未知用户上下文被拒绝，不会退化为平台全局客户端")
  void nullContextIsRejected() {
    assertThatThrownBy(() -> registry.getChatClient((AiExecutionContext) null))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo(ErrorCode.AI_CONTEXT_USER_UNAVAILABLE.getCode());
  }

  @Test
  @DisplayName("Plain / Voice / 全功能三种客户端按用户隔离且互不复用")
  void clientVariantsAreIsolatedPerUser() {
    ChatClient fullA = registry.getChatClient(CTX_A);
    ChatClient plainA = registry.getPlainChatClient(CTX_A);
    ChatClient voiceA = registry.getVoiceChatClient(CTX_A);
    ChatClient fullB = registry.getChatClient(CTX_B);

    assertThat(fullA).isNotSameAs(plainA).isNotSameAs(voiceA);
    assertThat(fullA).isNotSameAs(fullB);
    // 三种变体共用同一份用户 ChatModel，只解密一次
    verify(userCredentialService, times(1)).openChatSecret(1L, PROVIDER);
  }

  @Test
  @DisplayName("容量上限生效：超过上限的条目会被淘汰，不会无界增长")
  void cacheIsBounded() {
    ByokProperties bounded = new ByokProperties();
    bounded.getCache().setMaxEntries(8);
    LlmProviderProperties properties = new LlmProviderProperties();
    LlmProviderRegistry smallRegistry = new LlmProviderRegistry(properties, bounded, null, null, null,
        userCredentialService, null, null, null);

    for (long userId = 1; userId <= 40; userId++) {
      org.mockito.Mockito.lenient()
          .when(userCredentialService.openChatSecret(userId, PROVIDER))
          .thenReturn(new ProviderSecret(PROVIDER, "https://api.deepseek.com", MODEL,
              "sk-user-" + userId + "-0123456789", 1L));
      smallRegistry.getChatClient(new AiExecutionContext(userId, PROVIDER, MODEL, 1L));
    }

    // 三个缓存各自有界（客户端 8 + 模型 8），不会随用户数无界增长
    assertThat(cacheSizeOf(smallRegistry, "clientCache")).isLessThanOrEqualTo(8);
    assertThat(cacheSizeOf(smallRegistry, "chatModelCache")).isLessThanOrEqualTo(8);
  }

  @SuppressWarnings("unchecked")
  private Set<String> cacheKeys() {
    try {
      var field = LlmProviderRegistry.class.getDeclaredField("clientCache");
      field.setAccessible(true);
      UserScopedCache<ChatClient> cache = (UserScopedCache<ChatClient>) field.get(registry);
      return cache.keys();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  private int cacheSizeOf(LlmProviderRegistry target, String fieldName) {
    try {
      var field = LlmProviderRegistry.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      UserScopedCache<?> cache = (UserScopedCache<?>) field.get(target);
      return cache.size();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
