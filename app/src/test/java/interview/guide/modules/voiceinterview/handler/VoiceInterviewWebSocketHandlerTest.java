package interview.guide.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.context.VoiceContextCompressor;
import interview.guide.modules.voiceinterview.service.DashscopeLlmService;
import interview.guide.modules.voiceinterview.service.QwenAsrService;
import interview.guide.modules.voiceinterview.service.QwenTtsService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import interview.guide.modules.voiceinterview.service.VolcengineSpeechService;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.usercredential.model.AiCapability;
import interview.guide.modules.usercredential.service.UserCredentialService.ProviderSecret;
import java.util.function.Consumer;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class VoiceInterviewWebSocketHandlerTest {

  @Mock
  private ObjectMapper objectMapper;
  @Mock
  private QwenAsrService sttService;
  @Mock
  private QwenTtsService ttsService;
  @Mock
  private VolcengineSpeechService volcSpeech;
  @Mock
  private DashscopeLlmService llmService;
  @Mock
  private VoiceInterviewService interviewService;
  @Mock
  private VoiceContextCompressor voiceContextCompressor;
  @Mock
  private interview.guide.modules.voiceinterview.context.VoiceHistoryLoader voiceHistoryLoader;
  @Mock
  private ObjectProvider<MeterRegistry> meterRegistryProvider;
  @Mock
  private interview.guide.modules.usercredential.service.UserCredentialService userCredentialService;

  private VoiceInterviewWebSocketHandler handler;

  @AfterEach
  void tearDown() {
    if (handler != null) {
      handler.destroy();
    }
  }

  @Test
  @DisplayName("默认关闭开场音频预热，应用启动时不调用云端 TTS")
  void shouldNotWarmupOpeningAudioByDefault() throws InterruptedException {
    VoiceInterviewProperties properties = new VoiceInterviewProperties();
    CountDownLatch ttsCalled = new CountDownLatch(1);
    lenient().when(ttsService.synthesize(anyString(), any())).thenAnswer(invocation -> {
      ttsCalled.countDown();
      return new byte[0];
    });
    handler = newHandler(properties);

    assertThat(properties.isOpeningAudioWarmupEnabled()).isFalse();

    handler.warmupOpeningAudioCache();

    assertThat(ttsCalled.await(300, TimeUnit.MILLISECONDS)).isFalse();
  }

  @Test
  @DisplayName("BYOK：即使显式开启预热也不调用云端 TTS，避免使用平台全局语音凭证")
  void shouldNeverWarmupOpeningAudioEvenWhenEnabled() throws InterruptedException {
    VoiceInterviewProperties properties = new VoiceInterviewProperties();
    properties.setOpeningAudioWarmupEnabled(true);
    properties.getOpening().setSkillQuestions(Map.of("java-backend", "你好，开始面试。"));
    properties.getOpening().setAlgorithmQuestion("");
    properties.getOpening().setBackendQuestion("");
    CountDownLatch ttsCalled = new CountDownLatch(1);
    lenient().when(ttsService.synthesize(anyString(), any())).thenAnswer(invocation -> {
      ttsCalled.countDown();
      return new byte[0];
    });
    handler = newHandler(properties);

    handler.warmupOpeningAudioCache();

    // 预热已按 BYOK 停用：启动期不会用开发者全局 Key 合成音频
    assertThat(ttsCalled.await(500, TimeUnit.MILLISECONDS)).isFalse();
  }

  @Test
  @DisplayName("上一轮提交后到达的临时字幕不会推送到当前轮")
  void shouldIgnoreLatePartialAfterPreviousTurnWasSubmitted() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    handler = newHandler(new VoiceInterviewProperties(), new ObjectMapper());
    prepareSttState("49", session, true);

    invokeHandleSttResult("49", "这是上一轮回答", false);

    verify(session, never()).sendMessage(any(TextMessage.class));
  }

  @Test
  @DisplayName("上一轮处理完成后当前轮临时字幕可以正常推送")
  void shouldSendPartialForCurrentTurnWhenNotProcessing() throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.isOpen()).thenReturn(true);
    handler = newHandler(new VoiceInterviewProperties(), new ObjectMapper());
    prepareSttState("49", session, false);

    invokeHandleSttResult("49", "这是当前轮回答", false);

    ArgumentCaptor<TextMessage> messageCaptor = ArgumentCaptor.forClass(TextMessage.class);
    verify(session).sendMessage(messageCaptor.capture());
    assertThat(messageCaptor.getValue().getPayload())
        .contains("\"type\":\"subtitle\"")
        .contains("这是当前轮回答");
  }

  @Test
  @DisplayName("未配置播报时，火山转写和选定的文本模型仍能完成一轮文字追问")
  void volcAsrWithTextOnlyOutput() throws Exception {
    var properties = new VoiceInterviewProperties();
    properties.setLlmStreamingEnabled(false);
    var session = mock(WebSocketSession.class);
    when(session.isOpen()).thenReturn(true);
    var entity = VoiceInterviewSessionEntity.builder().id(49L).userId(7L)
        .llmProvider("hunyuan|2|1").asrReference("volcengine-asr|4|1").build();
    when(interviewService.getSession(49L)).thenReturn(entity);
    var secret = new ProviderSecret("volcengine-asr", "wss://example.invalid", "volc.seedasr.sauc.duration", "test-key", 1L);
    when(userCredentialService.openCapabilitySecret(7L, AiCapability.ASR, "volcengine-asr|4|1")).thenReturn(secret);
    when(llmService.chat(eq("我的回答"), eq(entity), any())).thenReturn("请举一个例子。");
    doAnswer(invocation -> {
      Consumer<String> onFinal = invocation.getArgument(2);
      onFinal.accept("我的回答");
      return null;
    }).when(volcSpeech).start(eq("49"), eq(secret), any(), any(), any(), any());
    handler = newHandler(properties, new ObjectMapper());
    prepareSttState("49", session, false);

    Method connect = VoiceInterviewWebSocketHandler.class.getDeclaredMethod(
        "connectStt", String.class, WebSocketSession.class, boolean.class);
    connect.setAccessible(true);
    connect.invoke(handler, "49", session, false);
    Method submit = VoiceInterviewWebSocketHandler.class.getDeclaredMethod("flushMergedUtteranceToLlm", String.class);
    submit.setAccessible(true);
    submit.invoke(handler, "49");

    var messages = ArgumentCaptor.forClass(TextMessage.class);
    verify(session, timeout(3000).atLeast(3)).sendMessage(messages.capture());
    assertThat(messages.getAllValues()).anySatisfy(message ->
        assertThat(message.getPayload()).contains("请举一个例子。"));
    verifyNoInteractions(sttService, ttsService);
    verify(volcSpeech, never()).synthesize(any(), any());
  }

  private VoiceInterviewWebSocketHandler newHandler(VoiceInterviewProperties properties) {
    return newHandler(properties, objectMapper);
  }

  private VoiceInterviewWebSocketHandler newHandler(
      VoiceInterviewProperties properties,
      ObjectMapper handlerObjectMapper) {
    return new VoiceInterviewWebSocketHandler(
        handlerObjectMapper,
        sttService,
        ttsService,
        volcSpeech,
        llmService,
        interviewService,
        voiceContextCompressor,
        voiceHistoryLoader,
        properties,
        meterRegistryProvider,
        userCredentialService
    );
  }

  private void prepareSttState(
      String sessionId,
      WebSocketSession session,
      boolean processing) throws Exception {
    fieldMap("sessions").put(sessionId, session);

    Class<?> stateType = Class.forName(
        VoiceInterviewWebSocketHandler.class.getName() + "$SessionState");
    Constructor<?> constructor = stateType.getDeclaredConstructor();
    constructor.setAccessible(true);
    Object state = constructor.newInstance();
    Method isProcessing = stateType.getDeclaredMethod("isProcessing");
    isProcessing.setAccessible(true);
    ((AtomicBoolean) isProcessing.invoke(state)).set(processing);
    fieldMap("sessionStates").put(sessionId, state);
  }

  private void invokeHandleSttResult(
      String sessionId,
      String text,
      boolean finalSegment) throws Exception {
    Method method = VoiceInterviewWebSocketHandler.class.getDeclaredMethod(
        "handleSttResult", String.class, String.class, boolean.class);
    method.setAccessible(true);
    method.invoke(handler, sessionId, text, finalSegment);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> fieldMap(String fieldName) throws Exception {
    Field field = VoiceInterviewWebSocketHandler.class.getDeclaredField(fieldName);
    field.setAccessible(true);
    return (Map<String, Object>) field.get(handler);
  }
  @Nested
  @DisplayName("STT 日志隐私")
  class SttLogPrivacy {

    private final Logger handlerLogger =
        (Logger) LoggerFactory.getLogger(VoiceInterviewWebSocketHandler.class);
    private final ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
    private ch.qos.logback.classic.Level originalLevel;

    @BeforeEach
    void setUpAppender() {
      originalLevel = handlerLogger.getLevel();
      handlerLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
      logAppender.start();
      handlerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDownAppender() {
      handlerLogger.setLevel(originalLevel);
      handlerLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("迟到与最终转写片段日志不包含转写原文，只记录长度")
    void shouldNotLogSttText() throws Exception {
      String lateMarker = "迟到转写敏感标记MARKER-STT-LATE-2e9b";
      String finalMarker = "最终转写敏感标记MARKER-STT-FINAL-c47a";
      WebSocketSession session = mock(WebSocketSession.class);
      when(session.isOpen()).thenReturn(true);
      handler = newHandler(new VoiceInterviewProperties(), new ObjectMapper());
      prepareSttState("51", session, true);

      invokeHandleSttResult("51", lateMarker, false);

      prepareSttState("52", session, false);
      invokeHandleSttResult("52", finalMarker, true);

      StringBuilder logs = new StringBuilder();
      for (ILoggingEvent event : logAppender.list) {
        logs.append(event.getFormattedMessage()).append('\n');
      }
      assertThat(logs.toString()).doesNotContain(lateMarker);
      assertThat(logs.toString()).doesNotContain(finalMarker);
      assertThat(logs.toString()).contains("textLength=" + lateMarker.length());
      assertThat(logs.toString()).contains("textLength=" + finalMarker.length());
    }
  }
}
