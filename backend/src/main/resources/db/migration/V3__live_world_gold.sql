-- Minute snapshots stay separate from the daily close series used by forecasts.
CREATE TABLE live_gold_quote (
    observed_minute TIMESTAMPTZ PRIMARY KEY,
    observed_at     TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL,
    usd_per_ounce   NUMERIC(20, 6) NOT NULL CHECK (usd_per_ounce > 0),
    source          VARCHAR(64) NOT NULL,
    CHECK (observed_at >= observed_minute AND observed_at < observed_minute + INTERVAL '1 minute')
);
CREATE INDEX idx_live_gold_quote_observed ON live_gold_quote (observed_at DESC);
