// src/main/java/com/investingapp/backend/security/services/UserDetailsImpl.java
package com.investingapp.backend.security.services;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.investingapp.backend.model.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class UserDetailsImpl implements UserDetails {
    private static final long serialVersionUID = 1L;

    private Long id;
    private String publicId; // Opaque analytics identity
    private String email; // Username will be email

    @JsonIgnore // Don't send password back in API responses if this object is serialized
    private String password;

    // For now, we'll assign a default "ROLE_USER". You can expand this later.
    private Collection<? extends GrantedAuthority> authorities;

    @Override
    public String getPassword() {
        // If user is passkey-only, this can return null or a placeholder.
        // If you still support password login, this should return the hashed password.
        // For now, returning null as we focus on passkeys.
        // The DaoAuthenticationProvider will fail if it tries to authenticate this user
        // with a password.
        return null;
    }

    public UserDetailsImpl(Long id, String email,
            Collection<? extends GrantedAuthority> authorities) {
        this(id, null, email, authorities);
    }

    public UserDetailsImpl(Long id, String publicId, String email,
            Collection<? extends GrantedAuthority> authorities) {
        this.id = id;
        this.publicId = publicId;
        this.email = email;
        this.authorities = authorities;
    }

    /** Opaque analytics identity; carried here so JwtUtils can stamp it into the token. */
    public String getPublicId() {
        return publicId;
    }

    public static UserDetailsImpl build(User user) {
        // For now, every user gets ROLE_USER. You can customize this based on user
        // roles in your DB.
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        // If you had roles stored in User entity:
        // List<GrantedAuthority> authorities = user.getRoles().stream()
        // .map(role -> new SimpleGrantedAuthority(role.getName().name()))
        // .collect(Collectors.toList());

        return new UserDetailsImpl(
                user.getId(),
                user.getPublicId(),
                user.getEmail(),
                authorities);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    public Long getId() {
        return id;
    }

    @Override
    public String getUsername() {
        return email; // Using email as the username
    }

    @Override
    public boolean isAccountNonExpired() {
        return true; // You can add logic for account expiration
    }

    @Override
    public boolean isAccountNonLocked() {
        return true; // You can add logic for account locking
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true; // You can add logic for credentials expiration
    }

    @Override
    public boolean isEnabled() {
        return true; // You can add logic for disabling accounts
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        UserDetailsImpl user = (UserDetailsImpl) o;
        return Objects.equals(id, user.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}