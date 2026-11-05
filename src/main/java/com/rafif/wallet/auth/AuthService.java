package com.rafif.wallet.auth;

import com.rafif.wallet.common.ApiException;
import com.rafif.wallet.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Registration and login.
 * <p>
 * Not {@code @Transactional} on purpose: hashing a password is slow by design (about 100 ms), and we do not want to
 * hold a database connection while the CPU is busy hashing. Each repository call is its own short transaction.
 */
@Service
public class AuthService {

    /** BCrypt only uses the first 72 bytes of a password. */
    private static final int BCRYPT_MAX_BYTES = 72;
    static final String USERNAME_CONSTRAINT = "uq_users_username";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    /**
     * Compared against when the username does not exist, so "unknown user" takes about as long as "wrong password".
     * Otherwise response time would reveal which usernames are registered.
     */
    private final String dummyHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    public UserResponse register(RegisterRequest request) {
        String username = normalize(request.username());
        if (tooLongForBcrypt(request.password())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "password must be at most 72 bytes");
        }
        // Cheap early exit for the common case. It is NOT what guarantees uniqueness (see the catch below).
        if (users.existsByUsername(username)) {
            throw usernameTaken();
        }
        String hash = passwordEncoder.encode(request.password());
        try {
            return UserResponse.from(users.saveAndFlush(User.create(username, hash)));
        } catch (DataIntegrityViolationException e) {
            // Two people registered the same name at the same moment and the other one committed first:
            // the database unique constraint is the referee, same idea as idempotency keys.
            if (isUsernameConflict(e)) {
                throw usernameTaken();
            }
            throw e;
        }
    }

    public TokenResponse login(LoginRequest request) {
        User user = users.findByUsername(normalize(request.username())).orElse(null);
        boolean passwordFits = !tooLongForBcrypt(request.password());
        // Always do one BCrypt comparison, whether or not the user exists (see dummyHash).
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean matches = passwordFits && passwordEncoder.matches(request.password(), hash);
        if (user == null || !matches) {
            // Same answer for "no such user" and "wrong password".
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid username or password");
        }
        return jwtService.issue(user.getId());
    }

    private static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean tooLongForBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }

    private static ApiException usernameTaken() {
        return new ApiException(ErrorCode.USERNAME_TAKEN, "Username is already taken");
    }

    private static boolean isUsernameConflict(DataIntegrityViolationException e) {
        String message = NestedExceptionUtils.getMostSpecificCause(e).getMessage();
        return message != null && message.contains(USERNAME_CONSTRAINT);
    }
}
