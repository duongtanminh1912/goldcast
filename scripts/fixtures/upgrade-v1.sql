DO $$ BEGIN
  IF (SELECT count(*) FROM flyway_schema_history WHERE success) <> 1
     OR NOT EXISTS (SELECT 1 FROM flyway_schema_history WHERE version = '1' AND success) THEN
    RAISE EXCEPTION 'Upgrade fixture requires exactly the V1 baseline';
  END IF;
END $$;
-- Data owned by the migration test; providers cannot alter this instrument.
INSERT INTO instrument (code, name, kind, currency, unit, source)
VALUES ('CI_UPGRADE', 'Existing V1 instrument', 'SPOT_GOLD', 'USD', 'USD_PER_TROY_OUNCE', 'ci-fixture');
INSERT INTO price_point (instrument_id, observed_on, close_price, source)
SELECT id, DATE '2025-01-02', 2500.1250, 'ci-fixture' FROM instrument WHERE code = 'CI_UPGRADE';
INSERT INTO forecast_run (instrument_id, model, horizon, train_size, last_close, last_close_on)
SELECT id, 'NAIVE', 1, 60, 2500.1250, DATE '2025-01-02' FROM instrument WHERE code = 'CI_UPGRADE';
INSERT INTO forecast_point (run_id, horizon_step, target_on, point_value)
SELECT r.id, 1, DATE '2025-01-03', 2500.1250 FROM forecast_run r
JOIN instrument i ON i.id = r.instrument_id WHERE i.code = 'CI_UPGRADE';
