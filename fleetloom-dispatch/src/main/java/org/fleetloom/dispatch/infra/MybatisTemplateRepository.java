package org.fleetloom.dispatch.infra;

import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.fleetloom.dispatch.core.OrderTemplate;
import org.fleetloom.dispatch.core.Ports;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public class MybatisTemplateRepository implements Ports.TemplateRepository {

    private final OrderTemplateMapper mapper;
    private final JsonMapper json;

    public MybatisTemplateRepository(OrderTemplateMapper mapper, JsonMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public Optional<OrderTemplate> find(String code) {
        return Optional.ofNullable(mapper.selectById(code)).map(this::toDomain);
    }

    @Override
    public List<OrderTemplate> findAll() {
        return mapper.selectList(new LambdaQueryWrapper<OrderTemplateEntity>().orderByAsc(OrderTemplateEntity::getCode))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public void save(OrderTemplate t) {
        OrderTemplateEntity e = new OrderTemplateEntity();
        e.setCode(t.code());
        e.setName(t.name());
        e.setViaPoints(json.writeValueAsString(t.viaPoints()));
        if (mapper.selectById(t.code()) == null) {
            e.setPickAction(t.pickAction());
            e.setDropAction(t.dropAction());
            e.setViaAction(t.viaAction());
            e.setDescription(t.description());
            mapper.insert(e);
        } else {
            // nullable columns only through set(...), so they can be cleared
            mapper.update(e, new LambdaUpdateWrapper<OrderTemplateEntity>()
                    .eq(OrderTemplateEntity::getCode, t.code())
                    .set(OrderTemplateEntity::getPickAction, t.pickAction())
                    .set(OrderTemplateEntity::getDropAction, t.dropAction())
                    .set(OrderTemplateEntity::getViaAction, t.viaAction())
                    .set(OrderTemplateEntity::getDescription, t.description()));
        }
    }

    @Override
    public boolean delete(String code) {
        return mapper.deleteById(code) == 1;
    }

    private OrderTemplate toDomain(OrderTemplateEntity e) {
        List<String> via = e.getViaPoints() == null ? List.of() : json.readValue(e.getViaPoints(), new TypeReference<List<String>>() { });
        return new OrderTemplate(e.getCode(), e.getName(), e.getPickAction(), e.getDropAction(), via, e.getViaAction(),
                e.getDescription());
    }
}
