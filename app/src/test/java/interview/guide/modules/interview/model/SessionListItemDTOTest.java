package interview.guide.modules.interview.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionListItemDTOTest {

  @Test
  @DisplayName("from 映射面试会话基础字段")
  void shouldMapBasicFields() {
    InterviewSessionEntity entity = new InterviewSessionEntity();
    entity.setSessionId("s2");
    entity.setTotalQuestions(null);

    SessionListItemDTO dto = SessionListItemDTO.from(entity);

    assertThat(dto.sessionId()).isEqualTo("s2");
    assertThat(dto.sourceType()).isEqualTo("NORMAL");
    assertThat(dto.totalQuestions()).isZero();
  }
}
