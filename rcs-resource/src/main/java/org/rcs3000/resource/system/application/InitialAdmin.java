package org.rcs3000.resource.system.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates user "admin" on an empty user table, so a fresh installation can be logged into. */
@Component
public class InitialAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialAdmin.class);

    private final UserService users;
    private final String password;

    public InitialAdmin(UserService users, @Value("${rcs.security.initial-admin-password}") String password) {
        this.users = users;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.createInitialAdmin(password)) {
            log.warn("created initial user 'admin' with the configured initial password; change it after the first login");
        }
    }
}
