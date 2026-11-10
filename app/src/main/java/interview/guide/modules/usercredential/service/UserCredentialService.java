package interview.guide.modules.usercredential.service;

import interview.guide.common.ai.AiExecutionContext;
import interview.guide.common.ai.UserCredentialChangedEvent;
import interview.guide.common.config.ByokProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.CurrentUserProvider;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import interview.guide.modules.usercredential.dto.CredentialStatusDTO;
import interview.guide.modules.usercredential.dto.CapabilitySelectionDTO;
import interview.guide.modules.usercredential.model.AiCapability;
import interview.guide.modules.usercredential.repository.UserCapabilityRepository;
import interview.guide.modules.usercredential.dto.ProviderPresetDTO;
import interview.guide.modules.usercredential.dto.SaveCredentialRequest;
import interview.guide.modules.usercredential.dto.UserCredentialDTO;
import interview.guide.modules.usercredential.model.CredentialStatus;
import interview.guide.modules.usercredential.model.UserLlmCredentialEntity;
import interview.guide.modules.usercredential.repository.UserLlmCredentialRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 用户级 LLM 凭证管理 + 用户级模型上下文解析。
 *
 * <p>安全边界：</p>
 * <ul>
 *   <li>所有方法的第一参数都是 {@code userId}，同步请求由
 *       {@link CurrentUserProvider} 从认证上下文提供；异步任务由业务资源的所有者提供。</li>
 *   <li>只对「当前用户 + 供应商」这一组合做读写，天然阻断越权。</li>
 *   <li>返回值永远是 {@link UserCredentialDTO}（不含明文/密文）。</li>
 *   <li>明文 API Key 仅在 {@link #openChatSecret} 中短暂出现，不写日志、不入缓存 Key、不进消息队列。</li>
 *   <li><b>不存在平台全局 Key 回退</b>：用户没有可用凭证时直接抛出配置错误。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserCredentialService {

    /** 模型名允许的字符集：模型标识（含厂商前缀与版本号）足够覆盖，且排除空白与控制字符。 */
    private static final Pattern MODEL_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9._:\\-/@]+$");
    private static final int MIN_API_KEY_LENGTH = 8;
    private static final int KEY_TAIL_LENGTH = 4;

    private final UserLlmCredentialRepository repository;
    private final ApiKeyEncryptionService encryptionService;
    private final ProviderPresetRegistry presetRegistry;
    private final ByokProperties byokProperties;
    private final UserCapabilityRepository capabilityRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;

    // ==================================================================
    // 查询
    // ==================================================================

    @Transactional(readOnly = true)
    public List<UserCredentialDTO> list(Long userId) {
        return repository.findAllByUserIdOrderByUpdatedAtDesc(userId).stream()
            .map(this::toDto)
            .toList();
    }

    @Transactional(readOnly = true)
    public UserCredentialDTO get(Long userId, String providerKey) {
        String key = normalizeProviderKey(providerKey);
        return repository.findByUserIdAndProviderKey(userId, key)
            .map(this::toDto)
            .orElseGet(() -> UserCredentialDTO.notConfigured(key));
    }

    @Transactional(readOnly = true)
    public List<ProviderPresetDTO> listProviders(Long userId) {
        Set<String> configured = repository.findAllByUserIdOrderByUpdatedAtDesc(userId).stream()
            .filter(entity -> CredentialStatus.ACTIVE.name().equals(entity.getStatus()))
            .map(UserLlmCredentialEntity::getProviderKey)
            .collect(Collectors.toSet());
        return presetRegistry.listPresets(configured);
    }

    /**
     * 当前用户 AI 能力就绪状态（Chat / 语音）。
     */
    @Transactional(readOnly = true)
    public CredentialStatusDTO status(Long userId) {
        var chat = selected(userId, AiCapability.TEXT);
        var speech = selected(userId, AiCapability.ASR);
        return new CredentialStatusDTO(chat.isPresent(),
            chat.map(UserLlmCredentialEntity::getProviderKey).orElse(null),
            chat.map(UserLlmCredentialEntity::getModelName).orElse(null),
            speech.isPresent(), speech.map(UserLlmCredentialEntity::getProviderKey).orElse(null));
    }

    @Transactional(readOnly = true)
    public List<CapabilitySelectionDTO> capabilities(Long userId) {
        var credentials = repository.findAllByUserIdOrderByUpdatedAtDesc(userId);
        return java.util.Arrays.stream(AiCapability.values()).map(capability -> {
            var selected = capabilityRepository.find(userId, capability).orElse(null);
            String key = selected == null ? null : selected.providerKey();
            var options = credentials.stream().filter(entity -> supports(entity, capability))
                .map(entity -> new CapabilitySelectionDTO.Option(entity.getProviderKey(),
                    presetRegistry.displayName(entity.getProviderKey()), entity.getModelName()))
                .toList();
            return new CapabilitySelectionDTO(capability, key,
                credentials.stream().anyMatch(entity -> matchesSelection(selected, entity) && supports(entity, capability)), options);
        }).toList();
    }

    @Transactional
    public void selectCapability(Long userId, AiCapability capability, String providerKey) {
        if (providerKey == null || providerKey.isBlank()) {
            capabilityRepository.clear(userId, capability);
            return;
        }
        String key = normalizeProviderKey(providerKey);
        var entity = requireCredential(userId, capability, key);
        capabilityRepository.save(userId, capability,
            new UserCapabilityRepository.Selection(key, entity.getId(), entity.getModelName()));
    }

    @Transactional(readOnly = true)
    public ProviderSecret openCapabilitySecret(Long userId, AiCapability capability, String reference) {
        var entity = reference == null ? selected(userId, capability)
            .orElseThrow(() -> missing(capability)) : requirePinned(userId, capability, reference);
        return secret(entity);
    }

    @Transactional(readOnly = true)
    public Optional<String> selectedReference(Long userId, AiCapability capability) {
        return selected(userId, capability)
            .map(entity -> entity.getProviderKey() + "|" + entity.getId() + "|" + entity.getCredentialVersion());
    }

    @Transactional(readOnly = true)
    public String requireSelectedReference(Long userId, AiCapability capability) {
        return selectedReference(userId, capability).orElseThrow(() -> missing(capability));
    }

    @Transactional(readOnly = true)
    public ProviderSecret openSavedSecretForTest(Long userId, String providerKey) {
        String key = normalizeProviderKey(providerKey);
        return secret(requireCredential(userId, presetRegistry.capability(key), key));
    }

    @Transactional
    public UserCredentialDTO reuseKey(Long userId, String targetProvider, String sourceProvider) {
        String target = normalizeProviderKey(targetProvider);
        String source = normalizeProviderKey(sourceProvider);
        boolean compatible = target.startsWith("dashscope-")
            && (source.equals("dashscope") || ("dashscope-asr".equals(source) || "dashscope-tts".equals(source)))
            || target.startsWith("volcengine-") && ("volcengine-asr".equals(source) || "volcengine-tts".equals(source));
        if (!compatible || !SpeechPresets.ALL.containsKey(target) || target.equals(source)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "这两个模型不能复用同一凭证");
        }
        return save(userId, new SaveCredentialRequest(target, decrypt(loadActive(userId, source)),
            presetRegistry.defaultModel(target)));
    }

    // ==================================================================
    // 写入
    // ==================================================================

    /**
     * 新增或覆盖用户凭证。覆盖时 {@code credentialVersion} 自增，便于调用侧感知配置变更。
     */
    @Transactional
    public UserCredentialDTO save(Long userId, SaveCredentialRequest request) {
        String providerKey = normalizeProviderKey(request.providerKey());
        if (!presetRegistry.isAllowed(providerKey)) {
            throw new BusinessException(ErrorCode.USER_CREDENTIAL_PROVIDER_NOT_ALLOWED);
        }
        // 提前解析 Base URL，确保该供应商在平台侧确实可用（同时避免把不可用供应商写入库）。
        presetRegistry.requireBaseUrl(providerKey);

        String apiKey = validateApiKey(request.apiKey());
        String modelName = validateProviderModel(providerKey, request.modelName());

        ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(apiKey);

        UserLlmCredentialEntity entity = repository
            .findByUserIdAndProviderKey(userId, providerKey)
            .orElseGet(() -> UserLlmCredentialEntity.builder()
                .userId(userId)
                .providerKey(providerKey)
                .credentialVersion(0L)
                .build());

        entity.setModelName(modelName);
        entity.setApiKeyCiphertext(encrypted.ciphertext());
        entity.setApiKeyNonce(encrypted.nonce());
        entity.setKeyVersion(currentKeyVersion());
        entity.setKeyTail(extractKeyTail(apiKey));
        entity.setStatus(CredentialStatus.ACTIVE.name());
        entity.setChatEnabled(presetRegistry.capability(providerKey) == AiCapability.TEXT);
        entity.setCredentialVersion(
            (entity.getCredentialVersion() == null ? 0L : entity.getCredentialVersion()) + 1);

        UserLlmCredentialEntity saved = repository.save(entity);
        // 只记录非敏感元数据：用户、供应商、模型。绝不记录 Key 或密文。
        log.info("用户 AI 凭证已保存: userId={}, providerKey={}, model={}, credentialVersion={}",
            userId, providerKey, modelName, saved.getCredentialVersion());
        publishChanged(userId, providerKey);
        return toDto(saved);
    }

    /**
     * 仅更新模型名称，不触碰密文与 Key 尾部信息。
     */
    @Transactional
    public UserCredentialDTO updateModelName(Long userId, String providerKey, String modelName) {
        String key = normalizeProviderKey(providerKey);
        UserLlmCredentialEntity entity = repository
            .findByUserIdAndProviderKey(userId, key)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_CREDENTIAL_NOT_CONFIGURED));
        entity.setModelName(validateProviderModel(key, modelName));
        entity.setCredentialVersion(
            (entity.getCredentialVersion() == null ? 0L : entity.getCredentialVersion()) + 1);
        UserLlmCredentialEntity saved = repository.save(entity);
        log.info("用户 AI 凭证模型已更新: userId={}, providerKey={}, model={}, credentialVersion={}",
            userId, key, saved.getModelName(), saved.getCredentialVersion());
        publishChanged(userId, key);
        return toDto(saved);
    }

    @Transactional
    public void delete(Long userId, String providerKey) {
        String key = normalizeProviderKey(providerKey);
        if (repository.findByUserIdAndProviderKey(userId, key).isEmpty()) {
            // 即使库里没有也要清一次缓存，避免脏缓存继续被使用。
            publishChanged(userId, key);
            return;
        }
        repository.deleteByUserIdAndProviderKey(userId, key);
        log.info("用户 AI 凭证已删除: userId={}, providerKey={}", userId, key);
        publishChanged(userId, key);
    }

    // ==================================================================
    // 用户级模型上下文解析（阶段二核心）
    // ==================================================================

    /**
     * 同步请求：从 SecurityContext 获取用户，解析其 Chat 模型上下文。
     */
    @Transactional(readOnly = true)
    public AiExecutionContext requireChatContext() {
        return chatContextForUser(currentUserProvider.requireUserId(), null);
    }

    /**
     * 解析指定用户的 Chat 模型上下文（同步 / 异步通用）。
     *
     * @param userId              业务数据所有者；异步任务必须来自数据库资源的 {@code user_id}
     * @param preferredProviderKey 业务侧期望的供应商（可为 null），仅在用户确实配置了该供应商时采纳
     * @throws BusinessException 用户未配置任何可用 Chat 凭证时抛出 {@code USER_CREDENTIAL_NOT_CONFIGURED}
     */
    @Transactional(readOnly = true)
    public AiExecutionContext chatContextForUser(Long userId, String preferredProviderKey) {
        var entity = preferredProviderKey != null && preferredProviderKey.contains("|")
            ? requirePinned(userId, AiCapability.TEXT, preferredProviderKey)
            : selected(userId, AiCapability.TEXT).orElseThrow(() -> missing(AiCapability.TEXT));
        return toContext(entity);
    }

    /**
     * 解密用户 Chat 凭证，仅供 {@code LlmProviderRegistry} 在缓存未命中时构造客户端。
     *
     * <p>返回对象只在方法调用点短暂存在，调用方不得写日志、缓存、消息队列或工作流状态。</p>
     */
    @Transactional(readOnly = true)
    public ProviderSecret openChatSecret(Long userId, String providerKey) {
        UserLlmCredentialEntity entity = loadActive(userId, providerKey);
        if (!supports(entity, AiCapability.TEXT)) {
            throw new BusinessException(ErrorCode.USER_CREDENTIAL_NOT_CONFIGURED);
        }
        return new ProviderSecret(
            entity.getProviderKey(),
            presetRegistry.requireBaseUrl(entity.getProviderKey()),
            entity.getModelName(),
            decrypt(entity),
            entity.getCredentialVersion()
        );
    }

    /**
     * 解密一份「尚未保存」的临时凭证，仅用于连接测试；不写入数据库、不写日志。
     */
    public ProviderSecret openTransientSecret(String providerKey, String apiKey) {
        String key = normalizeProviderKey(providerKey);
        if (!presetRegistry.isAllowed(key)) {
            throw new BusinessException(ErrorCode.USER_CREDENTIAL_PROVIDER_NOT_ALLOWED);
        }
        return new ProviderSecret(
            key,
            presetRegistry.requireBaseUrl(key),
            presetRegistry.defaultModel(key),
            validateApiKey(apiKey),
            0L
        );
    }

    private Optional<UserLlmCredentialEntity> selected(Long userId, AiCapability capability) {
        return capabilityRepository.find(userId, capability)
            .flatMap(choice -> repository.findByUserIdAndProviderKey(userId, choice.providerKey())
                .filter(entity -> matchesSelection(choice, entity)))
            .filter(entity -> supports(entity, capability));
    }

    private boolean matchesSelection(UserCapabilityRepository.Selection selected, UserLlmCredentialEntity entity) {
        return selected != null && selected.providerKey().equals(entity.getProviderKey())
            && Objects.equals(selected.credentialId(), entity.getId())
            && selected.modelName().equals(entity.getModelName());
    }

    private UserLlmCredentialEntity requireCredential(Long userId, AiCapability capability, String providerKey) {
        return repository.findByUserIdAndProviderKey(userId, normalizeProviderKey(providerKey))
            .filter(entity -> supports(entity, capability)).orElseThrow(() -> missing(capability));
    }

    private UserLlmCredentialEntity requirePinned(Long userId, AiCapability capability, String reference) {
        int separator = reference.indexOf('|');
        if (separator <= 0) {
            throw missing(capability);
        }
        var entity = requireCredential(userId, capability, reference.substring(0, separator));
        if (!(entity.getId() + "|" + entity.getCredentialVersion()).equals(reference.substring(separator + 1))) {
            throw new BusinessException(ErrorCode.USER_CREDENTIAL_NOT_CONFIGURED,
                capability.label() + "配置已变更，请重新发起本次操作。");
        }
        return entity;
    }

    private ProviderSecret secret(UserLlmCredentialEntity entity) {
        return new ProviderSecret(entity.getProviderKey(), presetRegistry.requireBaseUrl(entity.getProviderKey()),
            entity.getModelName(), decrypt(entity), entity.getCredentialVersion());
    }

    private boolean supports(UserLlmCredentialEntity entity, AiCapability capability) {
        return CredentialStatus.ACTIVE.name().equals(entity.getStatus())
            && (capability != AiCapability.TEXT || Boolean.TRUE.equals(entity.getChatEnabled()))
            && presetRegistry.supports(entity.getProviderKey(), entity.getModelName(), capability);
    }

    private BusinessException missing(AiCapability capability) {
        return new BusinessException(ErrorCode.USER_CREDENTIAL_NOT_CONFIGURED,
            "尚未选择可用的" + capability.label() + "模型，请前往「设置 → 我的 AI 配置」选择。");
    }

    private String validateProviderModel(String providerKey, String modelName) {
        String model = validateModelName(modelName);
        if (!presetRegistry.supports(providerKey, model, presetRegistry.capability(providerKey))) {
            throw new BusinessException(ErrorCode.USER_CREDENTIAL_MODEL_NOT_ALLOWED);
        }
        return model;
    }

    private AiExecutionContext toContext(UserLlmCredentialEntity entity) {
        return new AiExecutionContext(
            entity.getUserId(),
            entity.getProviderKey(),
            entity.getModelName(),
            entity.getCredentialVersion() == null ? 0L : entity.getCredentialVersion());
    }

    private UserLlmCredentialEntity loadActive(Long userId, String providerKey) {
        UserLlmCredentialEntity entity = repository
            .findByUserIdAndProviderKey(userId, normalizeProviderKey(providerKey))
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_CREDENTIAL_NOT_CONFIGURED));
        if (!CredentialStatus.ACTIVE.name().equals(entity.getStatus())) {
            throw new BusinessException(ErrorCode.USER_CREDENTIAL_INVALID);
        }
        return entity;
    }

    private String decrypt(UserLlmCredentialEntity entity) {
        try {
            return encryptionService.decrypt(entity.getApiKeyNonce(), entity.getApiKeyCiphertext());
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.USER_CREDENTIAL_DECRYPTION_FAILED);
        }
    }

    private void publishChanged(Long userId, String providerKey) {
        eventPublisher.publishEvent(UserCredentialChangedEvent.of(userId, providerKey));
    }

    private UserCredentialDTO toDto(UserLlmCredentialEntity entity) {
        boolean configured = CredentialStatus.ACTIVE.name().equals(entity.getStatus());
        return new UserCredentialDTO(
            entity.getProviderKey(),
            configured,
            entity.getModelName(),
            entity.getKeyTail(),
            entity.getUpdatedAt(),
            configured && Boolean.TRUE.equals(entity.getChatEnabled())
        );
    }

    private String normalizeProviderKey(String providerKey) {
        if (providerKey == null || providerKey.isBlank()) {
            throw new BusinessException(ErrorCode.USER_CREDENTIAL_PROVIDER_NOT_ALLOWED);
        }
        return providerKey.trim().toLowerCase(Locale.ROOT);
    }

    private String validateApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "API Key 不能为空");
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() < MIN_API_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "API Key 长度过短");
        }
        if (trimmed.length() > byokProperties.getMaxApiKeyLength()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "API Key 长度超出限制");
        }
        if (trimmed.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "API Key 只能包含可见 ASCII 字符");
        }
        return trimmed;
    }

    private String validateModelName(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模型名称不能为空");
        }
        String trimmed = modelName.trim();
        if (trimmed.length() > byokProperties.getMaxModelNameLength()) {
            throw new BusinessException(ErrorCode.USER_CREDENTIAL_MODEL_NOT_ALLOWED);
        }
        if (!MODEL_NAME_PATTERN.matcher(trimmed).matches()) {
            throw new BusinessException(ErrorCode.USER_CREDENTIAL_MODEL_NOT_ALLOWED);
        }
        return trimmed;
    }

    private String extractKeyTail(String apiKey) {
        // 短 Key 不回显尾部，避免泄露大部分内容。
        if (apiKey.length() <= MIN_API_KEY_LENGTH + KEY_TAIL_LENGTH) {
            return null;
        }
        return apiKey.substring(apiKey.length() - KEY_TAIL_LENGTH);
    }

    private int currentKeyVersion() {
        // 阶段一与阶段二使用固定版本 1；将来引入多密钥轮换时在此读取当前活跃版本。
        return 1;
    }

    /**
     * 解密后的凭证快照。
     *
     * <p>{@code toString()} 被覆盖以屏蔽 apiKey，避免误打日志。</p>
     */
    public record ProviderSecret(
        String providerKey,
        String baseUrl,
        String modelName,
        String apiKey,
        Long credentialVersion
    ) {
        @Override
        public String toString() {
            return "ProviderSecret[providerKey=" + providerKey
                + ", modelName=" + modelName
                + ", credentialVersion=" + credentialVersion
                + ", apiKey=***]";
        }
    }
}
