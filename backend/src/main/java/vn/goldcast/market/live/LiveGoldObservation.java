package vn.goldcast.market.live;

import java.math.BigDecimal;
import java.time.Instant;

public record LiveGoldObservation(BigDecimal usdPerOunce, Instant observedAt, Instant receivedAt) {}
