package com.bhukkad.security;

import com.bhukkad.audit.AuditService;
import com.bhukkad.entity.Customer;
import com.bhukkad.entity.User;
import com.bhukkad.repository.CustomerRepository;
import com.bhukkad.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * Completes the OAuth2 (Google / Apple / Facebook) login by mapping the verified
 * provider identity onto the existing {@link User} model and issuing this
 * application's own JWT pair — the browser never receives a provider token.
 *
 * <p><strong>Account linking:</strong> users are matched by email. An existing
 * account simply logs in (providers return verified emails); otherwise a new
 * {@link Customer} is provisioned with an unguessable random password so the
 * account cannot be accessed through the password path.</p>
 */
@Slf4j
@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final UserRepository userRepository;
    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final AuthTokenService authTokenService;
    private final AuditService auditService;

    /** Inline default keeps plain unit tests deterministic; the container overrides it. */
    @Value("${app.oauth2.redirect-uri:http://localhost:3000/oauth/callback}")
    private String redirectUri = "http://localhost:3000/oauth/callback";

    public OAuth2LoginSuccessHandler(UserRepository userRepository,
                                     CustomerRepository customerRepository,
                                     PasswordEncoder passwordEncoder,
                                     JwtTokenProvider jwtTokenProvider,
                                     AuthTokenService authTokenService,
                                     AuditService auditService) {
        this.userRepository = userRepository;
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.authTokenService = authTokenService;
        this.auditService = auditService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2AuthenticationToken oauthToken = (OAuth2AuthenticationToken) authentication;
        String provider = oauthToken.getAuthorizedClientRegistrationId();
        OAuth2User oAuth2User = oauthToken.getPrincipal();
        Map<String, Object> attributes = oAuth2User.getAttributes();

        String email = firstNonNull(
                str(attributes.get("email")),
                // Apple puts the email inside a nested "name" object only on first auth.
                str(firstMapValue(attributes, "name", "email")));
        String fullName = firstNonNull(
                str(attributes.get("name")),
                str(attributes.get("given_name")),
                "Social User");

        if (email == null || email.isBlank()) {
            auditService.recordEvent("OAUTH_LOGIN_FAILED", "AUTH", provider, null, "missing_email", null);
            redirectWithError(request, response, "Email permission is required to sign in");
            return;
        }
        email = email.toLowerCase().trim();

        User user = userRepository.findByEmail(email).orElse(null);
        boolean newUser = false;
        if (user == null) {
            user = provisionCustomer(email, fullName);
            newUser = true;
        } else if (!Boolean.TRUE.equals(user.getActive())) {
            auditService.recordEvent("OAUTH_LOGIN_BLOCKED", "AUTH", user.getEmail(), null, "deactivated", user.getId());
            redirectWithError(request, response, "Account is deactivated");
            return;
        }

        String accessToken = jwtTokenProvider.generateAccessToken(toUserDetails(user));
        String refreshToken = jwtTokenProvider.generateRefreshToken(toUserDetails(user));
        authTokenService.storeRefreshToken(
                user.getId(), refreshToken, jwtTokenProvider.getRemainingValidityMs(refreshToken));

        auditService.recordEvent(newUser ? "REGISTER_SOCIAL" : "LOGIN_SOCIAL", "AUTH",
                user.getEmail(), null, provider, user.getId());
        log.info("OAuth login success | userId={} | provider={} | newUser={}", user.getId(), provider, newUser);

        String targetUrl = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("token", accessToken)
                .queryParam("refreshToken", refreshToken)
                .build().toUriString();
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private User provisionCustomer(String email, String fullName) {
        Customer customer = new Customer();
        customer.setEmail(email);
        // Unguessable password: the account is reachable only via social login or
        // the password-reset flow.
        customer.setPassword(passwordEncoder.encode(
                Base64.getUrlEncoder().encodeToString(SecureRandom.getSeed(32))));
        customer.setFullName(fullName != null ? fullName : "Social User");
        customer.setRole(User.UserRole.CUSTOMER);
        customer.setActive(true);
        // Provider emails are verified by the provider.
        customer.setEmailVerified(true);
        return customerRepository.save(customer);
    }

    private org.springframework.security.core.userdetails.UserDetails toUserDetails(User user) {
        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPassword())
                .authorities("ROLE_" + user.getRole().name())
                .build();
    }

    private void redirectWithError(HttpServletRequest request, HttpServletResponse response,
                                   String message) throws IOException {
        String targetUrl = UriComponentsBuilder.fromUriString(redirectUri)
                .queryParam("error", message)
                .build().toUriString();
        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }

    private static String str(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static Object firstMapValue(Map<String, Object> attributes, String key, String nestedKey) {
        Object nested = attributes.get(key);
        if (nested instanceof Map<?, ?> map) {
            return ((Map<String, Object>) map).get(nestedKey);
        }
        return null;
    }

    private static String firstNonNull(String... values) {
        for (String value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
