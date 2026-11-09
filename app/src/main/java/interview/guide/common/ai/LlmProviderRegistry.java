package interview.guide.common.ai;

import com.openai.client.OpenAIClient;
import interview.guide.common.config.ByokProperties;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.config.LlmProviderProperties.AdvisorConfig;
import interview.guide.common.config.LlmProviderProperties.ProviderConfig;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.model.LlmGlobalSettingEntity;
import interview.guide.modules.llmprovider.model.LlmProviderEntity;
import interview.guide.modules.llmprovider.repository.LlmGlobalSettingRepository;
import interview.guide.modules.llmprovider.repository.LlmProviderRepository;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import interview.guide.modules.usercredential.service.UserCredentialService;
import interview.guide.modules.usercredential.service.UserCredentialService.ProviderSecret;
import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SafeGuardAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * LLM Provider 注册表：按<b>用户级隔离</b>创建并缓存 ChatClient / ChatModel。
 *
 * <p><b>阶段二核心改造</b>：缓存 Key 从「providerId」改为
 * {@code userId::providerKey::modelName::credentialVersion}。因此：</p>
 * <ul>
 *   <li>用户 A 与用户 B 即使使用相同供应商、相同模型，也各自持有独立客户端，
 *       <b>不会串用 API Key</b>；</li>
 *   <li>用户修改 Key 后 {@code credentialVersion} 递增，旧 Key 自动失效；</li>
 *   <li>用户删除凭证时通过 {@link UserCredentialChangedEvent} 主动清理该用户全部客户端；</li>
 *   <li>缓存 Key 中<b>不含</b>任何 API Key 明文或密文。</li>
 * </ul>
 *
 * <p><b>安全红线</b>：业务调用必须使用带 {@link AiExecutionContext} 的重载方法。
 * 保留的「平台级」旧方法仅供管理员 Provider 管理页使用，业务代码禁止调用，
 * 从而保证不存在「用户请求静默回退到开发者全局 Key」的路径。</p>
 */
@Component
@Slf4j
public class LlmProviderRegistry {

    /** 平台级（管理员）缓存键前缀，与用户级 key 空间完全隔离。 */
    private static final String PLATFORM_SCOPE = "platform";
    private static final String SUFFIX_FULL = ":full";
    private static final String SUFFIX_PLAIN = ":plain";
    private static final String SUFFIX_VOICE = ":voice";
    private static final String SUFFIX_MODEL = ":model";

    private final LlmProviderProperties properties;
    private final UserScopedCache<ChatClient> clientCache;
    private final UserScopedCache<OpenAiChatModel> chatModelCache;

    private final LlmProviderRepository providerRepository;
    private final LlmGlobalSettingRepository globalSettingRepository;
    private final ApiKeyEncryptionService encryptionService;
    private final UserCredentialService userCredentialService;

    private final ToolCallingManager toolCallingManager;
    private final ObservationRegistry observationRegistry;
    private final ToolCallback interviewSkillsToolCallback;

    @Autowired
    public LlmProviderRegistry(
            LlmProviderProperties properties,
            ByokProperties byokProperties,
            LlmProviderRepository providerRepository,
            LlmGlobalSettingRepository globalSettingRepository,
            ApiKeyEncryptionService encryptionService,
            UserCredentialService userCredentialService,
            @Autowired(required = false) ToolCallingManager toolCallingManager,
            @Autowired(required = false) ObservationRegistry observationRegistry,
            @Autowired(required = false) @Qualifier("interviewSkillsToolCallback") ToolCallback interviewSkillsToolCallback) {
        this.properties = properties;
        this.providerRepository = providerRepository;
        this.globalSettingRepository = globalSettingRepository;
        this.encryptionService = encryptionService;
        this.userCredentialService = userCredentialService;
        this.toolCallingManager = toolCallingManager;
        this.observationRegistry = observationRegistry;
        this.interviewSkillsToolCallback = interviewSkillsToolCallback;
        int maxEntries = byokProperties.getCache().getMaxEntries();
        var ttl = byokProperties.getCache().getTtl();
        this.clientCache = new UserScopedCache<>("LlmChatClient", maxEntries, ttl);
        this.chatModelCache = new UserScopedCache<>("LlmChatModel", maxEntries, ttl);
    }

