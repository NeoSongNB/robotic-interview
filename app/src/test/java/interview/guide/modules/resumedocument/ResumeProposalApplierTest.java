package interview.guide.modules.resumedocument;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resumedocument.model.ResumeContent;
import interview.guide.modules.resumedocument.model.ResumeEditOperationDTO;
import interview.guide.modules.resumedocument.model.ResumeEditProposalDTO;
import interview.guide.modules.resumedocument.service.ResumeContentCodec;
import interview.guide.modules.resumedocument.service.ResumeProposalApplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AI 修改提案校验与应用")
class ResumeProposalApplierTest {

  private ResumeContentCodec codec;
  private ResumeProposalApplier applier;

  @BeforeEach
  void setUp() {
    codec = new ResumeContentCodec(new ObjectMapper());
    applier = new ResumeProposalApplier(codec);
  }

  private ResumeContent contentWithProject() {
    ResumeContent content = codec.blank();
    ResumeContent.EducationItem education = new ResumeContent.EducationItem();
    education.setId("education-1");
    education.setSchool("原学校");
    content.setEducation(new ArrayList<>(List.of(education)));

    ResumeContent.HighlightItem first = new ResumeContent.HighlightItem();
    first.setId("highlight-1");
    first.setContent("Multi-Agent 编排");
    first.setSortOrder(1);
    ResumeContent.HighlightItem second = new ResumeContent.HighlightItem();
    second.setId("highlight-2");
    second.setContent("RAG 检索增强");
    second.setSortOrder(2);

    ResumeContent.ProjectItem project = new ResumeContent.ProjectItem();
    project.setId("project-1");
    project.setName("智能面试平台");
    project.setSummary("基于 Spring Boot 的面试平台");
    project.setHighlights(new ArrayList<>(List.of(first, second)));
    content.setProjects(new ArrayList<>(List.of(project)));
    return content;
  }

  private ResumeEditOperationDTO op(
      String op, String target, String id, String parentId, String field, String value,
      List<String> values, ResumeEditOperationDTO.EducationPatch education,
      ResumeEditOperationDTO.ProjectPatch project,
      ResumeEditOperationDTO.HighlightPatch highlight) {
    return new ResumeEditOperationDTO(
        op, target, id, parentId, field, value, values, education, project, highlight);
  }

  private ResumeEditOperationDTO simple(String op, String target, String field, String value) {
    return op(op, target, null, null, field, value, null, null, null, null);
  }

  private ResumeEditProposalDTO proposal(ResumeEditOperationDTO... operations) {
    return new ResumeEditProposalDTO(
        "已生成修改提案", List.of("修改说明"), List.of(operations), false, null, 0L);
  }

  private void assertInvalid(Runnable action) {
    assertThatThrownBy(action::run)
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo(ErrorCode.RESUME_EDIT_PROPOSAL_INVALID.getCode());
  }

  @Test
  @DisplayName("新增教育经历不影响已有项目")
  void addsEducation() {
    var patch = new ResumeEditOperationDTO.EducationPatch(
        "北京大学", "软件工程", "硕士", "2022-09", "2026-06", "研究方向：分布式系统");
    var result = applier.apply(contentWithProject(), proposal(
        op("ADD", "EDUCATION", null, null, null, null, null, patch, null, null)));

    assertThat(result.getEducation()).hasSize(2);
    assertThat(result.getEducation().get(1).getSchool()).isEqualTo("北京大学");
    assertThat(result.getEducation().get(1).getId()).isNotBlank();
    assertThat(result.getProjects()).hasSize(1);
    assertThat(result.getProjects().get(0).getName()).isEqualTo("智能面试平台");
  }

  @Test
  @DisplayName("新增项目时为每个技术亮点生成独立稳定 ID 与序号")
  void addsProjectWithMultipleHighlights() {
    var patch = new ResumeEditOperationDTO.ProjectPatch(
        "奶娃面试官", "", "AI 智能面试平台",
        List.of("Spring AI", "Redis"),
        List.of("Multi-Agent 编排", "RAG 检索增强", "SSE 流式输出"),
        "2025-01", "");
    var result = applier.apply(contentWithProject(), proposal(
        op("ADD", "PROJECT", null, null, null, null, null, null, patch, null)));

    assertThat(result.getProjects()).hasSize(2);
    ResumeContent.ProjectItem added = result.getProjects().get(1);
    assertThat(added.getName()).isEqualTo("奶娃面试官");
    assertThat(added.getTechStack()).containsExactly("Spring AI", "Redis");
    assertThat(added.getHighlights()).hasSize(3);
    assertThat(added.getHighlights())
        .extracting(ResumeContent.HighlightItem::getId)
        .doesNotHaveDuplicates()
        .allSatisfy(id -> assertThat(id).isNotBlank());
    assertThat(added.getHighlights())
        .extracting(ResumeContent.HighlightItem::getSortOrder)
        .containsExactly(1, 2, 3);
  }

