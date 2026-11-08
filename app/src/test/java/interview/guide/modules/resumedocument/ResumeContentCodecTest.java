package interview.guide.modules.resumedocument;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resumedocument.model.ResumeContent;
import interview.guide.modules.resumedocument.service.ResumeContentCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("在线简历结构化内容编解码")
class ResumeContentCodecTest {

  private ResumeContentCodec codec;

  @BeforeEach
  void setUp() {
    codec = new ResumeContentCodec(new ObjectMapper());
  }

  @Test
  @DisplayName("空白简历包含完整结构，所有数组为空且预留实习/获奖扩展位")
  void blankContentHasCompleteStructure() {
    ResumeContent content = codec.blank();
    assertThat(content.getBasicInfo()).isNotNull();
    assertThat(content.getEducation()).isEmpty();
    assertThat(content.getProjects()).isEmpty();
    assertThat(content.getSkills()).isEmpty();
    assertThat(content.getInternships()).isEmpty();
    assertThat(content.getAwards()).isEmpty();

    String json = codec.serialize(content);
    assertThat(json).contains("basicInfo", "education", "projects", "skills", "internships", "awards");
    assertThat(codec.deserialize(json).getBasicInfo().getName()).isEmpty();
  }

  @Test
  @DisplayName("由原始简历解析文本本地抽取基础信息，不重复调用大模型")
  void extractFromResumeTextLocally() {
    String text = """
        张三
        手机：13800138000  邮箱：zhangsan@example.com
        清华大学 计算机科学与技术 本科
        求职意向：Java 后端开发工程师
        专业技能
        Java、Spring Boot、Redis、MySQL
        项目经历
        智能面试平台
        """;
    ResumeContent content = codec.fromResumeText(text);

    assertThat(content.getBasicInfo().getName()).isEqualTo("张三");
    assertThat(content.getBasicInfo().getPhone()).isEqualTo("13800138000");
    assertThat(content.getBasicInfo().getEmail()).isEqualTo("zhangsan@example.com");
    assertThat(content.getBasicInfo().getSchool()).contains("清华大学");
    assertThat(content.getBasicInfo().getDegree()).isEqualTo("本科");
    assertThat(content.getBasicInfo().getTargetPosition()).contains("Java 后端");
    assertThat(content.getSkills()).contains("Java", "Spring Boot", "Redis", "MySQL");
    // 本地规则不臆造项目经历，由用户通过 AI 对话补充
    assertThat(content.getProjects()).isEmpty();
  }

  @Test
  @DisplayName("按章节抽取教育起止时间、项目技术栈与多条亮点，技能不被项目描述污染")
  void extractSectionedResumeText() {
      String text = """
          张三
          电话：13800000000  邮箱：qa@example.com
          求职意向：Java 后端开发

          教育经历
          大连理工大学 计算机科学与技术 本科 2019-09 至 2023-06

          技能
          Java, Spring Boot, PostgreSQL, Redis

          项目经历
          奶娃面试官（2025-01 至 2025-06）
          技术栈：Spring Boot、Spring AI、PostgreSQL、Redis
          项目介绍：面向求职者的 AI 在线面试平台。
          亮点一：Multi-Agent 协作完成结构化提问与追问。
          亮点二：RAG 知识库支撑个性化面试题。
          亮点三：Redis Stream 实现异步评估。
          """;
      ResumeContent content = codec.fromResumeText(text);

      assertThat(content.getBasicInfo().getSchool()).isEqualTo("大连理工大学");
      assertThat(content.getBasicInfo().getMajor()).isEqualTo("计算机科学与技术");
      assertThat(content.getEducation()).hasSize(1);
      ResumeContent.EducationItem education = content.getEducation().get(0);
      assertThat(education.getStartDate()).isEqualTo("2019-09");
      assertThat(education.getEndDate()).isEqualTo("2023-06");

      assertThat(content.getProjects()).hasSize(1);
      ResumeContent.ProjectItem project = content.getProjects().get(0);
      assertThat(project.getName()).isEqualTo("奶娃面试官");
      assertThat(project.getStartDate()).isEqualTo("2025-01");
      assertThat(project.getEndDate()).isEqualTo("2025-06");
      assertThat(project.getTechStack()).contains("Spring Boot", "Spring AI", "PostgreSQL", "Redis");
      assertThat(project.getSummary()).contains("AI 在线面试平台");
      assertThat(project.getHighlights()).hasSize(3);
      assertThat(project.getHighlights().get(0).getContent()).startsWith("Multi-Agent");

      assertThat(content.getSkills()).contains("Java", "Spring Boot", "PostgreSQL", "Redis");
      assertThat(content.getSkills()).noneMatch(skill -> skill.contains("亮点") || skill.contains("项目介绍"));
  }