    /**
     * 测试/探针用构造器：不接任何数据库与用户凭证，只能使用平台级方法。
     */
    public LlmProviderRegistry(
            LlmProviderProperties properties,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observationRegistry,
            ToolCallback interviewSkillsToolCallback) {
        this(properties, new ByokProperties(), null, null, null, null,
            toolCallingManager, observationRegistry, interviewSkillsToolCallback);
    }

    // ==================================================================
    // 用户级入口（业务代码唯一允许使用的 API）
    // ==================================================================

    /**
     * 获取该用户专属的 ChatClient（含工具回调与默认 Advisor）。
     */
    public ChatClient getChatClient(AiExecutionContext context) {
        requireUserContext(context);
        return clientCache.get(context.cacheScope() + SUFFIX_FULL,
            key -> buildChatClient(context));
    }

    /**
     * 获取该用户专属的 Plain ChatClient（不注册工具回调，用于结构化输出等）。
     */
    public ChatClient getPlainChatClient(AiExecutionContext context) {
        requireUserContext(context);
        return clientCache.get(context.cacheScope() + SUFFIX_PLAIN,
            key -> buildPlainChatClient(context));
    }

    /**
     * 获取该用户专属的语音面试 ChatClient（SkillsTool + 流式 ToolCall，无 Memory Advisor）。
     */
    public ChatClient getVoiceChatClient(AiExecutionContext context) {
        requireUserContext(context);
        return clientCache.get(context.cacheScope() + SUFFIX_VOICE,
            key -> buildVoiceChatClient(context));
    }

