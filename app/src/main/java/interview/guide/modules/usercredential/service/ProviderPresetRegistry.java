package interview.guide.modules.usercredential.service;

import interview.guide.common.config.ByokProperties;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.usercredential.dto.ProviderPresetDTO;
import interview.guide.modules.usercredential.model.AiCapability;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 用户可选供应商白名单。
 *
 * <p>设计要点：<b>Base URL 只能来自服务端配置</b>（{@code app.ai.providers.<key>.base-url}），
 * 请求体中的任何地址字段都会被忽略，从根源上消除 SSRF。</p>
 */
@Component
@RequiredArgsConstructor
public class ProviderPresetRegistry {

    private static final Map<String, String> DISPLAY_NAMES = Map.of(
        "hunyuan", "腾讯混元（TokenHub）",
        "dashscope", "阿里云百炼 DashScope",
        "kimi", "月之暗面 Kimi",
        "deepseek", "DeepSeek",
        "glm", "智谱 GLM",
        "volcengine", "火山引擎方舟（豆包）"
    );

    private final ByokProperties byokProperties;
    private final LlmProviderProperties llmProviderProperties;

    public boolean isAllowed(String providerKey) {
        if (providerKey == null || providerKey.isBlank()) {
            return false;
        }
        String normalized = normalize(providerKey);
        return "hunyuan".equals(normalized) || SpeechPresets.ALL.containsKey(normalized) || byokProperties.getAllowedProviders() != null
            && byokProperties.getAllowedProviders().stream()
                .map(this::normalize)
                .anyMatch(normalized::equals);
    }

    /**
     * 解析供应商的 Base URL。
     *
     * @throws BusinessException 供应商不在白名单或平台未配置其地址
     */
    public String requireBaseUrl(String providerKey) {
        String normalized = normalize(providerKey);
        if (!isAllowed(normalized)) {
            throw new BusinessException(ErrorCode.USER_CREDENTIAL_PROVIDER_NOT_ALLOWED);
        }
        var speech = SpeechPresets.ALL.get(normalized);
        if (speech != null) {
            return speech.endpoint();
        }
        Map<String, LlmProviderProperties.ProviderConfig> providers =
            llmProviderProperties.getProviders();
        LlmProviderProperties.ProviderConfig config =
            providers == null ? null : providers.get(normalized);
        String baseUrl = config == null ? null : config.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BusinessException(ErrorCode.USER_CREDENTIAL_PROVIDER_NOT_ALLOWED);
        }
        return baseUrl.trim();
    }

    public AiCapability capability(String providerKey) {
        var preset = SpeechPresets.ALL.get(normalize(providerKey));
        return preset == null ? AiCapability.TEXT : preset.capability();
    }

    public boolean supports(String providerKey, String model, AiCapability capability) {
        if (!isAllowed(providerKey) || capability(providerKey) != capability || model == null || model.isBlank()) {
            return false;
        }
        var speech = SpeechPresets.ALL.get(normalize(providerKey));
        if (speech != null) {
            return speech.model().equals(model);
        }
        var providers = llmProviderProperties.getProviders();
        var config = providers == null ? null : providers.get(normalize(providerKey));
        return config != null && config.getBaseUrl() != null && !config.getBaseUrl().isBlank();
    }

    public String defaultModel(String providerKey) {
        String normalized = normalize(providerKey);
        var speech = SpeechPresets.ALL.get(normalized);
        if (speech != null) {
            return speech.model();
        }
        Map<String, LlmProviderProperties.ProviderConfig> providers =
            llmProviderProperties.getProviders();
        LlmProviderProperties.ProviderConfig config =
            providers == null ? null : providers.get(normalized);
        return config == null ? null : config.getModel();
    }

    public String displayName(String providerKey) {
        String normalized = normalize(providerKey);
        var speech = SpeechPresets.ALL.get(normalized);
        return speech == null ? DISPLAY_NAMES.getOrDefault(normalized, normalized) : speech.name();
    }

    /**
     * 列出白名单内所有可用供应商预设。
     *
     * @param configuredKeys 当前用户已配置的 providerKey 集合
     */
    public List<ProviderPresetDTO> listPresets(Set<String> configuredKeys) {
        List<ProviderPresetDTO> presets = new ArrayList<>();
        var textKeys = new java.util.LinkedHashSet<String>();
        textKeys.add("hunyuan");
        textKeys.addAll(byokProperties.getAllowedProviders());
        for (String rawKey : textKeys) {
            String key = normalize(rawKey);
            String baseUrl;
            try {
                baseUrl = requireBaseUrl(key);
            } catch (BusinessException e) {
                // 平台未配置该供应商的地址时直接跳过，不暴露给前端。
                continue;
            }
            presets.add(new ProviderPresetDTO(
                key,
                displayName(key),
                baseUrl,
                defaultModel(key),
                configuredKeys != null && configuredKeys.contains(key), capability(key)
            ));
        }
        SpeechPresets.ALL.keySet().stream().sorted().forEach(key -> presets.add(new ProviderPresetDTO(
            key, displayName(key), requireBaseUrl(key), defaultModel(key),
            configuredKeys != null && configuredKeys.contains(key), capability(key))));
        return presets;
    }

    private String normalize(String providerKey) {
        return providerKey == null ? "" : providerKey.trim().toLowerCase(Locale.ROOT);
    }
}
