package interview.guide.modules.knowledgebase.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 批量操作的知识库 id 列表；服务端仍逐条做归属校验。 */
public record BatchIdsRequest(
    @NotEmpty(message = "至少选择一个知识库")
    @Size(max = 50, message = "单次最多操作 50 个知识库")
    List<Long> ids
) {}
