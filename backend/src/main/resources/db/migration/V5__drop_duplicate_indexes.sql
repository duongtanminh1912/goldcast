-- V1 created two indexes that repeat the btree behind a UNIQUE constraint on the same
-- columns. Each one doubled the index writes of every ingest and forecast run and bought
-- nothing: a btree on (instrument_id, observed_on) is read backwards just as well for
-- ORDER BY observed_on DESC.
--   idx_price_point_instrument_date (instrument_id, observed_on DESC) ~ price_point_unique
--   idx_forecast_point_run          (run_id, horizon_step)            = forecast_point_unique
-- Only indexes go, so the previous application version runs unchanged on this schema
-- (rollback does not restore the database).
DROP INDEX IF EXISTS idx_price_point_instrument_date;
DROP INDEX IF EXISTS idx_forecast_point_run;
