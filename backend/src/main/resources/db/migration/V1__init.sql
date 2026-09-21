-- goldcast: initial schema
-- Every price series (world gold, VND FX, VN gold brands) is modelled as one
-- "instrument" with a daily observation series, so the forecasting engine is
-- agnostic to what it is forecasting.

CREATE TABLE instrument (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL UNIQUE,
    name        VARCHAR(128) NOT NULL,
    kind        VARCHAR(24)  NOT NULL,   -- SPOT_GOLD | VN_GOLD | FX
    currency    VARCHAR(8)   NOT NULL,   -- USD | VND
    unit        VARCHAR(32)  NOT NULL,   -- USD_PER_TROY_OUNCE | VND_PER_TAEL | VND_PER_USD
    region      VARCHAR(64),             -- e.g. "Hồ Chí Minh", NULL for global series
    source      VARCHAR(64)  NOT NULL,   -- provider id that owns this series
    has_spread  BOOLEAN      NOT NULL DEFAULT FALSE, -- true when buy/sell quotes exist
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order  INT          NOT NULL DEFAULT 100,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT instrument_kind_chk CHECK (kind IN ('SPOT_GOLD', 'VN_GOLD', 'FX'))
);

COMMENT ON COLUMN instrument.has_spread IS
    'VN gold dealers quote a buy and a sell price; world spot and FX carry a single close.';

CREATE TABLE price_point (
    id            BIGSERIAL PRIMARY KEY,
    instrument_id BIGINT        NOT NULL REFERENCES instrument (id) ON DELETE CASCADE,
    observed_on   DATE          NOT NULL,
    -- close_price is the canonical series used by every model.
    -- For VN gold it is the sell price (what a buyer actually pays).
    close_price   NUMERIC(20, 4) NOT NULL,
    buy_price     NUMERIC(20, 4),
    sell_price    NUMERIC(20, 4),
    source        VARCHAR(64)   NOT NULL,
    ingested_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT price_point_unique UNIQUE (instrument_id, observed_on),
    CONSTRAINT price_point_positive_chk CHECK (close_price > 0)
);

CREATE INDEX idx_price_point_instrument_date
    ON price_point (instrument_id, observed_on DESC);

CREATE TABLE forecast_run (
    id            BIGSERIAL PRIMARY KEY,
    instrument_id BIGINT        NOT NULL REFERENCES instrument (id) ON DELETE CASCADE,
    model         VARCHAR(32)   NOT NULL,
    horizon       INT           NOT NULL,
    train_size    INT           NOT NULL,
    params        TEXT,
    -- Backtest accuracy of this model on this series, measured before the forecast
    -- was produced. NULL when the history was too short to backtest.
    mae           NUMERIC(20, 6),
    rmse          NUMERIC(20, 6),
    mape          NUMERIC(12, 6),
    mase          NUMERIC(12, 6),
    last_close    NUMERIC(20, 4) NOT NULL,
    last_close_on DATE          NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT forecast_run_horizon_chk CHECK (horizon BETWEEN 1 AND 365)
);

CREATE INDEX idx_forecast_run_instrument_created
    ON forecast_run (instrument_id, created_at DESC);

CREATE TABLE forecast_point (
    id             BIGSERIAL PRIMARY KEY,
    run_id         BIGINT         NOT NULL REFERENCES forecast_run (id) ON DELETE CASCADE,
    horizon_step   INT            NOT NULL,
    target_on      DATE           NOT NULL,
    point_value    NUMERIC(20, 4) NOT NULL,
    lower_80       NUMERIC(20, 4),
    upper_80       NUMERIC(20, 4),
    lower_95       NUMERIC(20, 4),
    upper_95       NUMERIC(20, 4),
    CONSTRAINT forecast_point_unique UNIQUE (run_id, horizon_step)
);

CREATE INDEX idx_forecast_point_run ON forecast_point (run_id, horizon_step);
