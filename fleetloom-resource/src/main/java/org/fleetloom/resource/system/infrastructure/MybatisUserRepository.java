package org.fleetloom.resource.system.infrastructure;

import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.fleetloom.resource.system.domain.Role;
import org.fleetloom.resource.system.domain.User;
import org.fleetloom.resource.system.domain.UserRepository;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisUserRepository implements UserRepository {

    private final UserMapper mapper;

    public MybatisUserRepository(UserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username)))
                .map(MybatisUserRepository::toDomain);
    }

    @Override
    public List<User> findAll() {
        return mapper.selectList(new LambdaQueryWrapper<UserEntity>().orderByAsc(UserEntity::getUsername)).stream()
                .map(MybatisUserRepository::toDomain).toList();
    }

    @Override
    public long count() {
        return mapper.selectCount(null);
    }

    @Override
    public User save(User u) {
        UserEntity e = new UserEntity();
        e.setId(u.id());
        e.setUsername(u.username());
        e.setDisplayName(u.displayName());
        e.setPasswordHash(u.passwordHash());
        e.setRole(u.role().name());
        e.setEnabled(u.enabled());
        e.setCreatedAt(u.createdAt());
        e.setUpdatedAt(u.updatedAt());
        if (u.id() == null) {
            mapper.insert(e);
        } else {
            mapper.updateById(e);
        }
        return toDomain(e);
    }

    @Override
    public void delete(User user) {
        mapper.deleteById(user.id());
    }

    private static User toDomain(UserEntity e) {
        return new User(e.getId(), e.getUsername(), e.getDisplayName(), e.getPasswordHash(), Role.valueOf(e.getRole()),
                e.getEnabled(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