  @Test
  @DisplayName("删除指定技术亮点而保留其它亮点")
  void deletesOnlyTargetHighlight() {
    var result = applier.apply(contentWithProject(), proposal(
        op("DELETE", "HIGHLIGHT", "highlight-1", null, null, null, null, null, null, null)));

    assertThat(result.getProjects().get(0).getHighlights()).hasSize(1);
    assertThat(result.getProjects().get(0).getHighlights().get(0).getContent())
        .isEqualTo("RAG 检索增强");
  }

  @Test
  @DisplayName("按提案调整技术亮点顺序并重排序号")
  void reordersHighlights() {
    var result = applier.apply(contentWithProject(), proposal(
        op("REORDER", "HIGHLIGHT", null, "project-1", null, null,
            List.of("highlight-2", "highlight-1"), null, null, null)));

    assertThat(result.getProjects().get(0).getHighlights())
        .extracting(ResumeContent.HighlightItem::getContent)
        .containsExactly("RAG 检索增强", "Multi-Agent 编排");
    assertThat(result.getProjects().get(0).getHighlights())
        .extracting(ResumeContent.HighlightItem::getSortOrder)
        .containsExactly(1, 2);
  }

  @Test
  @DisplayName("润色项目介绍只改目标字段，其它字段保持原样")
  void updatesSingleFieldOnly() {
    var result = applier.apply(contentWithProject(), proposal(
        op("UPDATE", "PROJECT", "project-1", null, "summary",
            "优化后的项目介绍", null, null, null, null)));

    assertThat(result.getProjects().get(0).getSummary()).isEqualTo("优化后的项目介绍");
    assertThat(result.getProjects().get(0).getName()).isEqualTo("智能面试平台");
    assertThat(result.getProjects().get(0).getHighlights()).hasSize(2);
  }

  @Test
  @DisplayName("信息不足的追问型提案合法且不包含任何修改操作")
  void acceptsClarificationProposal() {
    var clarification = new ResumeEditProposalDTO(
        "还需要一些信息", List.of(), List.of(), true, "请补充项目名称和技术亮点", 0L);

    assertThatCode(() -> applier.validate(contentWithProject(), clarification))
        .doesNotThrowAnyException();
    ResumeContent result = applier.apply(contentWithProject(), clarification);
    assertThat(result.getProjects().get(0).getName()).isEqualTo("智能面试平台");
  }

  @Test
  @DisplayName("拒绝修改或删除不存在的记录")
  void rejectsUnknownTargetId() {
    var invalid = proposal(
        op("DELETE", "PROJECT", "not-exist", null, null, null, null, null, null, null));
    assertInvalid(() -> applier.apply(contentWithProject(), invalid));
  }

  @Test
  @DisplayName("拒绝不支持的操作类型")
  void rejectsUnknownOperation() {
    assertInvalid(() -> applier.apply(contentWithProject(),
        proposal(simple("DROP", "PROJECT", null, null))));
  }

  @Test
  @DisplayName("拒绝亮点重排中遗漏现有 ID 的提案")
  void rejectsInvalidReorder() {
    var invalid = proposal(op("REORDER", "HIGHLIGHT", null, "project-1", null, null,
        List.of("highlight-1"), null, null, null));
    assertInvalid(() -> applier.apply(contentWithProject(), invalid));
  }

  @Test
  @DisplayName("拒绝既无修改操作又非追问的无效结构化输出")
  void rejectsEmptyProposal() {
    var invalid = new ResumeEditProposalDTO(
        "我不知道该改什么", List.of(), List.of(), false, null, 0L);
    assertInvalid(() -> applier.validate(contentWithProject(), invalid));
  }

  @Test
  @DisplayName("用户取消修改（不调用应用入口）时简历内容保持不变")
  void cancelDoesNotChangeContent() {
    ResumeContent content = contentWithProject();
    String before = codec.serialize(codec.normalize(content));
    // 前端取消后直接丢弃提案，后端没有任何写入入口被调用
    var discarded = proposal(
        op("DELETE", "PROJECT", "project-1", null, null, null, null, null, null, null));
    assertThat(discarded.operations()).hasSize(1);
    assertThat(codec.serialize(codec.normalize(content))).isEqualTo(before);
  }

