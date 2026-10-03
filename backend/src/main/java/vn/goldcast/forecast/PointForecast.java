package vn.goldcast.forecast;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Output of a single fit: the forecast path plus whatever parameters the model
 * settled on, so the API can explain itself rather than emitting a black-box number.
 */
public record PointForecast(ForecastModel model, double[] values, Map<String, Double> params) {

    public PointForecast {
        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("Forecast rỗng");
        }
        params = params == null ? Map.of() : Map.copyOf(params);
        values = values.clone();
    }

    public static PointForecast of(ForecastModel model, double[] values) {
        return new PointForecast(model, values, Map.of());
    }

    public static Map<String, Double> params(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("params() cần số lượng đối số chẵn");
        }
        Map<String, Double> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], ((Number) keyValues[i + 1]).doubleValue());
        }
        return map;
    }

    @Override
    public double[] values() {
        return values.clone();
    }

    public int horizon() {
        return values.length;
    }
}
