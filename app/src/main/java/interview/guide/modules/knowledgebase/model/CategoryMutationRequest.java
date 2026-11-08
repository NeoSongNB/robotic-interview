package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.Size;

/**
 * 分类维护请求。
 * rename 使用 from / to；remove 只使用 category。
 * 字段按需使用，不做统一 @NotBlank，具体必填项由各端点在 Service 里校验。
 */
public record CategoryMutationRequest(
    @Size(max = 64) String from,
    @Size(max = 64) String to,
    @Size(max = 64) String category
) {}
