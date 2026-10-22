package org.rcs3000.resource.system.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.rcs3000.common.error.BizException;
import org.rcs3000.resource.system.domain.PasswordHasher;
import org.rcs3000.resource.system.domain.Role;
import org.rcs3000.resource.system.domain.SystemErrorCode;
import org.rcs3000.resource.system.domain.TokenIssuer;
import org.rcs3000.resource.system.domain.User;
import org.rcs3000.resource.system.domain.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Users and login. Authorisation itself happens in the gateway, based on the role in the token. */
@Service
@Transactional
public class UserService {

    static final int MIN_PASSWORD_LENGTH = 8;

    private final UserRepository users;
    private final PasswordHasher hasher;
    private final TokenIssuer tokens;
    private final Clock clock;

    public UserService(UserRepository users, PasswordHasher hasher, TokenIssuer tokens, Clock clock) {
        this.users = users;
        this.hasher = hasher;
        this.tokens = tokens;
        this.clock = clock;
    }

    public LoginResult login(String username, String password) {
        User user = users.findByUsername(username)
                .filter(User::enabled)
                .filter(u -> hasher.matches(password, u.passwordHash()))
                .orElseThrow(() -> new BizException(SystemErrorCode.LOGIN_FAILED, "wrong username or password"));
        TokenIssuer.IssuedToken token = tokens.issue(user, clock.instant());
        return new LoginResult(token.token(), token.expiresAt(), UserView.of(user));
    }

    @Transactional(readOnly = true)
    public UserView get(String username) {
        return UserView.of(require(username));
    }

    @Transactional(readOnly = true)
    public List<UserView> list() {
        return users.findAll().stream().map(UserView::of).toList();
    }

    public UserView create(String username, String displayName, String password, Role role) {
        if (users.findByUsername(username).isPresent()) {
            throw new BizException(SystemErrorCode.USERNAME_DUPLICATE, "user %s already exists".formatted(username));
        }
        requireStrong(password);
        Instant now = clock.instant();
        return UserView.of(users.save(new User(null, username, displayName, hasher.hash(password), role, true, now, now)));
    }

    public UserView update(String username, String displayName, Role role, boolean enabled) {
        User user = require(username);
        if (user.role() == Role.ADMIN && user.enabled() && (role != Role.ADMIN || !enabled)) {
            requireAnotherAdmin(username);
        }
        return UserView.of(users.save(user.with(displayName, role, enabled, clock.instant())));
    }

    /** Changes one's own password after checking the current one. */
    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
        User user = require(username);
        if (!hasher.matches(currentPassword, user.passwordHash())) {
            throw new BizException(SystemErrorCode.WRONG_PASSWORD, "current password is wrong");
        }
        changePassword(username, newPassword);
    }

    public void changePassword(String username, String newPassword) {
        requireStrong(newPassword);
        User user = require(username);
        users.save(user.withPasswordHash(hasher.hash(newPassword), clock.instant()));
    }

    public void delete(String username) {
        User user = require(username);
        if (user.role() == Role.ADMIN && user.enabled()) {
            requireAnotherAdmin(username);
        }
        users.delete(user);
    }

    /** Creates the first administrator when there are no users at all. */
    public boolean createInitialAdmin(String password) {
        if (users.count() > 0) {
            return false;
        }
        create("admin", "Administrator", password, Role.ADMIN);
        return true;
    }

    private void requireAnotherAdmin(String username) {
        boolean another = users.findAll().stream()
                .anyMatch(u -> u.role() == Role.ADMIN && u.enabled() && !u.username().equals(username));
        if (!another) {
            throw new BizException(SystemErrorCode.LAST_ADMIN, "%s is the last enabled administrator".formatted(username));
        }
    }

    private static void requireStrong(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BizException(SystemErrorCode.WEAK_PASSWORD,
                    "password must have at least %d characters".formatted(MIN_PASSWORD_LENGTH));
        }
    }

    private User require(String username) {
        return users.findByUsername(username)
                .orElseThrow(() -> new BizException(SystemErrorCode.USER_NOT_FOUND, "user %s not found".formatted(username)));
    }

    public record UserView(String username, String displayName, Role role, boolean enabled, Instant createdAt) {

        static UserView of(User u) {
            return new UserView(u.username(), u.displayName(), u.role(), u.enabled(), u.createdAt());
        }
    }

    public record LoginResult(String token, Instant expiresAt, UserView user) {
    }
}
