package interview.guide.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import java.time.Duration;

/**
 * Spring Security 配置。
 *
 * <p>要点：</p>
 * <ul>
 *   <li>无状态（STATELESS）：不创建 HttpSession，身份完全依赖 JWT。</li>
 *   <li>JWT 默认放在 HttpOnly + SameSite Cookie，前端 JS 不可读取，规避 XSS 窃取。</li>
 *   <li>启用 CSRF 双提交 Cookie 防护：令牌写入非 HttpOnly 的 XSRF-TOKEN Cookie，
 *       前端 axios 默认自动回写 X-XSRF-TOKEN 头。</li>
 * </ul>
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;
    private final AuthProperties authProperties;

    /** 无需认证即可访问的路径。 */
    private static final String[] PUBLIC_PATHS = {
        "/api/auth/register",
        "/api/auth/login",
        "/api/auth/csrf",
        "/api/health",
        "/actuator/health",
        "/actuator/health/**",
        "/v3/api-docs/**",
        "/swagger-ui/**",
        "/swagger-ui.html",
        "/error"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AuthProperties.Jwt jwt = authProperties.getJwt();

        CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieName(jwt.getCsrfCookieName());
        csrfRepository.setHeaderName(jwt.getCsrfHeaderName());
        csrfRepository.setCookieCustomizer(builder -> builder
            .path("/")
            .sameSite(jwt.getCookieSameSite())
            .secure(jwt.isCookieSecure()));

        // 强制立即解析 CSRF 令牌，确保首个请求就会下发 XSRF-TOKEN Cookie；
        // 使用非 XOR 处理器，使 Cookie 值与请求头值一致（axios 双提交 Cookie 约定的前提）。
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfRepository)
                .csrfTokenRequestHandler(csrfRequestHandler))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout.disable())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(restAuthenticationEntryPoint)
                .accessDeniedHandler(restAccessDeniedHandler))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(PUBLIC_PATHS).permitAll()
                .requestMatchers("/api/me/**").authenticated()
                .requestMatchers("/api/**").authenticated()
                .requestMatchers("/ws/**").authenticated()
                .anyRequest().permitAll())
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 供文档与运维检查展示的会话时长（秒）。
     */
    public long accessTokenTtlSeconds() {
        Duration ttl = authProperties.getJwt().getTtl();
        return Math.max(60, ttl.toSeconds());
    }
}
