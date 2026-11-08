package interview.guide.modules.knowledgebase.model;

/**
 * 一次知识库问答里实际参与生成的检索片段。
 *
 * <p>下标与答案里的 [S1] / [S2] 引用一一对应。只暴露展示与跳转所需的字段，
 * 不返回片段全文以外的内容；storageUrl 由服务端按已校验归属的知识库实体填充，
 * 不信任向量库 metadata 里的任意文件名。</p>
 */
public record QuerySource(
    int index,
    Long knowledgeBaseId,
    String knowledgeBaseName,
    String filename,
    Integer chunkIndex,
    Double score,
    String excerpt,
    String storageUrl
) {}
