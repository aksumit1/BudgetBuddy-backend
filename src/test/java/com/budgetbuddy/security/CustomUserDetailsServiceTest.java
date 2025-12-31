package com.budgetbuddy.security;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomUserDetailsServiceTest {

    @Mock
    private UserRepository userRepository;

    private CustomUserDetailsService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new CustomUserDetailsService(userRepository);
    }

    @Test
    void testLoadUserByUsername_UserExists_ShouldReturnUserDetails() {
        // Given
        String email = "test@example.com";
        String passwordHash = "hashedPassword";
        UserTable user = new UserTable();
        user.setUserId("user-123");
        user.setEmail(email);
        user.setPasswordHash(passwordHash);
        user.setEnabled(true);
        user.setRoles(Set.of("USER"));
        
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        
        // When
        UserDetails userDetails = service.loadUserByUsername(email);
        
        // Then
        assertNotNull(userDetails);
        assertEquals(email, userDetails.getUsername());
        assertEquals(passwordHash, userDetails.getPassword());
        assertTrue(userDetails.isEnabled());
        assertTrue(userDetails.isAccountNonLocked());
        assertTrue(userDetails.isAccountNonExpired());
        assertTrue(userDetails.isCredentialsNonExpired());
        assertTrue(userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
        
        verify(userRepository, times(1)).findByEmail(email);
    }

    @Test
    void testLoadUserByUsername_UserNotExists_ShouldThrowException() {
        // Given
        String email = "nonexistent@example.com";
        when(userRepository.findByEmail(email)).thenReturn(Optional.empty());
        
        // When/Then
        assertThrows(UsernameNotFoundException.class, () -> {
            service.loadUserByUsername(email);
        });
        
        verify(userRepository, times(1)).findByEmail(email);
    }

    @Test
    void testLoadUserByUsername_UserDisabled_ShouldBeDisabled() {
        // Given
        String email = "disabled@example.com";
        UserTable user = new UserTable();
        user.setUserId("user-456");
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setEnabled(false);
        user.setRoles(Set.of("USER"));
        
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        
        // When
        UserDetails userDetails = service.loadUserByUsername(email);
        
        // Then
        assertFalse(userDetails.isEnabled());
        assertFalse(userDetails.isAccountNonLocked());
    }

    @Test
    void testLoadUserByUsername_NoRoles_ShouldHaveDefaultRole() {
        // Given
        String email = "noroles@example.com";
        UserTable user = new UserTable();
        user.setUserId("user-789");
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setEnabled(true);
        user.setRoles(null); // No roles
        
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        
        // When
        UserDetails userDetails = service.loadUserByUsername(email);
        
        // Then
        assertTrue(userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }

    @Test
    void testLoadUserByUsername_EmptyRoles_ShouldHaveDefaultRole() {
        // Given
        String email = "emptyroles@example.com";
        UserTable user = new UserTable();
        user.setUserId("user-101");
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setEnabled(true);
        user.setRoles(Set.of()); // Empty roles
        
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        
        // When
        UserDetails userDetails = service.loadUserByUsername(email);
        
        // Then
        assertTrue(userDetails.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_USER")));
    }

    @Test
    void testLoadUserByUsername_MultipleRoles_ShouldHaveAllRoles() {
        // Given
        String email = "admin@example.com";
        UserTable user = new UserTable();
        user.setUserId("user-admin");
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setEnabled(true);
        user.setRoles(Set.of("USER", "ADMIN"));
        
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        
        // When
        UserDetails userDetails = service.loadUserByUsername(email);
        
        // Then
        Set<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(authorities.contains("ROLE_USER"));
        assertTrue(authorities.contains("ROLE_ADMIN"));
    }

    @Test
    void testLoadUserByUsername_RolesAreUppercased() {
        // Given
        String email = "mixed@example.com";
        UserTable user = new UserTable();
        user.setUserId("user-mixed");
        user.setEmail(email);
        user.setPasswordHash("hash");
        user.setEnabled(true);
        user.setRoles(Set.of("user", "admin")); // Lowercase
        
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(user));
        
        // When
        UserDetails userDetails = service.loadUserByUsername(email);
        
        // Then
        Set<String> authorities = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(authorities.contains("ROLE_USER"));
        assertTrue(authorities.contains("ROLE_ADMIN"));
    }
}

