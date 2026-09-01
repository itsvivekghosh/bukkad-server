package com.bhukkad.identity.api;

import com.bhukkad.identity.domain.Address;
import com.bhukkad.identity.security.JwtService;
import com.bhukkad.identity.service.AddressService;
import com.bhukkad.identity.service.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Identity service public API: customer registration, login, addresses.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class IdentityController {

    private final IdentityService identityService;
    private final AddressService addressService;
    private final JwtService jwtService;

    public record RegisterRequest(
            @NotBlank @Email String email,
            String phoneNumber,
            @NotBlank @Size(max = 100) String fullName,
            @NotBlank @Size(min = 8, max = 128) String password) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record AuthResponse(String token, Long customerId, String fullName) {
    }

    public record TokenRequest(@NotBlank String token) {
    }

    public record VerifyResponse(boolean valid, Long customerId, String email, String scope, String expiresAt) {
    }

    public record AddressRequest(
            String label,
            @NotBlank String line1,
            @NotBlank String city,
            String state,
            String zipCode,
            boolean isDefault) {
    }

    @PostMapping("/auth/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        var customer = identityService.register(
                request.email(), request.phoneNumber(), request.fullName(), request.password());
        var login = identityService.login(request.email(), request.password());
        return new AuthResponse(login.token(), customer.getId(), login.fullName());
    }

    @PostMapping("/auth/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        var login = identityService.login(request.email(), request.password());
        return new AuthResponse(login.token(), login.customerId(), login.fullName());
    }

    /**
     * Rotates a still-valid access token into a fresh one (see
     * {@link com.bhukkad.identity.service.IdentityService#refresh(String)}).
     */
    @PostMapping("/auth/refresh")
    public AuthResponse refresh(@Valid @RequestBody TokenRequest request) {
        var login = identityService.refresh(request.token());
        return new AuthResponse(login.token(), login.customerId(), login.fullName());
    }

    /**
     * Token introspection (RFC 7662 style) for server-to-server verification.
     * Returns 200 with {@code valid: false} for malformed/expired tokens — never
     * 4xx — so downstream services can cheaply confirm a token without carrying
     * the shared secret (though most validate locally via platform-lib).
     */
    @PostMapping("/internal/verify")
    public VerifyResponse verify(@Valid @RequestBody TokenRequest request) {
        var result = jwtService.introspect(request.token());
        return new VerifyResponse(
                result.valid(),
                result.customerId(),
                result.email(),
                result.scope(),
                result.expiresAt() == null ? null : result.expiresAt().toString());
    }

    @PostMapping("/customers/{customerId}/addresses")
    public Address addAddress(@PathVariable Long customerId,
                              @Valid @RequestBody AddressRequest request) {
        return addressService.addAddress(customerId,
                new AddressService.AddressInput(request.label(), request.line1(), request.city(),
                        request.state(), request.zipCode(), request.isDefault()));
    }

    @GetMapping("/customers/{customerId}/addresses")
    public List<Address> listAddresses(@PathVariable Long customerId) {
        return addressService.listAddresses(customerId);
    }
}