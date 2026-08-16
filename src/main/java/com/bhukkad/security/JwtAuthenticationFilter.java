package com.bhukkad.security;

import com.bhukkad.entity.User;
import com.bhukkad.logging.LoggingConstants;
import com.bhukkad.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService userDetailsService;
    private final UserRepository userRepository;
    private final AuthTokenService authTokenService;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt) && jwtTokenProvider.validateToken(jwt)) {
                if (authTokenService.isAccessTokenBlacklisted(jwt)) {
                    log.debug("Rejected blacklisted access token");
                    filterChain.doFilter(request, response);
                    return;
                }
                if (jwtTokenProvider.isRefreshToken(jwt)) {
                    log.debug("Rejected refresh token used as access token");
                    filterChain.doFilter(request, response);
                    return;
                }

                String username = jwtTokenProvider.extractUsername(jwt);

                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                if (jwtTokenProvider.isTokenValid(jwt, userDetails)) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    userDetails.getAuthorities()
                            );

                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request)
                    );

                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    // *** SET MDC CONTEXT AFTER SUCCESSFUL AUTHENTICATION ***
                    setUserMDCContext(username, userDetails);

                    log.debug("Authentication successful for user: {}", username);
                }
            }
        } catch (Exception ex) {
            log.error("Could not set user authentication: {}", ex.getMessage());
            MDC.put(LoggingConstants.USER_ID, "INVALID_TOKEN");
        }

        filterChain.doFilter(request, response);
    }

    private void setUserMDCContext(String email, UserDetails userDetails) {
        try {
            MDC.put(LoggingConstants.USER_EMAIL, email);
            MDC.put(LoggingConstants.TIMESTAMP, Instant.now().toString());

            // Set role
            if (!userDetails.getAuthorities().isEmpty()) {
                String role = userDetails.getAuthorities().iterator().next().getAuthority();
                MDC.put(LoggingConstants.USER_ROLE, role);
            }

            // Fetch user ID from database
            Optional<User> userOptional = userRepository.findByEmail(email);
            if (userOptional.isPresent()) {
                MDC.put(LoggingConstants.USER_ID, String.valueOf(userOptional.get().getId()));
            }

            log.debug("MDC Context set | UserId: {} | Email: {} | Role: {}",
                    MDC.get(LoggingConstants.USER_ID),
                    MDC.get(LoggingConstants.USER_EMAIL),
                    MDC.get(LoggingConstants.USER_ROLE));

        } catch (Exception e) {
            log.warn("Failed to set user MDC context: {}", e.getMessage());
        }
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}