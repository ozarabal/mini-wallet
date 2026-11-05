package com.rafif.wallet.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Thin controller: validate input, delegate to the service. No business logic here. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a user account",
            description = "Usernames are case-insensitive. The password must be 8 to 72 characters.")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR")
    @ApiResponse(responseCode = "409", description = "USERNAME_TAKEN")
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return service.register(request);
    }

    @PostMapping("/login")
    @Operation(summary = "Log in and get an access token",
            description = "Send the token as 'Authorization: Bearer <token>' on every other request.")
    @ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS (same answer for an unknown user and a wrong password)")
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return service.login(request);
    }
}
