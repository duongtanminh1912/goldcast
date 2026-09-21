package vn.goldcast.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A stored forecast, together with the backtest accuracy that was measured
 * <em>before</em> it was produced.
 *
 * <p>Persisting runs is what makes the site auditable: a month from now the accuracy
 * claims made on the day can be checked against what actually happened.
 */
@Entity
@Table(name = "forecast_run")
public class ForecastRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false)
    private Instrument instrument;

    @Column(nullable = false, length = 32)
    private String model;

    @Column(nullable = false)
    private int horizon;

    @Column(name = "train_size", nullable = false)
    private int trainSize;

    @Column(columnDefinition = "text")
    private String params;

    @Column(precision = 20, scale = 6)
    private BigDecimal mae;

    @Column(precision = 20, scale = 6)
    private BigDecimal rmse;

    @Column(precision = 12, scale = 6)
    private BigDecimal mape;

    @Column(precision = 12, scale = 6)
    private BigDecimal mase;

    @Column(name = "last_close", nullable = false, precision = 20, scale = 4)
    private BigDecimal lastClose;

    @Column(name = "last_close_on", nullable = false)
    private LocalDate lastCloseOn;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("horizonStep ASC")
    private List<ForecastPoint> points = new ArrayList<>();

    protected ForecastRun() {
        // for JPA
    }

    public ForecastRun(Instrument instrument, String model, int horizon, int trainSize,
                       BigDecimal lastClose, LocalDate lastCloseOn) {
        this.instrument = instrument;
        this.model = model;
        this.horizon = horizon;
        this.trainSize = trainSize;
        this.lastClose = lastClose;
        this.lastCloseOn = lastCloseOn;
    }

    public void addPoint(ForecastPoint point) {
        points.add(point);
        point.setRun(this);
    }

    public Long getId() {
        return id;
    }

    public Instrument getInstrument() {
        return instrument;
    }

    public String getModel() {
        return model;
    }

    public int getHorizon() {
        return horizon;
    }

    public int getTrainSize() {
        return trainSize;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public BigDecimal getMae() {
        return mae;
    }

    public void setMae(BigDecimal mae) {
        this.mae = mae;
    }

    public BigDecimal getRmse() {
        return rmse;
    }

    public void setRmse(BigDecimal rmse) {
        this.rmse = rmse;
    }

    public BigDecimal getMape() {
        return mape;
    }

    public void setMape(BigDecimal mape) {
        this.mape = mape;
    }

    public BigDecimal getMase() {
        return mase;
    }

    public void setMase(BigDecimal mase) {
        this.mase = mase;
    }

    public BigDecimal getLastClose() {
        return lastClose;
    }

    public LocalDate getLastCloseOn() {
        return lastCloseOn;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public List<ForecastPoint> getPoints() {
        return points;
    }
}
