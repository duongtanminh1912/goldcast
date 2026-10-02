-- Stooq's CSV endpoint started serving a JavaScript browser check instead of data, so the
-- world gold and FX series move to Twelve Data. Ingest picks providers by this column.
UPDATE instrument SET source = 'twelvedata' WHERE code IN ('XAUUSD', 'USDVND');
