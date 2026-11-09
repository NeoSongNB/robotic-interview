package interview.guide.modules.usercredential.dto;

/**
 * 当前用户 AI 能力就绪状态（供前端展示「Chat / 语音」各自的配置状态）。
 *
 * <p><b>字段白名单</b>：只允许出现供应商标识、模型名称与就绪布尔值。
 * 禁止新增 apiKey / ciphertext / nonce / baseUrl 字段。</p>
 *
 * @param chatReady          是否已具备可用的 Chat 凭证
 * @param chatProviderKey    当前生效的 Chat 供应商标识（未就绪为 null）
 * @param chatModelName      当前生效的 Chat 模型名称（未就绪为 null）
 * @param speechReady        是否可进行语音面试（ASR/TTS 需要 DashScope 凭证）
 * @param speechProviderKey  语音能力使用的供应商标识
 */
public record CredentialStatusDTO(
    boolean chatReady,
    String chatProviderKey,
    String chatModelName,
    boolean speechReady,
    String speechProviderKey
) {
}
