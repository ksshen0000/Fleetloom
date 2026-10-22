package org.rcs3000.resource.system.domain;

import org.rcs3000.common.error.ErrorCode;

public enum SystemErrorCode implements ErrorCode {
    /** Wrong username or password, or the user is disabled; deliberately not more specific. */
    LOGIN_FAILED(401),
    USER_NOT_FOUND(404),
    USERNAME_DUPLICATE(409),
    /** Removing, disabling or demoting the last enabled administrator would lock everyone out. */
    LAST_ADMIN(409),
    WEAK_PASSWORD(400),
    /** The current password given to change one's own password is wrong. */
    WRONG_PASSWORD(400),
    ALARM_NOT_FOUND(404);

    private final int httpStatus;

    SystemErrorCode(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int httpStatus() {
        return httpStatus;
    }
}
