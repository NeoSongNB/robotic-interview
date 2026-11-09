package interview.guide.modules.usercredential.dto;

import java.time.LocalDateTime;

/**
 * 用户凭证对外视图。
 *
 * <p><b>字段白名单</b>：只允许暴露下列字段。禁止新增 apiKey、
 * ciphertext、nonce、baseUrl 等字段，避免凭证或内部结构外泄。</p>
 *
 * @param providerKey      供应商标识
 * @param configured       凭证是否已配置且处于 ACTIVE 状态
 * @param modelName        用户配置的 Chat 模型名称
 * @param keyTail          明文末尾 4 位，仅用于脱敏展示
 * @param updatedAt        最后更新时间
 * @param chatEnabled      该凭证可用于 Chat
 */
public record UserCredentialDTO(
    String providerKey,
    boolean configured,
    String modelName,
    String keyTail,
    LocalDateTime updatedAt,
    boolean chatEnabled
) {
    public static UserCredentialDTO notConfigured(String providerKey) {
        return new UserCredentialDTO(providerKey, false, null, null, null, false);
    }
}
