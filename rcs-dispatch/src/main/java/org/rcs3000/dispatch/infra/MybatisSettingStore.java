package org.rcs3000.dispatch.infra;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.rcs3000.dispatch.app.SettingStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class MybatisSettingStore implements SettingStore {

    private final SettingMapper mapper;
    private final Clock clock;

    public MybatisSettingStore(SettingMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Map<String, String> load() {
        Map<String, String> values = new LinkedHashMap<>();
        mapper.findAll().forEach(row -> values.put(row.name(), row.value()));
        return values;
    }

    @Override
    @Transactional
    public void save(Map<String, String> values) {
        values.forEach((name, value) -> mapper.upsert(name, value, clock.instant()));
    }

    public record Row(String name, String value) {
    }

    @Mapper
    public interface SettingMapper {

        @Select("SELECT name, value FROM dispatch_setting ORDER BY name")
        List<Row> findAll();

        @Insert("""
                INSERT INTO dispatch_setting (name, value, updated_at) VALUES (#{name}, #{value}, #{at})
                ON CONFLICT (name) DO UPDATE SET value = EXCLUDED.value, updated_at = EXCLUDED.updated_at
                """)
        void upsert(@Param("name") String name, @Param("value") String value, @Param("at") java.time.Instant at);
    }
}
