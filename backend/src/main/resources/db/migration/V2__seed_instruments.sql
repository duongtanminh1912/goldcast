-- Reference data. Adding a new dealer/series later is an INSERT, not a code change.

INSERT INTO instrument (code, name, kind, currency, unit, region, source, has_spread, sort_order)
VALUES
    ('XAUUSD',   'Vàng thế giới (spot)',      'SPOT_GOLD', 'USD', 'USD_PER_TROY_OUNCE', NULL,          'stooq',  FALSE, 10),
    ('USDVND',   'Tỷ giá USD/VND',            'FX',        'VND', 'VND_PER_USD',        NULL,          'stooq',  FALSE, 20),
    ('SJC_HCM',  'Vàng miếng SJC — TP.HCM',   'VN_GOLD',   'VND', 'VND_PER_TAEL',       'Hồ Chí Minh', 'sjc',    TRUE,  30),
    ('SJC_HN',   'Vàng miếng SJC — Hà Nội',   'VN_GOLD',   'VND', 'VND_PER_TAEL',       'Hà Nội',      'sjc',    TRUE,  40),
    ('SJC_RING', 'Vàng nhẫn SJC 99,99',       'VN_GOLD',   'VND', 'VND_PER_TAEL',       'Hồ Chí Minh', 'sjc',    TRUE,  50)
ON CONFLICT (code) DO NOTHING;
