-- This row must be removed by the real minute writer, not by this test fixture.
INSERT INTO live_gold_quote (observed_minute, observed_at, received_at, usd_per_ounce, source)
VALUES (date_trunc('minute', now() - INTERVAL '31 days'),
        date_trunc('minute', now() - INTERVAL '31 days'), now(), 1000, 'retention-fixture');
