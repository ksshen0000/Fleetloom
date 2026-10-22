package org.rcs3000.resource.system.infrastructure;

import java.time.Instant;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.rcs3000.resource.system.domain.AuditEntry;
import org.rcs3000.resource.system.domain.AuditRepository;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisAuditRepository implements AuditRepository {

    private final AuditMapper mapper;

    public MybatisAuditRepository(AuditMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(AuditEntry a) {
        AuditEntity e = new AuditEntity();
        e.setAt(a.at());
        e.setUsername(a.username());
        e.setRole(a.role());
        e.setMethod(a.method());
        e.setPath(a.path());
        e.setQuery(a.query());
        e.setStatus(a.status());
        e.setDurationMs(a.durationMs());
        e.setClientIp(a.clientIp());
        mapper.insert(e);
    }

    @Override
    public List<AuditEntry> find(String username, String pathPrefix, boolean failedOnly, Instant from, Instant to, int limit) {
        LambdaQueryWrapper<AuditEntity> q = new LambdaQueryWrapper<AuditEntity>()
                .eq(username != null, AuditEntity::getUsername, username)
                .likeRight(pathPrefix != null, AuditEntity::getPath, pathPrefix)
                .and(failedOnly, w -> w.ge(AuditEntity::getStatus, 400).or().eq(AuditEntity::getStatus, 0))
                .ge(from != null, AuditEntity::getAt, from)
                .lt(to != null, AuditEntity::getAt, to)
                .orderByDesc(AuditEntity::getAt)
                .last("LIMIT " + limit);
        return mapper.selectList(q).stream().map(e -> new AuditEntry(e.getId(), e.getAt(), e.getUsername(), e.getRole(),
                e.getMethod(), e.getPath(), e.getQuery(), e.getStatus(), e.getDurationMs(), e.getClientIp())).toList();
    }

    @Override
    public int deleteBefore(Instant cutoff) {
        return mapper.delete(new LambdaQueryWrapper<AuditEntity>().lt(AuditEntity::getAt, cutoff));
    }
}
