package vn.goldcast.forecast;

/** Two-sided prediction intervals around a point forecast. */
public record PredictionInterval(double lower80, double upper80, double lower95, double upper95) {}
