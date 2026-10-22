package vn.goldcast.market.live;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Repository
public class LiveGoldStore {
    private final JdbcTemplate jdbc;

    public LiveGoldStore(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
    }

    /** Commit the observation and retention together, then allow telemetry to advance. */
    @Transactional(timeout = 10)
    public void save(LiveGoldObservation observation, int retentionDays) {
        jdbc.update("""
                INSERT INTO live_gold_quote (observed_minute, observed_at, received_at, usd_per_ounce, source)
                VALUES (?, ?, ?, ?, 'gold-api.com')
                ON CONFLICT (observed_minute) DO UPDATE SET
                    observed_at = EXCLUDED.observed_at, received_at = EXCLUDED.received_at,
                    usd_per_ounce = EXCLUDED.usd_per_ounce, source = EXCLUDED.source
                WHERE EXCLUDED.observed_at > live_gold_quote.observed_at
                """, Timestamp.from(observation.observedAt().truncatedTo(ChronoUnit.MINUTES)),
                Timestamp.from(observation.observedAt()), Timestamp.from(observation.receivedAt()),
                observation.usdPerOunce());
        jdbc.update("DELETE FROM live_gold_quote WHERE observed_at < ?",
                Timestamp.from(observation.receivedAt().minus(retentionDays, ChronoUnit.DAYS)));
    }

    @Transactional(readOnly = true, timeout = 5)
    public List<LiveGoldObservation> recent(int limit) {
        return jdbc.query("""
                SELECT usd_per_ounce, observed_at, received_at FROM live_gold_quote
                ORDER BY observed_at DESC LIMIT ?
                """, (row, number) -> new LiveGoldObservation(row.getBigDecimal("usd_per_ounce"),
                row.getTimestamp("observed_at").toInstant(), row.getTimestamp("received_at").toInstant()), limit);
    }
}
