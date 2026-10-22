package org.rcs3000.resource.system.domain;

import java.util.List;
import java.util.Optional;

public interface UserRepository {

    Optional<User> findByUsername(String username);

    List<User> findAll();

    long count();

    User save(User user);

    void delete(User user);
}
