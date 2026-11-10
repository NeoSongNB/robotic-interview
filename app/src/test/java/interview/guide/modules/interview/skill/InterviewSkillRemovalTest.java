package interview.guide.modules.interview.skill;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.usercredential.service.UserCredentialService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewPromptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class InterviewSkillRemovalTest {

  private InterviewSkillService skills;
  private InterviewSkillController controller;
  private PromptSanitizer sanitizer;

  @BeforeEach
  void setUp() throws Exception {
    sanitizer = mock(PromptSanitizer.class);
    skills = new InterviewSkillService(
        mock(LlmProviderRegistry.class), mock(UserCredentialService.class),
        mock(StructuredOutputInvoker.class), new DefaultResourceLoader(), sanitizer);
    skills.loadPresetSkills();
    controller = new InterviewSkillController(skills);
  }

  @Test
  @DisplayName("方向列表保留通用预设，已下线方向无法获取详情")
  void returnsOnlySupportedPresets() {
    assertThat(controller.listSkills().getData())
        .extracting(InterviewSkillService.SkillDTO::id)
        .containsExactlyInAnyOrder(
            "ai-agent-dev", "algorithm", "frontend", "java-backend",
            "python-backend", "system-design", "test-development");
    assertThat(controller.getSkill("java-backend").getData().id()).isEqualTo("java-backend");
    for (String removed : new String[]{"ali-backend", "bytedance-backend", "java-backend-tencent"}) {
      assertThatThrownBy(() -> controller.getSkill(removed))
          .isInstanceOf(BusinessException.class)
          .hasMessageContaining("未找到面试主题");
    }
  }

  @Test
  @DisplayName("旧语音会话使用通用提示，不调用已下线的预设")
  void historicalVoiceSessionUsesGenericPrompt() {
    var prompts = new VoiceInterviewPromptService(sanitizer, skills);
    assertThat(prompts.generateSystemPromptWithContext("ali-backend", null))
        .contains("语音面试输出约束")
        .doesNotContain("ali-backend", "加载该技能");
    assertThat(prompts.generateSystemPromptWithContext("algorithm", null))
        .contains("command: algorithm");
  }
}
