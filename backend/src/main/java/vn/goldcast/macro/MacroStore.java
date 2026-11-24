package vn.goldcast.macro;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/** Reads and writes {@code macro_observation}. Writes join the caller's transaction. */
@Repository
public class MacroStore {

    private final JdbcTemplate jdbc;

    public MacroStore(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(10);
    }

    /**
     * Inserts new dates and rewrites dates whose value changed. Re-running over data
     * already stored changes nothing.
     *
     * @return {@code {inserted, updated}}
     */
    public int[] upsert(MacroSeries series, List<MacroObservation> observations, String source) {
        if (observations.isEmpty()) {
            return new int[] {0, 0};
        }
        LocalDate earliest = observations.stream()
                .map(MacroObservation::observedOn).min(LocalDate::compareTo).orElseThrow();
        NavigableMap<LocalDate, Double> known = since(series, earliest);

        List<Object[]> inserts = new ArrayList<>();
        List<Object[]> updates = new ArrayList<>();
        for (MacroObservation observation : observations) {
            BigDecimal value = BigDecimal.valueOf(observation.value()).setScale(6, RoundingMode.HALF_UP);
            Double stored = known.get(observation.observedOn());
            if (stored == null) {
                inserts.add(new Object[] {series.name(), Date.valueOf(observation.observedOn()), value, source});
            } else if (BigDecimal.valueOf(stored).compareTo(value) != 0) {
                updates.add(new Object[] {value, source, series.name(), Date.valueOf(observation.observedOn())});
            }
        }
        if (!inserts.isEmpty()) {
            jdbc.batchUpdate("INSERT INTO macro_observation (series_code, observed_on, value, source) "
                    + "VALUES (?, ?, ?, ?) ON CONFLICT (series_code, observed_on) DO NOTHING", inserts);
        }
        if (!updates.isEmpty()) {
            jdbc.batchUpdate("UPDATE macro_observation SET value = ?, source = ? "
                    + "WHERE series_code = ? AND observed_on = ?", updates);
        }
        return new int[] {inserts.size(), updates.size()};
    }

    /** Values from {@code from} onwards, keyed by date. */
    public NavigableMap<LocalDate, Double> since(MacroSeries series, LocalDate from) {
        NavigableMap<LocalDate, Double> out = new TreeMap<>();
        jdbc.query("SELECT observed_on, value FROM macro_observation "
                        + "WHERE series_code = ? AND observed_on >= ? ORDER BY observed_on",
                row -> {
                    out.put(row.getDate(1).toLocalDate(), row.getBigDecimal(2).doubleValue());
                },
                series.name(), Date.valueOf(from));
        return out;
    }

    /** Newest stored observation date of a series, whatever its source. */
    public Optional<LocalDate> latestDate(MacroSeries series) {
        Date latest = jdbc.queryForObject(
                "SELECT max(observed_on) FROM macro_observation WHERE series_code = ?", Date.class, series.name());
        return Optional.ofNullable(latest).map(Date::toLocalDate);
    }

    public long count(MacroSeries series) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM macro_observation WHERE series_code = ?", Long.class, series.name());
        return count == null ? 0 : count;
    }

    public boolean hasSourceSince(MacroSeries series, LocalDate from, String source) {
        Boolean found = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM macro_observation "
                        + "WHERE series_code = ? AND observed_on >= ? AND source = ?)",
                Boolean.class, series.name(), Date.valueOf(from), source);
        return Boolean.TRUE.equals(found);
    }
}
