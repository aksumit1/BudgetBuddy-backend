package com.budgetbuddy.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Unit Tests for JwtAuthenticationFilter Tests JWT token processing and authentication context
 * setup
 */
// Test methods declare `throws Exception` for setup convenience —
// JUnit idiom; the rule is a noise generator on test classes.
@SuppressFBWarnings(
        value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
        justification = "JUnit idiom — test methods accept any setup exception")
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    private static final String AUTHORIZATION = "Authorization";

    @Mock private JwtTokenProvider tokenProvider;

    @Mock private UserDetailsService userDetailsService;

    @Mock private HttpServletRequest request;

    @Mock private HttpServletResponse response;

    @Mock private FilterChain filterChain;

    @InjectMocks private JwtAuthenticationFilter jwtAuthenticationFilter;

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final String TEST_USERNAME = "test@example.com";
    private static final String BEARER_TOKEN = "Bearer " + VALID_TOKEN;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testDoFilterInternalWithValidTokenShouldSetAuthentication() throws Exception {
        // Given
        final UserDetails userDetails =
                User.builder()
                        .username(TEST_USERNAME)
                        .password("password")
                        .authorities(
                                Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")))
                        .build();

        when(request.getHeader(AUTHORIZATION)).thenReturn(BEARER_TOKEN);
        when(tokenProvider.validateToken(VALID_TOKEN)).thenReturn(true);
        when(tokenProvider.getUsernameFromToken(VALID_TOKEN)).thenReturn(TEST_USERNAME);
        when(userDetailsService.loadUserByUsername(TEST_USERNAME)).thenReturn(userDetails);

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(
                TEST_USERNAME, SecurityContextHolder.getContext().getAuthentication().getName());
        verify(tokenProvider).validateToken(VALID_TOKEN);
        verify(userDetailsService).loadUserByUsername(TEST_USERNAME);
    }

    @Test
    void testDoFilterInternalWithInvalidTokenShouldNotSetAuthentication() throws Exception {
        // Given
        when(request.getHeader(AUTHORIZATION)).thenReturn(BEARER_TOKEN);
        when(tokenProvider.validateToken(VALID_TOKEN)).thenReturn(false);

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(tokenProvider).validateToken(VALID_TOKEN);
        verify(userDetailsService, never()).loadUserByUsername(anyString());
    }

    @Test
    void testDoFilterInternalWithNoTokenShouldNotSetAuthentication() throws Exception {
        // Given
        when(request.getHeader(AUTHORIZATION)).thenReturn(null);

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(tokenProvider, never()).validateToken(anyString());
        verify(userDetailsService, never()).loadUserByUsername(anyString());
    }

    @Test
    void testDoFilterInternalWithUserNotFoundShouldNotSetAuthentication() throws Exception {
        // Given
        when(request.getHeader(AUTHORIZATION)).thenReturn(BEARER_TOKEN);
        when(tokenProvider.validateToken(VALID_TOKEN)).thenReturn(true);
        when(tokenProvider.getUsernameFromToken(VALID_TOKEN)).thenReturn(TEST_USERNAME);
        when(userDetailsService.loadUserByUsername(TEST_USERNAME))
                .thenThrow(new UsernameNotFoundException("User not found"));

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(tokenProvider).validateToken(VALID_TOKEN);
        verify(userDetailsService).loadUserByUsername(TEST_USERNAME);
    }

    @Test
    void testDoFilterInternalWithJwtExceptionShouldNotSetAuthentication() throws Exception {
        // Given
        when(request.getHeader(AUTHORIZATION)).thenReturn(BEARER_TOKEN);
        when(tokenProvider.validateToken(VALID_TOKEN))
                .thenThrow(new io.jsonwebtoken.JwtException("Invalid token"));

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(tokenProvider).validateToken(VALID_TOKEN);
        verify(userDetailsService, never()).loadUserByUsername(anyString());
    }

    @Test
    void testDoFilterInternalWithEmptyUsernameShouldNotSetAuthentication() throws Exception {
        // Given
        when(request.getHeader(AUTHORIZATION)).thenReturn(BEARER_TOKEN);
        when(tokenProvider.validateToken(VALID_TOKEN)).thenReturn(true);
        when(tokenProvider.getUsernameFromToken(VALID_TOKEN)).thenReturn("");

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(tokenProvider).validateToken(VALID_TOKEN);
        verify(userDetailsService, never()).loadUserByUsername(anyString());
    }

    @Test
    void testDoFilterInternalWithNullUsernameShouldNotSetAuthentication() throws Exception {
        // Given
        when(request.getHeader(AUTHORIZATION)).thenReturn(BEARER_TOKEN);
        when(tokenProvider.validateToken(VALID_TOKEN)).thenReturn(true);
        when(tokenProvider.getUsernameFromToken(VALID_TOKEN)).thenReturn(null);

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(tokenProvider).validateToken(VALID_TOKEN);
        verify(userDetailsService, never()).loadUserByUsername(anyString());
    }

    @Test
    void testDoFilterInternalWithUnexpectedExceptionShouldContinueFilterChain() throws Exception {
        // Given
        when(request.getHeader(AUTHORIZATION)).thenReturn(BEARER_TOKEN);
        when(tokenProvider.validateToken(VALID_TOKEN))
                .thenThrow(new RuntimeException("Unexpected error"));

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void testDoFilterInternalWithNullUserDetailsShouldNotSetAuthentication() throws Exception {
        // Given
        when(request.getHeader(AUTHORIZATION)).thenReturn(BEARER_TOKEN);
        when(tokenProvider.validateToken(VALID_TOKEN)).thenReturn(true);
        when(tokenProvider.getUsernameFromToken(VALID_TOKEN)).thenReturn(TEST_USERNAME);
        when(userDetailsService.loadUserByUsername(TEST_USERNAME)).thenReturn(null);

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(userDetailsService).loadUserByUsername(TEST_USERNAME);
    }

    @Test
    void testDoFilterInternalWithTokenWithoutBearerPrefixShouldNotProcess() throws Exception {
        // Given
        when(request.getHeader(AUTHORIZATION)).thenReturn(VALID_TOKEN); // No "Bearer " prefix

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(tokenProvider, never()).validateToken(anyString());
    }

    @Test
    void testConstructorWithNullTokenProviderShouldThrowException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    new JwtAuthenticationFilter(null, userDetailsService);
                });
    }

    @Test
    void testConstructorWithNullUserDetailsServiceShouldThrowException() {
        // When/Then
        assertThrows(
                IllegalArgumentException.class,
                () -> {
                    new JwtAuthenticationFilter(tokenProvider, null);
                });
    }
}
