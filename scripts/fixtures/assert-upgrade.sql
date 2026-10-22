DO $$ BEGIN
  IF (SELECT count(*) FROM flyway_schema_history WHERE success) < 3
     OR EXISTS (SELECT 1 FROM flyway_schema_history WHERE NOT success) THEN
    RAISE EXCEPTION 'Upgrade migrations did not finish';
  END IF;
  IF (SELECT count(*) FROM instrument WHERE code = 'CI_UPGRADE' AND name = 'Existing V1 instrument') <> 1
     OR (SELECT count(*) FROM price_point p JOIN instrument i ON i.id = p.instrument_id
         WHERE i.code = 'CI_UPGRADE' AND p.observed_on = DATE '2025-01-02' AND p.close_price = 2500.1250) <> 1
     OR (SELECT count(*) FROM forecast_run r JOIN instrument i ON i.id = r.instrument_id
         WHERE i.code = 'CI_UPGRADE' AND r.model = 'NAIVE' AND r.train_size = 60 AND r.last_close = 2500.1250) <> 1
     OR (SELECT count(*) FROM forecast_point p JOIN forecast_run r ON r.id = p.run_id
         JOIN instrument i ON i.id = r.instrument_id WHERE i.code = 'CI_UPGRADE'
         AND p.horizon_step = 1 AND p.point_value = 2500.1250 AND p.target_on = DATE '2025-01-03') <> 1 THEN
    RAISE EXCEPTION 'Upgrade lost or changed existing domain data';
  END IF;
END $$;

DO $$ BEGIN
  IF to_regclass('live_gold_quote') IS NULL THEN
    RAISE EXCEPTION 'Upgrade did not create minute observations';
  END IF;
END $$;
