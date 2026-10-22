package org.rcs3000.resource.carrier.infrastructure;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.rcs3000.resource.carrier.domain.Goods;
import org.rcs3000.resource.carrier.domain.GoodsRepository;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisGoodsRepository implements GoodsRepository {

    private final GoodsMapper mapper;
    private final Clock clock;

    public MybatisGoodsRepository(GoodsMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public List<Goods> find(String carrierCode) {
        return mapper.find(carrierCode).stream().map(Row::goods).toList();
    }

    @Override
    public void replace(String carrierCode, List<Goods> goods) {
        mapper.deleteAll(carrierCode);
        Instant now = clock.instant();
        for (int i = 0; i < goods.size(); i++) {
            Goods g = goods.get(i);
            mapper.insert(carrierCode, i, g.sku(), g.name(), g.quantity(), g.unit(), g.batchNo(), now);
        }
    }

    @Override
    public List<Stocked> findBySku(String skuPrefix, int limit) {
        return mapper.findBySku(skuPrefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%", limit)
                .stream().map(r -> new Stocked(r.carrierCode(), r.goods())).toList();
    }

    public record Row(String carrierCode, String sku, String name, BigDecimal quantity, String unit, String batchNo) {

        Goods goods() {
            return new Goods(sku, name, quantity, unit, batchNo);
        }
    }

    @Mapper
    public interface GoodsMapper {

        @Select("SELECT carrier_code, sku, name, quantity, unit, batch_no FROM carrier_goods WHERE carrier_code = #{code} ORDER BY line_no")
        List<Row> find(@Param("code") String carrierCode);

        @Select("""
                SELECT carrier_code, sku, name, quantity, unit, batch_no FROM carrier_goods
                WHERE sku LIKE #{pattern} ORDER BY sku, carrier_code, line_no LIMIT #{limit}
                """)
        List<Row> findBySku(@Param("pattern") String pattern, @Param("limit") int limit);

        @Delete("DELETE FROM carrier_goods WHERE carrier_code = #{code}")
        void deleteAll(@Param("code") String carrierCode);

        @Insert("""
                INSERT INTO carrier_goods (carrier_code, line_no, sku, name, quantity, unit, batch_no, updated_at)
                VALUES (#{code}, #{lineNo}, #{sku}, #{name}, #{quantity}, #{unit}, #{batchNo}, #{at})
                """)
        void insert(@Param("code") String carrierCode, @Param("lineNo") int lineNo, @Param("sku") String sku,
                @Param("name") String name, @Param("quantity") BigDecimal quantity, @Param("unit") String unit,
                @Param("batchNo") String batchNo, @Param("at") Instant at);
    }
}
