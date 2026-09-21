package vn.goldcast.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A price history with enough context to render it honestly: where the numbers came from,
 * and whether any of them are synthetic.
 */
public record SeriesDto(
        InstrumentDto instrument,
        List<PricePointDto> points,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate from,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate to,
        int count,
        BigDecimal latestClose,
        BigDecimal changeAbsolute,
        Double changePercent,
        List<String> sources,
        boolean containsSyntheticData) {}
