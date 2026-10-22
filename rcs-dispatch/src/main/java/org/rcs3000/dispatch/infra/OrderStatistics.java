package org.rcs3000.dispatch.infra;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.rcs3000.contracts.dispatch.OrderStatus;
import org.springframework.stereotype.Component;

/**
 * Order throughput and timing per hour (DSP-12). Charging orders are the platform's own business and
 * left out; transport and move orders count.
 */
@Component
public class OrderStatistics {

    static final int MAX_HOURS = 24 * 31;

    private final StatisticsMapper mapper;
    private final Clock clock;

    public OrderStatistics(StatisticsMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * @param avgSeconds average time from creation to completion of the completed orders, {@code null} if none
     */
    public record Hour(Instant hour, long completed, long failed, long cancelled, Double avgSeconds) {
    }

    /** @param open orders not finished yet, by status */
    public record Report(Instant since, List<Hour> hours, Hour total, Map<OrderStatus, Long> open) {
    }

    public Report lastHours(int hours) {
        int n = Math.clamp(hours, 1, MAX_HOURS);
        Instant currentHour = clock.instant().truncatedTo(ChronoUnit.HOURS);
        Instant since = currentHour.minus(Duration.ofHours(n - 1));
        Map<Instant, Hour> found = mapper.hourly(since).stream().map(StatisticsMapper.HourRow::toHour)
                .collect(Collectors.toMap(Hour::hour, Function.identity()));
        List<Hour> series = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Instant h = since.plus(Duration.ofHours(i));
            series.add(found.getOrDefault(h, new Hour(h, 0, 0, 0, null)));
        }
        Hour total = mapper.total(since).toHour();
        Map<OrderStatus, Long> open = new EnumMap<>(OrderStatus.class);
        for (OrderStatus s : List.of(OrderStatus.QUEUED, OrderStatus.EXECUTING, OrderStatus.SUSPENDED)) {
            open.put(s, 0L);
        }
        mapper.open().forEach(row -> open.put(OrderStatus.valueOf(row.getStatus()), row.getCount()));
        return new Report(since, series, total, open);
    }

    @Mapper
    public interface StatisticsMapper {

        String COUNTS = """
                count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                count(*) FILTER (WHERE status = 'FAILED') AS failed,
                count(*) FILTER (WHERE status = 'CANCELLED') AS cancelled,
                avg(extract(epoch FROM finished_at - created_at)) FILTER (WHERE status = 'COMPLETED') AS avg_seconds
                """;

        @Select("SELECT date_trunc('hour', finished_at) AS hour, " + COUNTS
                + " FROM transport_order WHERE finished_at >= #{since} AND type <> 'CHARGE' GROUP BY 1")
        List<HourRow> hourly(@Param("since") Instant since);

        @Select("SELECT NULL AS hour, " + COUNTS + " FROM transport_order WHERE finished_at >= #{since} AND type <> 'CHARGE'")
        HourRow total(@Param("since") Instant since);

        @Select("SELECT status, count(*) AS count FROM transport_order"
                + " WHERE status IN ('QUEUED', 'EXECUTING', 'SUSPENDED') AND type <> 'CHARGE' GROUP BY status")
        List<StatusRow> open();

        class HourRow {
            private Instant hour;
            private long completed;
            private long failed;
            private long cancelled;
            private Double avgSeconds;

            Hour toHour() {
                return new Hour(hour, completed, failed, cancelled,
                        avgSeconds == null ? null : Math.round(avgSeconds * 10) / 10.0);
            }

            public void setHour(Instant hour) {
                this.hour = hour;
            }

            public void setCompleted(long completed) {
                this.completed = completed;
            }

            public void setFailed(long failed) {
                this.failed = failed;
            }

            public void setCancelled(long cancelled) {
                this.cancelled = cancelled;
            }

            public void setAvgSeconds(Double avgSeconds) {
                this.avgSeconds = avgSeconds;
            }
        }

        class StatusRow {
            private String status;
            private long count;

            public String getStatus() {
                return status;
            }

            public void setStatus(String status) {
                this.status = status;
            }

            public long getCount() {
                return count;
            }

            public void setCount(long count) {
                this.count = count;
            }
        }
    }
}
