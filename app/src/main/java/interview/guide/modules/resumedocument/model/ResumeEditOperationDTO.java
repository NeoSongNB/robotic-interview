package interview.guide.modules.resumedocument.model;

import java.util.List;

/**
 * AI 生成的一条「增量修改操作」，不是整份简历。
 *
 * <p>op: ADD / UPDATE / DELETE / REORDER；
 * target: BASIC_INFO / EDUCATION / INTERNSHIP / INTERNSHIP_HIGHLIGHT /
 * PROJECT / HIGHLIGHT / SKILL。</p>
 *
 * <p>单字段修改用 field + value；新增整条记录用对应 patch；
 * SKILL 与 REORDER 用 values；HIGHLIGHT 用 parentId 指定所属项目，
 * INTERNSHIP_HIGHLIGHT 用 parentId 指定所属实习。</p>
 */
public record ResumeEditOperationDTO(
    String op,
    String target,
    String id,
    String parentId,
    String field,
    String value,
    List<String> values,
    EducationPatch education,
    ProjectPatch project,
    HighlightPatch highlight,
    InternshipPatch internship
) {

    /** 兼容既有调用方：不带实习补丁的操作。 */
    public ResumeEditOperationDTO(
        String op, String target, String id, String parentId, String field, String value,
        List<String> values, EducationPatch education, ProjectPatch project,
        HighlightPatch highlight) {
        this(op, target, id, parentId, field, value, values,
            education, project, highlight, null);
    }

    /** highlights 为「工作与成果」文本列表，由后端生成稳定 ID 与 sortOrder。 */
    public record InternshipPatch(
        String company,
        String position,
        String startDate,
        String endDate,
        String description,
        List<String> highlights
    ) {}


    public record EducationPatch(
        String school,
        String major,
        String degree,
        String startDate,
        String endDate,
        String description
    ) {}

    /** highlights 为亮点文本列表，由后端生成稳定 ID 与 sortOrder。 */
    public record ProjectPatch(
        String name,
        String url,
        String summary,
        List<String> techStack,
        List<String> highlights,
        String startDate,
        String endDate
    ) {}

    public record HighlightPatch(
        String content,
        Integer sortOrder
    ) {}
}