  private ResumeContent contentWithInternship() {
    ResumeContent content = codec.blank();
    ResumeContent.HighlightItem first = new ResumeContent.HighlightItem();
    first.setId("ih-1");
    first.setContent("负责订单模块接口开发");
    first.setSortOrder(1);
    ResumeContent.HighlightItem second = new ResumeContent.HighlightItem();
    second.setId("ih-2");
    second.setContent("用单测验证接口边界");
    second.setSortOrder(2);
    ResumeContent.InternshipItem internship = new ResumeContent.InternshipItem();
    internship.setId("intern-1");
    internship.setCompany("某科技");
    internship.setPosition("后端实习生");
    internship.setDescription("参与后台接口开发与联调");
    internship.setHighlights(new ArrayList<>(List.of(first, second)));
    content.setInternships(new ArrayList<>(List.of(internship)));
    return content;
  }

  private ResumeEditOperationDTO internshipOp(
      String op, String target, String id, String parentId,
      ResumeEditOperationDTO.InternshipPatch internship,
      ResumeEditOperationDTO.HighlightPatch highlight, List<String> values) {
    return new ResumeEditOperationDTO(
        op, target, id, parentId, null, null, values, null, null, highlight, internship);
  }

  @Test
  @DisplayName("新增实习经历与逐条工作与成果，旧 description 保留且每条有稳定 ID")
  void addsInternshipWithHighlights() {
    var patch = new ResumeEditOperationDTO.InternshipPatch(
        "腾讯", "后端实习生", "2025-07", "2025-09",
        "参与后台接口开发与联调", List.of("负责订单模块接口开发", "用单测验证接口边界"));
    var result = applier.apply(contentWithProject(), proposal(
        internshipOp("ADD", "INTERNSHIP", null, null, patch, null, null)));

    assertThat(result.getInternships()).hasSize(1);
    var internship = result.getInternships().get(0);
    assertThat(internship.getId()).isNotBlank();
    assertThat(internship.getCompany()).isEqualTo("腾讯");
    assertThat(internship.getDescription()).isEqualTo("参与后台接口开发与联调");
    assertThat(internship.getHighlights())
        .extracting(ResumeContent.HighlightItem::getContent)
        .containsExactly("负责订单模块接口开发", "用单测验证接口边界");
    assertThat(internship.getHighlights())
        .extracting(ResumeContent.HighlightItem::getSortOrder)
        .containsExactly(1, 2);
    assertThat(internship.getHighlights().get(0).getId()).isNotBlank();
    assertThat(result.getProjects()).hasSize(1);
    codec.validate(result);
  }

  @Test
  @DisplayName("实习成果重排必须是现有 ID 的完整排列，否则拒绝提案")
  void reordersInternshipHighlightsOnlyWithFullPermutation() {
    var result = applier.apply(contentWithInternship(), proposal(
        internshipOp("REORDER", "INTERNSHIP_HIGHLIGHT", null, "intern-1", null, null,
            List.of("ih-2", "ih-1"))));
    assertThat(result.getInternships().get(0).getHighlights())
        .extracting(ResumeContent.HighlightItem::getContent)
        .containsExactly("用单测验证接口边界", "负责订单模块接口开发");

    ResumeContent content = contentWithInternship();
    var invalid = proposal(internshipOp("REORDER", "INTERNSHIP_HIGHLIGHT", null,
        "intern-1", null, null, List.of("ih-1")));
    assertInvalid(() -> applier.apply(content, invalid));
  }

  @Test
  @DisplayName("确认后的提案只更新目标实习成果条目")
  void updatesOnlyTargetInternshipHighlight() {
    var update = new ResumeEditOperationDTO(
        "UPDATE", "INTERNSHIP_HIGHLIGHT", "ih-1", null, "content",
        "负责订单模块接口开发并补充边界用例", null, null, null, null, null);
    var result = applier.apply(contentWithInternship(), proposal(update));

    assertThat(result.getInternships().get(0).getHighlights())
        .extracting(ResumeContent.HighlightItem::getContent)
        .containsExactly("负责订单模块接口开发并补充边界用例", "用单测验证接口边界");
    assertThat(result.getInternships().get(0).getDescription())
        .isEqualTo("参与后台接口开发与联调");
  }

  @Test
  @DisplayName("缺少事实只追问、拒绝或校验失败的提案都不会写入数据")
  void clarificationAndRejectedProposalDoNotWrite() {
    ResumeContent content = contentWithInternship();
    String before = codec.serialize(codec.normalize(content));

    var clarify = new ResumeEditProposalDTO(
        "需要补充信息", List.of(), List.of(), true, "你具体负责了哪个模块？", 0L);
    assertThatCode(() -> applier.validate(content, clarify)).doesNotThrowAnyException();

    var invalid = proposal(internshipOp("ADD", "INTERNSHIP_HIGHLIGHT", null,
        "not-exist", null, new ResumeEditOperationDTO.HighlightPatch("编造的成果", 1), null));
    assertInvalid(() -> applier.apply(content, invalid));

    assertThat(codec.serialize(codec.normalize(content))).isEqualTo(before);
  }
}
