package interview.guide.modules.usercredential.dto;

import interview.guide.modules.usercredential.model.AiCapability;

/**
 * 可供用户选择的供应商预设。
 *
 * <p>{@code baseUrl} 由服务端下发仅供展示，前端不可编辑、也不参与提交。</p>
 */
public record ProviderPresetDTO(
    String providerKey,
    String displayName,
    String baseUrl,
    String defaultModel,
    boolean configured,
    AiCapability capability
) {
}
