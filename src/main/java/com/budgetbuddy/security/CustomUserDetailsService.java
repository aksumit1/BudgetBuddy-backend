package com.budgetbuddy.security;

import com.budgetbuddy.model.dynamodb.UserTable;
import com.budgetbuddy.repository.dynamodb.UserRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Custom UserDetailsService implementation
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(final UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserDetails loadUserByUsername(final String email) throws UsernameNotFoundException {
        UserTable user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "User not found with email: " + email));

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPasswordHash()) // Use passwordHash from DynamoDB
                .authorities(getAuthorities(user))
                .accountExpired(false)
                .accountLocked(!Boolean.TRUE.equals(user.getEnabled()))
                .credentialsExpired(false)
                .disabled(!Boolean.TRUE.equals(user.getEnabled()))
                .build();
    }

    private Collection<? extends GrantedAuthority> getAuthorities(UserTable user) {
        Set<String> roles = user.getRoles();
        if (roles == null || roles.isEmpty()) {
            return Set.of(new SimpleGrantedAuthority("ROLE_USER"));
        }
        return roles.stream()
                .map((role) -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                .collect(Collectors.toList());
    }
}

