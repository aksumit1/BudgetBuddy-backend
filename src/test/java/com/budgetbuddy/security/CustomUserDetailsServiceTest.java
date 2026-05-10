package com.budgetbuddy.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

class CustomUserDetailsServiceTest {

    private static final String HASH = "hash";

    @Mock private UserRepository userRepository;

    private CustomUserDetailsService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CustomUserDetailsService(userRepository);
    }

    @Test
    void testLoadUserByUsernameUserExistsShouldReturnUserDetails() {
        // Given
        final String email = "test@example.com";
        final String passwordHash = "hashedPassword";
        final UserTable user = new UserTable();
        user.setUserId("user-123");
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        // When
        final UserDetails userDetails = service.loadUserByUsername(email);

        // Then
        assertNotNull(userDetails);
        assertEquals(email, userDetails.getUsername());
        assertEquals(passwordHash, userDetails.getPassword());
        assertTrue(userDetails.isEnabled());
        assertTrue(userDetails.isAccountNonLocked());
        assertTrue(userDetails.isAccountNonExpired());
        assertTrue(userDetails.isCredentialsNonExpired());
        assertTrue(
                userDetails.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_USER".equals(a.getAuthority())));

        verify(userRepository, times(1)).findByEmail(email);
    }

    @Test
    void testLoadUserByUsernameUserNotExistsShouldThrowException() {
        // Given
        final String email = "nonexistent@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());

        // When/Then
        assertThrows(
                UsernameNotFoundException.class,
                () -> {
                    service.loadUserByUsername(email);
                });

        verify(userRepository, times(1)).findByEmail(email);
    }

    @Test
    void testLoadUserByUsernameUserDisabledShouldBeDisabled() {
        // Given
        final String email = "disabled@example.com";
        final UserTable user = new UserTable();
        user.setUserId("user-456");
        user.setEmail(email);
        user.setPasswordHash(HASH);
        user.setEnabled(false);
        user.setRoles(Set.of("USER"));

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        // When
        final UserDetails userDetails = service.loadUserByUsername(email);

        // Then
        assertFalse(userDetails.isEnabled());
        assertFalse(userDetails.isAccountNonLocked());
    }

    @Test
    void testLoadUserByUsernameNoRolesShouldHaveDefaultRole() {
        // Given
        final String email = "noroles@example.com";
        final UserTable user = new UserTable();
        user.setUserId("user-789");
        user.setEmail(email);
        user.setPasswordHash(HASH);
        user.setEnabled(true);
        user.setRoles(null); // No roles

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        // When
        final UserDetails userDetails = service.loadUserByUsername(email);

        // Then
        assertTrue(
                userDetails.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_USER".equals(a.getAuthority())));
    }

    @Test
    void testLoadUserByUsernameEmptyRolesShouldHaveDefaultRole() {
        // Given
        final String email = "emptyroles@example.com";
        final UserTable user = new UserTable();
        user.setUserId("user-101");
        user.setEmail(email);
        user.setPasswordHash(HASH);
        user.setEnabled(true);
        user.setRoles(Set.of()); // Empty roles

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        // When
        final UserDetails userDetails = service.loadUserByUsername(email);

        // Then
        assertTrue(
                userDetails.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_USER".equals(a.getAuthority())));
    }

    @Test
    void testLoadUserByUsernameMultipleRolesShouldHaveAllRoles() {
        // Given
        final String email = "admin@example.com";
        final UserTable user = new UserTable();
        user.setUserId("user-admin");
        user.setEmail(email);
        user.setPasswordHash(HASH);
        user.setEnabled(true);
        user.setRoles(Set.of("USER", "ADMIN"));

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        // When
        final UserDetails userDetails = service.loadUserByUsername(email);

        // Then
        final Set<String> authorities =
                userDetails.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(java.util.stream.Collectors.toSet());
        assertTrue(authorities.contains("ROLE_USER"));
        assertTrue(authorities.contains("ROLE_ADMIN"));
    }

    @Test
    void testLoadUserByUsernameRolesAreUppercased() {
        // Given
        final String email = "mixed@example.com";
        final UserTable user = new UserTable();
        user.setUserId("user-mixed");
        user.setEmail(email);
        user.setPasswordHash(HASH);
        user.setEnabled(true);
        user.setRoles(Set.of("user", "admin")); // Lowercase

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));

        // When
        final UserDetails userDetails = service.loadUserByUsername(email);

        // Then
        final Set<String> authorities =
                userDetails.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .collect(java.util.stream.Collectors.toSet());
        assertTrue(authorities.contains("ROLE_USER"));
        assertTrue(authorities.contains("ROLE_ADMIN"));
    }
}
