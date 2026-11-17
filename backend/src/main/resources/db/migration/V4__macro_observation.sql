-- Macro drivers used as lagged regressors by the ARX_MACRO model.
-- Kept apart from price_point on purpose: a real yield can be negative, and these
-- series are model inputs, not instruments shown in the market views.
CREATE TABLE macro_observation (
    series_code VARCHAR(32)    NOT NULL,
    observed_on DATE           NOT NULL,
    value       NUMERIC(20, 6) NOT NULL,
    source      VARCHAR(64)    NOT NULL,
    PRIMARY KEY (series_code, observed_on)
);
