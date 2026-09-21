package vn.goldcast.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One observation as the API serves it.
 *
 * <p>Values arrive already rounded to the instrument's display scale, so the client never
 * has to decide how many decimals a VND amount deserves.
 */
public record PricePointDto(
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate date,
        BigDecimal close,
        BigDecimal buy,
        BigDecimal sell,
        BigDecimal spread,
        String source) {}
