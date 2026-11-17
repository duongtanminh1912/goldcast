package vn.goldcast.forecast;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArxMacroForecasterTest {

    private static final int N = 400;

    /** Gold change on day t = 1.5 − 40 × (yield change on day t−1) + small noise. */
    private static double[][] generate(long seed) {
        Random random = new Random(seed);
        double[] yield = new double[N];
        double[] usd = new double[N];
        double[] gold = new double[N];
        yield[0] = 1.8;
        usd[0] = 120;
        gold[0] = 2000;
        for (int t = 1; t < N; t++) {
            yield[t] = yield[t - 1] + random.nextGaussian() * 0.05;
            usd[t] = usd[t - 1] + random.nextGaussian() * 0.3;
            double yieldChangeYesterday = t >= 2 ? yield[t - 1] - yield[t - 2] : 0;
            gold[t] = gold[t - 1] + 1.5 - 40 * yieldChangeYesterday + random.nextGaussian();
        }
        return new double[][] {gold, yield, usd};
    }

    private static ArxMacroForecaster forecaster(double[] yield, double[] usd) {
        return new ArxMacroForecaster(new double[][] {yield, usd},
                new String[] {"realYield10y", "usdBroad"}, 2, 2);
    }

    @Test
    void recoversAKnownLaggedDriverEffect() {
        double[][] data = generate(7);
        PointForecast forecast = forecaster(data[1], data[2]).forecast(data[0], 5);

        // lag2 is not asserted: it is collinear with the gold lag (d(t-1) already carries
        // e(t-2)), so only their combination is well identified, not each one alone.
        assertEquals(-40.0, forecast.params().get("realYield10y_lag1"), 2.0);
        assertEquals(0.0, forecast.params().get("usdBroad_lag1"), 0.5);
        assertEquals(1.5, forecast.params().get("intercept"), 0.3);
    }

    @Test
    void firstStepUsesTheLatestDriverChangeAndLaterStepsAssumeFlatDrivers() {
        double[][] data = generate(11);
        double[] yield = data[1];
        PointForecast forecast = forecaster(yield, data[2]).forecast(data[0], 3);

        double last = data[0][N - 1];
        double latestYieldChange = yield[N - 1] - yield[N - 2];
        double step1 = forecast.values()[0] - last;
        double step2 = forecast.values()[1] - forecast.values()[0];
        assertEquals(1.5 - 40 * latestYieldChange, step1, 1.0);
        assertEquals(1.5, step2, 1.0);
    }

    @Test
    void neverReadsDriverValuesBeyondTheSeriesItIsGiven() {
        double[][] data = generate(3);
        int prefix = 300;
        double[] gold = Arrays.copyOf(data[0], prefix);

        double[] yieldAltered = data[1].clone();
        double[] usdAltered = data[2].clone();
        for (int t = prefix; t < N; t++) {
            yieldAltered[t] = 99;
            usdAltered[t] = -99;
        }

        double[] original = forecaster(data[1], data[2]).forecast(gold, 10).values();
        double[] altered = forecaster(yieldAltered, usdAltered).forecast(gold, 10).values();
        assertArrayEquals(original, altered, 0.0);
    }

    @Test
    void skipsRowsWithoutDriverDataButRefusesToForecastWithoutTheLatestOne() {
        double[][] data = generate(5);
        double[] yield = data[1].clone();
        for (int t = 0; t < 50; t++) {
            yield[t] = Double.NaN; // macro history starts later than the price history
        }
        assertTrue(Double.isFinite(forecaster(yield, data[2]).forecast(data[0], 3).values()[2]));

        yield[N - 1] = Double.NaN;
        assertThrows(IllegalArgumentException.class, () -> forecaster(yield, data[2]).forecast(data[0], 3));
    }

    @Test
    void backtestOnlyEverSeesPrefixesAndBeatsNaiveWhenTheDriverMatters() {
        double[][] data = generate(13);
        BacktestResult arx = new Backtester(60).run(data[0], forecaster(data[1], data[2]), 1, 100);
        BacktestResult naive = new Backtester(60).run(data[0], new NaiveForecaster(), 1, 100);
        assertTrue(arx.overall().mase() < naive.overall().mase(),
                "ARX MASE " + arx.overall().mase() + " vs naive " + naive.overall().mase());
    }

    @Test
    void rejectsMismatchedDriverNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new ArxMacroForecaster(new double[][] {{1}}, new String[] {}, 2, 2));
    }
}
