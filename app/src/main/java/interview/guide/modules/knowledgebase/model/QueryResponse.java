package interview.guide.modules.knowledgebase.model;

import java.util.List;

/**
 * 知识库查询响应
 *
 * @param sources 本次回答实际引用的片段，供前端把 [S1] 等角标渲染成可点击出处
 */
public record QueryResponse(
    String answer,
    Long knowledgeBaseId,
    String knowledgeBaseName,
    List<QuerySource> sources
) {
    /** 兼容旧调用方：无检索片段时返回空列表而不是 null。 */
    public QueryResponse(String answer, Long knowledgeBaseId, String knowledgeBaseName) {
        this(answer, knowledgeBaseId, knowledgeBaseName, List.of());
    }
}
