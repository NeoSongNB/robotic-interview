package interview.guide.common.security;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.usercredential.service.UserCredentialService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;

/**
 * AI 访问闸门（BYOK 阶段二改造版）。
 *
 * <p><b>阶段一</b>：由于所有 AI 调用都走开发者全局 Key，这里用
 * "禁止普通用户访问 AI 写入接口"来保护平台密钥。</p>
 *
 * <p><b>阶段二</b>：业务 AI 调用已全部改为用户级凭证，平台全局 Key 不再参与用户请求。
 * 因此闸门语义改为<b>前置凭证就绪校验</b>：</p>
 * <ul>
 *   <li>用户没有可用的 Chat 凭证时，直接返回明确的配置错误
 *       {@code USER_CREDENTIAL_NOT_CONFIGURED}，而不是让请求进入业务链路后再失败；</li>
 *   <li>用户已配置凭证时放行，由各业务服务按自身能力（Chat / 语音）
 *       解析<b>该用户自己的</b>凭证；缺少对应能力时由服务层返回精确错误；</li>
 *   <li><b>不再</b>因为"平台全局 Key 被禁用"而拒绝请求，也<b>不会</b>回退到全局 Key。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformAiAccessInterceptor implements HandlerInterceptor {

    /**
     * 会实际触发 LLM / ASR / TTS 调用的接口模式。
     *
     * <p>只校验写入型方法（POST/PUT），查询与删除不受影响。</p>
     */
    private static final List<String> AI_WRITE_PATTERNS = List.of(
        "/api/resumes/**",
        "/api/interview/**",
        "/api/voice-interview/**"
    );

    private final CurrentUserProvider currentUserProvider;
    private final UserCredentialService userCredentialService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!isWriteMethod(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (AI_WRITE_PATTERNS.stream().noneMatch(pattern -> match(pattern, path))) {
            return true;
        }
        Long userId = currentUserProvider.requireUserId();
        if (userCredentialService.hasAnyUsableCredential(userId)) {
            return true;
        }
        log.warn("用户未配置任何 AI 凭证，拒绝 AI 接口调用: userId={}, path={}, method={}",
            userId, path, request.getMethod());
        throw new BusinessException(ErrorCode.USER_CREDENTIAL_NOT_CONFIGURED,
            "尚未配置 AI 凭证，请先前往「设置 - 我的 AI 配置」完成配置");
    }

    private boolean isWriteMethod(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method);
    }

    /**
     * 极简 Ant 匹配（仅支持 {@code /**} 后缀），避免额外引入匹配器依赖。
     */
    private boolean match(String pattern, String path) {
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        return pattern.equals(path);
    }
}
