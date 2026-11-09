package interview.guide.common.security;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.usercredential.service.UserCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * AI 访问闸门：阶段二语义为「前置凭证就绪校验」，而不是「禁止普通用户访问」。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AI 访问闸门（用户级凭证就绪校验）")
class PlatformAiAccessInterceptorTest {

  private static final Long USER_ID = 5L;

  @Mock
  private CurrentUserProvider currentUserProvider;
  @Mock
  private UserCredentialService userCredentialService;

  private PlatformAiAccessInterceptor interceptor;

  @BeforeEach
  void setUp() {
    interceptor = new PlatformAiAccessInterceptor(currentUserProvider, userCredentialService);
  }

  @Test
  @DisplayName("未配置任何凭证时，AI 写入接口被拒绝并返回 USER_CREDENTIAL_NOT_CONFIGURED")
  void rejectsWhenNoCredential() {
    when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
    when(userCredentialService.hasAnyUsableCredential(USER_ID)).thenReturn(false);

    var request = post("/api/resumes/upload");

    assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getCode())
        .isEqualTo(ErrorCode.USER_CREDENTIAL_NOT_CONFIGURED.getCode());
  }

  @Test
  @DisplayName("已配置凭证时放行，具体缺少哪种能力由业务服务返回更精确的错误")
  void allowsWhenCredentialPresent() {
    when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
    when(userCredentialService.hasAnyUsableCredential(USER_ID)).thenReturn(true);

    assertThat(interceptor.preHandle(post("/api/interview/sessions"),
        new MockHttpServletResponse(), new Object())).isTrue();
  }

  @Test
  @DisplayName("查询 / 删除等非写入方法不受闸门影响")
  void skipsReadMethods() {
    var request = new MockHttpServletRequest("GET", "/api/interview/sessions");

    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
  }

  @Test
  @DisplayName("非 AI 接口不受影响")
  void skipsNonAiPaths() {
    var request = post("/api/auth/login");

    assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
  }

  private MockHttpServletRequest post(String uri) {
    return new MockHttpServletRequest("POST", uri);
  }
}
