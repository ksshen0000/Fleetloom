package org.rcs3000.resource.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.rcs3000.common.error.BizException;
import org.rcs3000.resource.system.application.UserService;
import org.rcs3000.resource.system.domain.PasswordHasher;
import org.rcs3000.resource.system.domain.Role;
import org.rcs3000.resource.system.domain.SystemErrorCode;
import org.rcs3000.resource.system.domain.TokenIssuer;
import org.rcs3000.resource.system.domain.User;
import org.rcs3000.resource.system.domain.UserRepository;

class UserServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");

    private final InMemoryUsers repo = new InMemoryUsers();
    private final UserService users = new UserService(repo, new PlainHasher(),
            (user, now) -> new TokenIssuer.IssuedToken("token-for-" + user.username(), now.plusSeconds(60)),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void initialAdminIsCreatedOnlyOnce() {
        assertThat(users.createInitialAdmin("secret-123")).isTrue();
        assertThat(users.createInitialAdmin("other-123")).isFalse();
        assertThat(users.list()).singleElement().satisfies(u -> assertThat(u.role()).isEqualTo(Role.ADMIN));
    }

    @Test
    void loginReturnsATokenForTheRightPasswordOnly() {
        users.create("alice", "Alice", "password-1", Role.OPERATOR);

        UserService.LoginResult result = users.login("alice", "password-1");
        assertThat(result.token()).isEqualTo("token-for-alice");
        assertThat(result.user().role()).isEqualTo(Role.OPERATOR);

        assertError(() -> users.login("alice", "wrong"), SystemErrorCode.LOGIN_FAILED);
        assertError(() -> users.login("nobody", "password-1"), SystemErrorCode.LOGIN_FAILED);
    }

    @Test
    void disabledUserCannotLogIn() {
        users.create("admin", "Admin", "password-1", Role.ADMIN);
        users.create("bob", "Bob", "password-1", Role.VIEWER);
        users.update("bob", "Bob", Role.VIEWER, false);

        assertError(() -> users.login("bob", "password-1"), SystemErrorCode.LOGIN_FAILED);
    }

    @Test
    void passwordsAreHashedAndMustBeLongEnough() {
        users.create("alice", "Alice", "password-1", Role.OPERATOR);

        assertThat(repo.findByUsername("alice").orElseThrow().passwordHash()).isNotEqualTo("password-1");
        assertError(() -> users.create("bob", "Bob", "short", Role.VIEWER), SystemErrorCode.WEAK_PASSWORD);
    }

    @Test
    void changingOwnPasswordChecksTheCurrentOne() {
        users.create("alice", "Alice", "password-1", Role.OPERATOR);

        assertError(() -> users.changeOwnPassword("alice", "wrong", "password-2"), SystemErrorCode.WRONG_PASSWORD);
        users.changeOwnPassword("alice", "password-1", "password-2");
        assertThat(users.login("alice", "password-2").token()).isNotBlank();
    }

    @Test
    void lastAdministratorCannotBeRemovedDisabledOrDemoted() {
        users.create("admin", "Admin", "password-1", Role.ADMIN);

        assertError(() -> users.delete("admin"), SystemErrorCode.LAST_ADMIN);
        assertError(() -> users.update("admin", "Admin", Role.OPERATOR, true), SystemErrorCode.LAST_ADMIN);
        assertError(() -> users.update("admin", "Admin", Role.ADMIN, false), SystemErrorCode.LAST_ADMIN);

        users.create("admin2", "Second", "password-1", Role.ADMIN);
        users.delete("admin");
        assertThat(users.list()).extracting(UserService.UserView::username).containsExactly("admin2");
    }

    @Test
    void usernamesAreUnique() {
        users.create("alice", "Alice", "password-1", Role.OPERATOR);

        assertError(() -> users.create("alice", "Again", "password-1", Role.VIEWER), SystemErrorCode.USERNAME_DUPLICATE);
    }

    private static void assertError(ThrowingCallable call, SystemErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private static final class PlainHasher implements PasswordHasher {

        @Override
        public String hash(String raw) {
            return "hashed:" + raw;
        }

        @Override
        public boolean matches(String raw, String hash) {
            return hash.equals("hashed:" + raw);
        }
    }

    private static final class InMemoryUsers implements UserRepository {

        private final List<User> rows = new ArrayList<>();
        private long ids;

        @Override
        public Optional<User> findByUsername(String username) {
            return rows.stream().filter(u -> u.username().equals(username)).findFirst();
        }

        @Override
        public List<User> findAll() {
            return List.copyOf(rows);
        }

        @Override
        public long count() {
            return rows.size();
        }

        @Override
        public User save(User user) {
            rows.removeIf(u -> u.username().equals(user.username()));
            User stored = user.id() == null ? new User(++ids, user.username(), user.displayName(), user.passwordHash(),
                    user.role(), user.enabled(), user.createdAt(), user.updatedAt()) : user;
            rows.add(stored);
            return stored;
        }

        @Override
        public void delete(User user) {
            rows.removeIf(u -> u.username().equals(user.username()));
        }
    }
}
