package interview.guide.modules.interview.service;

import interview.guide.common.security.CurrentUserProvider;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewPersistenceServiceTest {

  @Mock
  private InterviewSessionRepository sessionRepository;

  @Mock
  private InterviewAnswerRepository answerRepository;

  @Mock
  private ResumeRepository resumeRepository;

  @Mock
  private CurrentUserProvider currentUserProvider;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("普通面试保存时来源类型为 NORMAL")
  void shouldSaveNormalSession() {
    InterviewPersistenceService service = newService();
    when(sessionRepository.save(any(InterviewSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.saveSession("sid2", null, 1, List.of(), "dashscope", "java-backend", "mid");

    ArgumentCaptor<InterviewSessionEntity> captor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
    verify(sessionRepository).save(captor.capture());
    InterviewSessionEntity saved = captor.getValue();
    assertThat(saved.getSourceType()).isEqualTo("NORMAL");
    assertThat(saved.getSessionId()).isEqualTo("sid2");
  }

  private InterviewPersistenceService newService() {
    return new InterviewPersistenceService(
        sessionRepository,
        answerRepository,
        currentUserProvider,
        resumeRepository,
        objectMapper
    );
  }
}
