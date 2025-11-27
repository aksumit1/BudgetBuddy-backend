package com.budgetbuddy.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

import java.io.IOException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit Tests for JwtAuthenticationFilter
 * Tests JWT token processing and authentication context setup
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private UserDetailsService userDetailsService;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final String INVALID_TOKEN = "invalid.jwt.token";
    private static final String TEST_USERNAME = "test@example.com";
    private static final String BEARER_TOKEN = "Bearer " + VALID_TOKEN;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testDoFilterInternal_WithValidToken_ShouldSetAuthentication() throws Exception {
        // Given
        UserDetails userDetails = User.builder()
                .username(TEST_USERNAME)
                .password("password")
                .authorities(Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")))
                .build();

        when(request.getHeader("Authorization")).thenReturn(BEARER_TOKEN);
        when(tokenProvider.validateToken(VALID_TOKEN)).thenReturn(true);
        when(tokenProvider.getUsernameFromToken(VALID_TOKEN)).thenReturn(TEST_USERNAME);
        when(userDetailsService.loadUserByUsername(TEST_USERNAME)).thenReturn(userDetails);

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(TEST_USERNAME, SecurityContextHolder.getContext().getAuthentication().getName());
        verify(tokenProvider).validateToken(VALID_TOKEN);
        verify(userDetailsService).loadUserByUsername(TEST_USERNAME);
    }

    @Test
    void testDoFilterInternal_WithInvalidToken_ShouldNotSetAuthentication() throws Exception {
        // Given
        when(request.getHeader("Authorization")).thenReturn(BEARER_TOKEN);
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
    void testDoFilterInternal_WithNoToken_ShouldNotSetAuthentication() throws Exception {
        // Given
        when(request.getHeader("Authorization")).thenReturn(null);

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(tokenProvider, never()).validateToken(anyString());
        verify(userDetailsService, never()).loadUserByUsername(anyString());
    }

    @Test
    void testDoFilterInternal_WithUserNotFound_ShouldNotSetAuthentication() throws Exception {
        // Given
        when(request.getHeader("Authorization")).thenReturn(BEARER_TOKEN);
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
    void testDoFilterInternal_WithJwtException_ShouldNotSetAuthentication() throws Exception {
        // Given
        when(request.getHeader("Authorization")).thenReturn(BEARER_TOKEN);
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
    void testDoFilterInternal_WithEmptyUsername_ShouldNotSetAuthentication() throws Exception {
        // Given
        when(request.getHeader("Authorization")).thenReturn(BEARER_TOKEN);
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
    void testDoFilterInternal_WithNullUsername_ShouldNotSetAuthentication() throws Exception {
        // Given
        when(request.getHeader("Authorization")).thenReturn(BEARER_TOKEN);
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
    void testDoFilterInternal_WithUnexpectedException_ShouldContinueFilterChain() throws Exception {
        // Given
        when(request.getHeader("Authorization")).thenReturn(BEARER_TOKEN);
        when(tokenProvider.validateToken(VALID_TOKEN)).thenThrow(new RuntimeException("Unexpected error"));

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void testDoFilterInternal_WithNullUserDetails_ShouldNotSetAuthentication() throws Exception {
        // Given
        when(request.getHeader("Authorization")).thenReturn(BEARER_TOKEN);
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
    void testDoFilterInternal_WithTokenWithoutBearerPrefix_ShouldNotProcess() throws Exception {
        // Given
        when(request.getHeader("Authorization")).thenReturn(VALID_TOKEN); // No "Bearer " prefix

        // When
        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        // Then
        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(tokenProvider, never()).validateToken(anyString());
    }

    @Test
    void testConstructor_WithNullTokenProvider_ShouldThrowException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new JwtAuthenticationFilter(null, userDetailsService);
        });
    }

    @Test
    void testConstructor_WithNullUserDetailsService_ShouldThrowException() {
        // When/Then
        assertThrows(IllegalArgumentException.class, () -> {
            new JwtAuthenticationFilter(tokenProvider, null);
        });
    }
}

