package jp.vinsight.api.config;

import jakarta.servlet.DispatcherType;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import jp.vinsight.api.admin.AdminRepository;
import jp.vinsight.api.member.MemberRepository;

/**
 * Who may call what. Anything not listed here is refused (deny by default).
 * Spring Security's own CSRF tokens are replaced by OriginCheckFilter, which the existing Next.js screens already satisfy.
 */
@Configuration
public class SecurityConfig {

  @Bean
  SecurityFilterChain api(HttpSecurity http, AppProperties props, MemberRepository members, AdminRepository admins, Clock clock) throws Exception {
    http
        .csrf(c -> c.disable())
        .formLogin(f -> f.disable())
        .httpBasic(b -> b.disable())
        .logout(l -> l.disable())
        .requestCache(r -> r.disable())
        .anonymous(a -> {})
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.NEVER))
        .securityContext(c -> c.securityContextRepository(new NullSecurityContextRepository()))
        .addFilterBefore(new StagingGateFilter(props), DisableEncodeUrlFilter.class)
        .addFilterBefore(new OriginCheckFilter(props.siteOrigin()), AuthorizationFilter.class)
        .addFilterBefore(new AdminIpFilter(props.admin().allowedIpList()), AuthorizationFilter.class)
        .addFilterBefore(new SessionAuthFilter(members, admins, clock, props), AuthorizationFilter.class)
        .authorizeHttpRequests(a -> a
            .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
            .requestMatchers(HttpMethod.GET, "/api/health", "/api/captcha", "/api/auth/me", "/api/public/**").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/logout", "/api/password-reset/request", "/api/password-reset/confirm").permitAll()
            .requestMatchers(HttpMethod.POST, "/api/admin/login", "/api/admin/logout").permitAll()
            .requestMatchers("/api/mypage/**").hasRole("MEMBER")
            .requestMatchers("/api/admin/admins/**", "/api/admin/settings/**").hasRole("ADMIN")
            .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "OPERATOR")
            .anyRequest().denyAll())
        .exceptionHandling(e -> e
            .authenticationEntryPoint((req, res, ex) -> Json.write(res, 401,
                req.getRequestURI().startsWith("/api/admin") ? "管理者ログインが必要です。" : "ログインが必要です。"))
            .accessDeniedHandler((req, res, ex) -> Json.write(res, 403, "この操作を行う権限がありません。")))
        .headers(h -> h
            .contentSecurityPolicy(c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
            .frameOptions(f -> f.deny())
            .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
            .httpStrictTransportSecurity(t -> t.includeSubDomains(true).maxAgeInSeconds(31_536_000))
            .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=(), payment=()")));
    return http.build();
  }
}