    /**
     * 使用「临时凭证」构建一次性 ChatClient，仅用于连接测试。
     *
     * <p>不进缓存、不落日志；调用方必须保证 apiKey 只存在于本次栈帧中。</p>
     */
    public ChatClient buildEphemeralChatClient(String baseUrl, String apiKey, String modelName) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(modelName)
            .temperature(0.0)
            .build();
        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(baseUrl, apiKey, 8000, 30000);
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .openAiClientAsync(openAiClient.async())
            .options(options)
            .observationRegistry(ObservationRegistry.NOOP)
            .build();
        return ChatClient.builder(chatModel).build();
    }

    /**
     * 凭证变更后主动失效该用户（或该用户某供应商）的全部客户端缓存。
     */
    @EventListener
    public void onCredentialChanged(UserCredentialChangedEvent event) {
        if (event == null || event.userId() == null) {
            return;
        }
        String prefix = event.providerKey() == null || event.providerKey().isBlank()
            ? AiExecutionContext.userPrefix(event.userId())
            : AiExecutionContext.userProviderPrefix(event.userId(), event.providerKey());
        invalidateByPrefix(prefix);
        log.info("[LlmProviderRegistry] 凭证变更，已失效用户级模型缓存: userId={}, providerKey={}",
            event.userId(), event.providerKey());
    }

    /**
     * 清空全部缓存（管理员修改平台 Provider 配置后调用）。
     */
    public void reload() {
        int size = clientCache.size() + chatModelCache.size();
        clientCache.clear();
        chatModelCache.clear();
        log.info("[LlmProviderRegistry] 缓存已清空 ({} 条)。下次访问将重建客户端。", size);
    }

    /** 仅供测试与诊断：当前用户级缓存条目数。 */
    public int cacheSize() {
        return clientCache.size() + chatModelCache.size();
    }

    private void invalidateByPrefix(String prefix) {
        clientCache.invalidate(key -> key.startsWith(prefix));
        chatModelCache.invalidate(key -> key.startsWith(prefix));
    }

    private void requireUserContext(AiExecutionContext context) {
        if (context == null || context.userId() == null) {
            throw new BusinessException(ErrorCode.AI_CONTEXT_USER_UNAVAILABLE);
        }
        if (userCredentialService == null) {
            throw new BusinessException(ErrorCode.USER_CREDENTIAL_NOT_CONFIGURED);
        }
    }

    // ------------------------------------------------------------------
    // 用户级客户端构建
    // ------------------------------------------------------------------

    private ChatClient buildChatClient(AiExecutionContext context) {
        OpenAiChatModel chatModel = chatModel(context);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = buildDefaultAdvisors(context.providerKey());
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
        }
        // 只记录非敏感定位信息，绝不记录 apiKey / baseUrl 凭证细节
        log.info("[LlmProviderRegistry] 已创建用户级 ChatClient: userId={}, provider={}, model={}, credentialVersion={}",
            context.userId(), context.providerKey(), context.modelName(), context.credentialVersion());
        return builder.build();
    }

    private ChatClient buildPlainChatClient(AiExecutionContext context) {
        OpenAiChatModel chatModel = chatModel(context);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        buildSafeGuardAdvisor().ifPresent(advisor -> builder.defaultAdvisors(List.of(advisor)));
        return builder.build();
    }

    private ChatClient buildVoiceChatClient(AiExecutionContext context) {
        OpenAiChatModel chatModel = chatModel(context);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = new ArrayList<>();
        if (toolCallingManager != null) {
            advisors.add(buildToolCallAdvisor(true));
        }
        buildSafeGuardAdvisor().ifPresent(advisors::add);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
        }
        return builder.build();
    }

    private OpenAiChatModel chatModel(AiExecutionContext context) {
        return chatModelCache.get(context.cacheScope() + SUFFIX_MODEL,
            key -> buildUserChatModel(context));
    }

    private OpenAiChatModel buildUserChatModel(AiExecutionContext context) {
        ProviderSecret secret = userCredentialService.openChatSecret(
            context.userId(), context.providerKey());
        // 模型名以用户配置为准，防止上下文与实际凭证不一致
        String model = secret.modelName() != null && !secret.modelName().isBlank()
            ? secret.modelName() : context.modelName();
        log.info("[LlmProviderRegistry] 构建用户级 ChatModel: userId={}, provider={}, model={}",
            context.userId(), context.providerKey(), model);

        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(secret.baseUrl(), secret.apiKey());
        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(model)
            .temperature(resolveTemperature(context.providerKey()))
            .build();
        return OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .openAiClientAsync(openAiClient.async())
            .options(options)
            .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
            .build();
    }

    private Double resolveTemperature(String providerKey) {
        ProviderConfig config = providerConfig(providerKey);
        Double configured = config == null ? null : config.getTemperature();
        return configured != null ? configured : 0.2;
    }

    private ProviderConfig providerConfig(String providerKey) {
        var providers = properties.getProviders();
        return providers == null ? null : providers.get(providerKey);
    }

    // ------------------------------------------------------------------
    // Advisor 构建
    // ------------------------------------------------------------------

    private List<Advisor> buildDefaultAdvisors(String providerId) {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isEnabled()) {
            return List.of();
        }

        List<Advisor> advisors = new ArrayList<>();

        if (config.isToolCallEnabled()) {
            if (toolCallingManager != null) {
                advisors.add(buildToolCallAdvisor(config.isToolCallConversationHistoryEnabled()));
            } else {
                log.warn("[LlmProviderRegistry] ToolCallAdvisor skipped: ToolCallingManager unavailable, provider={}", providerId);
            }
        }

        if (config.isMessageChatMemoryEnabled()) {
            int maxMessages = Math.max(20, config.getMessageChatMemoryMaxMessages());
            MessageChatMemoryAdvisor memoryAdvisor = MessageChatMemoryAdvisor.builder(
                MessageWindowChatMemory.builder()
                    .maxMessages(maxMessages)
                    .build()
            ).build();
            advisors.add(memoryAdvisor);
        }

        if (config.isSimpleLoggerEnabled()) {
            advisors.add(new SimpleLoggerAdvisor());
        }

        buildSafeGuardAdvisor().ifPresent(advisors::add);

        return advisors;
    }

    private ToolCallingAdvisor buildToolCallAdvisor(boolean conversationHistoryEnabled) {
        return ToolCallingAdvisor.builder()
            .toolCallingManager(toolCallingManager)
            .conversationHistoryEnabled(conversationHistoryEnabled)
            .build();
    }

    private Optional<SafeGuardAdvisor> buildSafeGuardAdvisor() {
        AdvisorConfig config = properties.getAdvisors();
        if (config == null || !config.isSafeguardEnabled()) {
            return Optional.empty();
        }
        SafeGuardAdvisor advisor = SafeGuardAdvisor.builder()
            .sensitiveWords(config.getSafeguardWords())
            .failureResponse("抱歉，我只能协助面试相关的任务。")
            .order(100)
            .build();
        return Optional.of(advisor);
    }

    // ==================================================================
    // 平台级入口（仅管理员 Provider 管理使用，业务代码禁止调用）
    // ==================================================================

    /**
     * @deprecated 业务代码禁止使用。仅供管理员 Provider 管理页做连通性验证；
     *             普通用户的任何 AI 请求都必须走 {@link #getChatClient(AiExecutionContext)}。
     */
    @Deprecated(since = "BYOK-2")
    public ChatClient getChatClient(String providerId) {
        String id = resolvePlatformProviderId(providerId);
        return clientCache.get(PLATFORM_SCOPE + "::" + id + SUFFIX_FULL,
            key -> createPlatformChatClient(id));
    }

    /**
     * @deprecated 业务代码禁止使用，理由同 {@link #getChatClient(String)}。
     */
    @Deprecated(since = "BYOK-2")
    public ChatClient getDefaultChatClient() {
        return getChatClient(resolvePlatformDefaultChatProviderId());
    }

    /**
     * @deprecated 业务代码禁止使用，理由同 {@link #getChatClient(String)}。
     */
    @Deprecated(since = "BYOK-2")
    public ChatClient getPlainChatClient() {
        return getPlainChatClient(resolvePlatformDefaultChatProviderId());
    }

    /**
     * @deprecated 业务代码禁止使用，理由同 {@link #getChatClient(String)}。
     */
    @Deprecated(since = "BYOK-2")
    public ChatClient getChatClientOrDefault(String providerId) {
        return getChatClient(resolvePlatformProviderId(providerId));
    }

    /**
     * @deprecated 业务代码禁止使用，理由同 {@link #getChatClient(String)}。
     */
    @Deprecated(since = "BYOK-2")
    public ChatClient getPlainChatClient(String providerId) {
        String id = resolvePlatformProviderId(providerId);
        return clientCache.get(PLATFORM_SCOPE + "::" + id + SUFFIX_PLAIN,
            key -> createPlatformPlainChatClient(id));
    }

    /**
     * @deprecated 业务代码禁止使用，理由同 {@link #getChatClient(String)}。
     */
    @Deprecated(since = "BYOK-2")
    public ChatClient getVoiceChatClient(String providerId) {
        String id = resolvePlatformProviderId(providerId);
        return clientCache.get(PLATFORM_SCOPE + "::" + id + SUFFIX_VOICE,
            key -> createPlatformVoiceChatClient(id));
    }

    private ChatClient createPlatformChatClient(String providerId) {
        OpenAiChatModel chatModel = platformChatModel(providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = buildDefaultAdvisors(providerId);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
        }
        return builder.build();
    }

    private ChatClient createPlatformPlainChatClient(String providerId) {
        OpenAiChatModel chatModel = platformChatModel(providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        buildSafeGuardAdvisor().ifPresent(advisor -> builder.defaultAdvisors(List.of(advisor)));
        return builder.build();
    }

    private ChatClient createPlatformVoiceChatClient(String providerId) {
        OpenAiChatModel chatModel = platformChatModel(providerId);
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (interviewSkillsToolCallback != null) {
            builder.defaultTools(interviewSkillsToolCallback);
        }
        List<Advisor> advisors = new ArrayList<>();
        if (toolCallingManager != null) {
            advisors.add(buildToolCallAdvisor(true));
        }
        buildSafeGuardAdvisor().ifPresent(advisors::add);
        if (!advisors.isEmpty()) {
            builder.defaultAdvisors(advisors);
        }
        return builder.build();
    }

    private OpenAiChatModel platformChatModel(String providerId) {
        return chatModelCache.get(PLATFORM_SCOPE + "::" + providerId + SUFFIX_MODEL,
            key -> buildPlatformChatModel(providerId));
    }

    private OpenAiChatModel buildPlatformChatModel(String providerId) {
        ProviderSnapshot config = loadPlatformProviderOrThrow(providerId);
        log.info("[LlmProviderRegistry] 构建平台级 ChatModel (管理员): provider={}, model={}",
            providerId, config.model());

        OpenAIClient openAiClient = ApiPathResolver.buildOpenAiClient(config.baseUrl(), config.apiKey());
        OpenAiChatOptions options = OpenAiChatOptions.builder()
            .model(config.model())
            .temperature(config.temperature() != null ? config.temperature() : 0.2)
            .build();
        return OpenAiChatModel.builder()
            .openAiClient(openAiClient)
            .openAiClientAsync(openAiClient.async())
            .options(options)
            .observationRegistry(observationRegistry != null ? observationRegistry : ObservationRegistry.NOOP)
            .build();
    }

    private String resolvePlatformProviderId(String providerId) {
        if (providerId == null || providerId.isBlank() || "default".equalsIgnoreCase(providerId.trim())) {
            return resolvePlatformDefaultChatProviderId();
        }
        return providerId;
    }

    private String resolvePlatformDefaultChatProviderId() {
        if (globalSettingRepository == null) {
            return properties.getDefaultProvider();
        }
        return globalSettingRepository.findById(LlmGlobalSettingEntity.SINGLETON_ID)
            .map(LlmGlobalSettingEntity::getDefaultChatProviderId)
            .filter(id -> !isBlank(id))
            .orElse(properties.getDefaultProvider());
    }

    private ProviderSnapshot loadPlatformProviderOrThrow(String providerId) {
        if (providerRepository == null) {
            return loadProviderFromPropertiesOrThrow(providerId);
        }
        LlmProviderEntity entity = providerRepository.findById(providerId)
            .filter(LlmProviderEntity::isEnabled)
            .orElseThrow(() -> new IllegalArgumentException("Unknown LLM provider: " + providerId));
        return new ProviderSnapshot(
            entity.getId(),
            entity.getBaseUrl(),
            encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext()),
            entity.getModel(),
            entity.getTemperature()
        );
    }

    private ProviderSnapshot loadProviderFromPropertiesOrThrow(String providerId) {
        ProviderConfig config = properties.getProviders().get(providerId);
        if (config == null) {
            log.error("[LlmProviderRegistry] Provider config not found: {}", providerId);
            throw new IllegalArgumentException("Unknown LLM provider: " + providerId);
        }
        return new ProviderSnapshot(
            providerId,
            config.getBaseUrl(),
            config.getApiKey(),
            config.getModel(),
            config.getTemperature()
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ProviderSnapshot(
        String id,
        String baseUrl,
        String apiKey,
        String model,
        Double temperature
    ) {
    }
}
