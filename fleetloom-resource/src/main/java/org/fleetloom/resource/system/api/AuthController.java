package org.fleetloom.resource.system.api;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.fleetloom.common.api.ApiResponse;
import org.fleetloom.common.error.BizException;
import org.fleetloom.common.error.CommonErrorCode;
import org.fleetloom.resource.system.application.UserService;
import org.fleetloom.resource.system.application.UserService.LoginResult;
import org.fleetloom.resource.system.application.UserService.UserView;
import org.fleetloom.resource.system.domain.Role;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login and user management. Only login is reachable without a token; the gateway restricts
 * {@code /users} to administrators and passes the authenticated user as {@code X-User-Name}.
 */
@RestController
@RequestMapping("/api/resource/v1")
public class AuthController {

    static final String USER_HEADER = "X-User-Name";

    private final UserService users;

    public AuthController(UserService users) {
        this.users = users;
    }

    @PostMapping("/auth/login")
    public ApiResponse<LoginResult> login(@Valid @RequestBody Login request) {
        return ApiResponse.ok(users.login(request.username(), request.password()));
    }

    @GetMapping("/auth/me")
    public ApiResponse<UserView> me(@RequestHeader(value = USER_HEADER, required = false) String username) {
        return ApiResponse.ok(users.get(requireUser(username)));
    }

    @PutMapping("/auth/me/password")
    public ApiResponse<Void> changeOwnPassword(@RequestHeader(value = USER_HEADER, required = false) String username,
            @Valid @RequestBody ChangePassword request) {
        users.changeOwnPassword(requireUser(username), request.currentPassword(), request.newPassword());
        return ApiResponse.ok();
    }

    @GetMapping("/users")
    public ApiResponse<List<UserView>> list() {
        return ApiResponse.ok(users.list());
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserView> create(@Valid @RequestBody CreateUser request) {
        return ApiResponse.ok(users.create(request.username(), request.displayName(), request.password(), request.role()));
    }

    @PutMapping("/users/{username}")
    public ApiResponse<UserView> update(@PathVariable String username, @Valid @RequestBody UpdateUser request) {
        return ApiResponse.ok(users.update(username, request.displayName(), request.role(), request.enabled()));
    }

    @PutMapping("/users/{username}/password")
    public ApiResponse<Void> resetPassword(@PathVariable String username, @Valid @RequestBody ResetPassword request) {
        users.changePassword(username, request.password());
        return ApiResponse.ok();
    }

    @DeleteMapping("/users/{username}")
    public ApiResponse<Void> delete(@PathVariable String username) {
        users.delete(username);
        return ApiResponse.ok();
    }

    private static String requireUser(String username) {
        if (username == null || username.isBlank()) {
            throw new BizException(CommonErrorCode.BAD_REQUEST, "no authenticated user");
        }
        return username;
    }

    public record Login(@NotBlank String username, @NotBlank String password) {
    }

    public record ChangePassword(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    public record CreateUser(@NotBlank @Pattern(regexp = "[A-Za-z0-9_.-]{2,64}") String username,
            @NotBlank String displayName, @NotBlank String password, @NotNull Role role) {
    }

    public record UpdateUser(@NotBlank String displayName, @NotNull Role role, @NotNull Boolean enabled) {
    }

    public record ResetPassword(@NotBlank String password) {
    }
}
