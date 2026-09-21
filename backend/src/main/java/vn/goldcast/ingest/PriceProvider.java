package vn.goldcast.ingest;

import java.util.List;

/**
 * A source of price observations.
 *
 * <p>Providers are looked up by {@link #id()}, which matches {@code instrument.source} in
 * the database. Adding a new dealer feed means adding an implementation and a row — the
 * ingest service itself never learns about specific vendors.
 *
 * <p>Implementations must not throw for ordinary upstream failures: a feed being down is
 * expected operational weather, not an error condition. Return an empty list and let the
 * ingest service record it.
 */
public interface PriceProvider {

    /** Stable identifier, matched against {@code instrument.source}. */
    String id();

    /** Human-readable name shown in the API's source attribution. */
    String displayName();

    /** Where the data comes from, shown to users so the numbers are traceable. */
    String attribution();

    /** Whether this provider is switched on in configuration. */
    boolean enabled();

    /**
     * Fetches whatever history the upstream will give, for the instrument codes requested.
     *
     * @param instrumentCodes codes this provider owns, never empty
     * @param maxHistoryDays  how far back to ask for; providers may return less
     * @return observations in any order, possibly empty; never {@code null}
     */
    List<ProviderQuote> fetch(List<String> instrumentCodes, int maxHistoryDays);
}
