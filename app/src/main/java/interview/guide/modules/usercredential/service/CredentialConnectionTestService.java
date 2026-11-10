package interview.guide.modules.usercredential.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.usercredential.dto.CredentialTestResultDTO;
import interview.guide.modules.usercredential.dto.TestCredentialRequest;
import interview.guide.modules.usercredential.service.UserCredentialService.ProviderSecret;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * 用户级模型连接测试。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>真实连通性</b>：实际向供应商发送一次极轻量请求（"ping"），
 *       而不是只检查数据库里有没有 Key；</li>
 *   <li><b>用户级凭证</b>：已保存场景解密当前用户凭证；未保存场景使用请求体里的临时凭证；</li>
 *   <li><b>绝不使用开发者全局 Key</b>；</li>
 *   <li><b>不泄漏凭证</b>：不记录 apiKey、不把原始堆栈返回前端，
 *       返回前统一做密钥形态脱敏。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CredentialConnectionTestService {

    /** 明显是密钥的片段：sk-xxx / 32 位以上无分隔长串等。 */
    private static final Pattern SECRET_LIKE = Pattern.compile("(?i)(sk-[A-Za-z0-9_\\-]{4,}|[A-Za-z0-9]{24,})");
    private static final String PROBE_PROMPT = "ping。请只回复 OK。";

    private final LlmProviderRegistry llmProviderRegistry;
    private final UserCredentialService userCredentialService;

    /**
     * 使用「已保存」的凭证做连接测试。
     */
    public CredentialTestResultDTO testSaved(Long userId, String providerKey) {
        ProviderSecret secret;
        try {
            secret = userCredentialService.openChatSecret(userId, providerKey);
        } catch (BusinessException e) {
            // 未配置/解密失败/禁止的供应商：直接返回明确错误码，绝不去找平台 Key 兜底
            return failure(providerKey, null, e);
        }
        return probe(secret);
    }

    /**
     * 使用「未保存」的临时凭证做连接测试（不会写入数据库）。
     */
    public CredentialTestResultDTO testTransient(Long userId, TestCredentialRequest request) {
        ProviderSecret secret;
        try {
            secret = userCredentialService.openTransientSecret(request.providerKey(), request.apiKey());
        } catch (BusinessException e) {
            return failure(request.providerKey(), request.modelName(), e);
        }
        // 模型名以请求为准（用户在表单上可能改了模型但还没保存）
        String model = request.modelName() == null || request.modelName().isBlank()
            ? secret.modelName() : request.modelName().trim();
        log.info("临时凭证连接测试: userId={}, providerKey={}, model={}", userId, secret.providerKey(), model);
        return probe(new ProviderSecret(secret.providerKey(), secret.baseUrl(), model, secret.apiKey(), 0L));
    }

    private CredentialTestResultDTO failure(String providerKey, String modelName, BusinessException e) {
        log.warn("模型连接测试无法进行: providerKey={}, code={}", providerKey, e.getCode());
        return new CredentialTestResultDTO(false, providerKey, modelName, 0L,
            safeMessage(e), String.valueOf(e.getCode()));
    }

    private CredentialTestResultDTO probe(ProviderSecret secret) {
        long start = System.nanoTime();
        try {
            ChatClient client = llmProviderRegistry.buildEphemeralChatClient(
                secret.providerKey(), secret.baseUrl(), secret.apiKey(), secret.modelName());
            String reply = client.prompt().user(PROBE_PROMPT).call().content();
            long latency = (System.nanoTime() - start) / 1_000_000;
            if (reply == null) {
                return new CredentialTestResultDTO(false, secret.providerKey(), secret.modelName(),
                    latency, "供应商返回空响应，请确认模型名称是否正确", null);
            }
            log.info("模型连接测试成功: providerKey={}, model={}, latencyMs={}",
                secret.providerKey(), secret.modelName(), latency);
            return new CredentialTestResultDTO(true, secret.providerKey(), secret.modelName(),
                latency, "连接成功，模型可用", null);
        } catch (BusinessException e) {
            long latency = (System.nanoTime() - start) / 1_000_000;
            log.warn("模型连接测试失败(BusinessException): providerKey={}, model={}, code={}",
                secret.providerKey(), secret.modelName(), e.getCode());
            return new CredentialTestResultDTO(false, secret.providerKey(), secret.modelName(),
                latency, safeMessage(e), String.valueOf(e.getCode()));
        } catch (Exception e) {
            long latency = (System.nanoTime() - start) / 1_000_000;
            // 只记录摘要，异常明细可能带有请求 URL 等内部信息
            log.warn("模型连接测试失败: providerKey={}, model={}, error={}",
                secret.providerKey(), secret.modelName(), ErrorLogSanitizer.summarize(e));
            boolean authError = looksLikeAuthFailure(e);
            String message = authError
                ? "供应商认证失败，请检查 API Key 是否正确或已过期"
                : "连接失败：" + redact(e.getMessage());
            return new CredentialTestResultDTO(false, secret.providerKey(), secret.modelName(),
                latency, message,
                authError ? String.valueOf(ErrorCode.PROVIDER_AUTHENTICATION_FAILED.getCode()) : null);
        }
    }

    private boolean looksLikeAuthFailure(Throwable e) {
        String text = collectMessages(e).toLowerCase();
        return text.contains("401") || text.contains("403") || text.contains("invalid api key")
            || text.contains("unauthorized") || text.contains("authentication")
            || text.contains("invalidapikey") || text.contains("incorrect api key");
    }

    private String collectMessages(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable current = e;
        int depth = 0;
        while (current != null && depth < 6) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(' ');
            }
            current = current.getCause();
            depth++;
        }
        return sb.toString();
    }

    /**
     * 脱敏：去掉可能出现的密钥形态字符串，并限制长度，避免把内部堆栈返回前端。
     */
    private String redact(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误，请稍后重试";
        }
        String withoutNewlines = message.replaceAll("\\s+", " ").trim();
        String masked = SECRET_LIKE.matcher(withoutNewlines).replaceAll("***");
        return masked.length() > 200 ? masked.substring(0, 200) + "…" : masked;
    }

    private String safeMessage(BusinessException e) {
        return e.getMessage() == null ? "连接失败，请检查配置" : redact(e.getMessage());
    }
}
