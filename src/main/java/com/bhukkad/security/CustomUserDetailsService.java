package com.bhukkad.security;

import com.bhukkad.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final AccountLookupService accountLookupService;

    @Override
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        User user = accountLookupService.byIdentifier(identifier)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + identifier));

        String identifierValue = AccountFields.email(user) != null
                ? AccountFields.email(user)
                : AccountFields.phoneNumber(user);
        String password = AccountFields.password(user) != null ? AccountFields.password(user) : "";

        return new org.springframework.security.core.userdetails.User(
                identifierValue != null ? identifierValue : identifier,
                password,
                user.getActive(),
                true,
                true,
                true,
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))
        );
    }
}
