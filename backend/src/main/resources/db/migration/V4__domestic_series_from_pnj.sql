-- SJC's XML feed now returns 404 and its site sits behind a Cloudflare challenge. PNJ
-- publishes SJC bar prices per city, so the two SJC series move there unchanged in meaning.
UPDATE instrument SET source = 'pnj' WHERE code IN ('SJC_HCM', 'SJC_HN');

-- PNJ does not sell SJC's own ring, so that series is retired rather than silently
-- relabelled; PNJ's plain 999.9 ring is the closest real product and gets its own code.
UPDATE instrument SET active = FALSE WHERE code = 'SJC_RING';

INSERT INTO instrument (code, name, kind, currency, unit, region, source, has_spread, sort_order)
VALUES ('PNJ_RING', 'Nhẫn trơn PNJ 999.9', 'VN_GOLD', 'VND', 'VND_PER_TAEL', 'Hồ Chí Minh', 'pnj', TRUE, 50)
ON CONFLICT (code) DO NOTHING;
