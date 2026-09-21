package vn.goldcast.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One step of a forecast path, with its prediction intervals. */
@Entity
@Table(name = "forecast_point")
public class ForecastPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false)
    private ForecastRun run;

    @Column(name = "horizon_step", nullable = false)
    private int horizonStep;

    @Column(name = "target_on", nullable = false)
    private LocalDate targetOn;

    @Column(name = "point_value", nullable = false, precision = 20, scale = 4)
    private BigDecimal pointValue;

    @Column(name = "lower_80", precision = 20, scale = 4)
    private BigDecimal lower80;

    @Column(name = "upper_80", precision = 20, scale = 4)
    private BigDecimal upper80;

    @Column(name = "lower_95", precision = 20, scale = 4)
    private BigDecimal lower95;

    @Column(name = "upper_95", precision = 20, scale = 4)
    private BigDecimal upper95;

    protected ForecastPoint() {
        // for JPA
    }

    public ForecastPoint(int horizonStep, LocalDate targetOn, BigDecimal pointValue,
                         BigDecimal lower80, BigDecimal upper80,
                         BigDecimal lower95, BigDecimal upper95) {
        this.horizonStep = horizonStep;
        this.targetOn = targetOn;
        this.pointValue = pointValue;
        this.lower80 = lower80;
        this.upper80 = upper80;
        this.lower95 = lower95;
        this.upper95 = upper95;
    }

    public Long getId() {
        return id;
    }

    public ForecastRun getRun() {
        return run;
    }

    void setRun(ForecastRun run) {
        this.run = run;
    }

    public int getHorizonStep() {
        return horizonStep;
    }

    public LocalDate getTargetOn() {
        return targetOn;
    }

    public BigDecimal getPointValue() {
        return pointValue;
    }

    public BigDecimal getLower80() {
        return lower80;
    }

    public BigDecimal getUpper80() {
        return upper80;
    }

    public BigDecimal getLower95() {
        return lower95;
    }

    public BigDecimal getUpper95() {
        return upper95;
    }
}