  @Test
  @DisplayName("识别带分隔符手机号、保留整句技能描述、不把学院名当专业")
  void extractCommonChineseResumeLayout() {
      String text = """
          林思远
          男 · 1999 年生 · 深圳 | 电话 138-0000-1234 | 邮箱 linsiyuan@example.com | 求职意向：Java 后端开发工程师

          教育背景
          深圳大学 计算机与软件学院 2018.09 - 2022.06
          计算机科学与技术 本科 · GPA 3.7/4.0

          专业技能
          Java 基础扎实，熟悉集合、并发、JVM 内存模型与垃圾回收；熟悉 Spring Boot、Spring Cloud、MyBatis。
          熟悉 MySQL 索引与事务、Redis 缓存与分布式锁；了解 PostgreSQL 与向量检索。
          """;
      ResumeContent content = codec.fromResumeText(text);

      assertThat(content.getBasicInfo().getPhone()).isEqualTo("138-0000-1234");
      assertThat(content.getBasicInfo().getSchool()).isEqualTo("深圳大学");
      assertThat(content.getBasicInfo().getMajor()).isEqualTo("计算机科学与技术");
      assertThat(content.getSkills()).hasSizeLessThan(8);
      assertThat(content.getSkills())
          .noneMatch(skill -> skill.equals("并发") || skill.equals("熟悉集合"));
      assertThat(content.getSkills())
          .anyMatch(skill -> skill.contains("JVM 内存模型与垃圾回收"));
  }

  @Test
  @DisplayName("支持技能标题与内容同行，项目要点按列表符号抽取")
  void extractInlineSkillHeadingAndBullets() {
      String text = """
          李四
          专业技能：Java、Spring Boot、Redis
          项目经历
          校园二手交易平台
          - 使用 Spring Boot 与 Redis 实现下单与缓存
          """;
      ResumeContent content = codec.fromResumeText(text);

      assertThat(content.getSkills()).contains("Java", "Spring Boot", "Redis");
      assertThat(content.getProjects()).hasSize(1);
      ResumeContent.ProjectItem project = content.getProjects().get(0);
      assertThat(project.getName()).isEqualTo("校园二手交易平台");
      assertThat(project.getHighlights()).hasSize(1);
      assertThat(project.getHighlights().get(0).getContent()).contains("下单与缓存");
  }

  @Test
  @DisplayName("规范化为缺失记录生成稳定唯一 ID 并重排亮点序号")
  void normalizeGeneratesStableIds() {
    ResumeContent raw = codec.blank();
    ResumeContent.ProjectItem project = new ResumeContent.ProjectItem();
    ResumeContent.HighlightItem first = new ResumeContent.HighlightItem();
    first.setId("duplicate-id");
    first.setContent("亮点一");
    ResumeContent.HighlightItem second = new ResumeContent.HighlightItem();
    second.setId("duplicate-id");
    second.setContent("亮点二");
    project.setHighlights(new java.util.ArrayList<>(java.util.List.of(first, second)));
    raw.setProjects(new java.util.ArrayList<>(java.util.List.of(project)));

    ResumeContent normalized = codec.normalize(raw);
    ResumeContent.ProjectItem result = normalized.getProjects().get(0);
    assertThat(result.getId()).isNotBlank();
    assertThat(result.getHighlights()).hasSize(2);
    assertThat(result.getHighlights().get(0).getId())
        .isNotEqualTo(result.getHighlights().get(1).getId());
    assertThat(result.getHighlights())
        .extracting(ResumeContent.HighlightItem::getSortOrder)
        .containsExactly(1, 2);
  }

  @Test
  @DisplayName("超长字段在校验阶段拒绝写入")
  void rejectsOversizedText() {
    ResumeContent content = codec.blank();
    content.getBasicInfo().setName("x".repeat(201));
    assertThatThrownBy(() -> codec.validate(content))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo(ErrorCode.RESUME_DOCUMENT_CONTENT_INVALID.getCode());
  }

  @Test
  @DisplayName("非法 JSON 内容解析为业务异常而非静默吞掉")
  void rejectsMalformedJson() {
    assertThatThrownBy(() -> codec.deserialize("{not-json"))
        .isInstanceOf(BusinessException.class)
        .extracting(error -> ((BusinessException) error).getCode())
        .isEqualTo(ErrorCode.RESUME_DOCUMENT_CONTENT_INVALID.getCode());
  }
}
