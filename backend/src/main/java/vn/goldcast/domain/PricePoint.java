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
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * One daily observation of one instrument.
 *
 * <p>{@code closePrice} is the canonical value every model reads. For dealers that quote
 * a two-way price it is the <em>sell</em> side — the price a retail buyer actually pays,
 * which is the number people mean when they ask what gold costs.
 */
@Entity
@Table(name = "price_point",
        uniqueConstraints = @UniqueConstraint(name = "price_point_unique",
                columnNames = {"instrument_id", "observed_on"}))
public class PricePoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_id", nullable = false)
    private Instrument instrument;

    @Column(name = "observed_on", nullable = false)
    private LocalDate observedOn;

    @Column(name = "close_price", nullable = false, precision = 20, scale = 4)
    private BigDecimal closePrice;

    @Column(name = "buy_price", precision = 20, scale = 4)
    private BigDecimal buyPrice;

    @Column(name = "sell_price", precision = 20, scale = 4)
    private BigDecimal sellPrice;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(name = "ingested_at", insertable = false, updatable = false)
    private OffsetDateTime ingestedAt;

    protected PricePoint() {
        // for JPA
    }

    public PricePoint(Instrument instrument, LocalDate observedOn, BigDecimal closePrice,
                      BigDecimal buyPrice, BigDecimal sellPrice, String source) {
        this.instrument = instrument;
        this.observedOn = observedOn;
        this.closePrice = closePrice;
        this.buyPrice = buyPrice;
        this.sellPrice = sellPrice;
        this.source = source;
    }

    public Long getId() {
        return id;
    }

    public Instrument getInstrument() {
        return instrument;
    }

    public LocalDate getObservedOn() {
        return observedOn;
    }

    public BigDecimal getClosePrice() {
        return closePrice;
    }

    public void setClosePrice(BigDecimal closePrice) {
        this.closePrice = closePrice;
    }

    public BigDecimal getBuyPrice() {
        return buyPrice;
    }

    public void setBuyPrice(BigDecimal buyPrice) {
        this.buyPrice = buyPrice;
    }

    public BigDecimal getSellPrice() {
        return sellPrice;
    }

    public void setSellPrice(BigDecimal sellPrice) {
        this.sellPrice = sellPrice;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public OffsetDateTime getIngestedAt() {
        return ingestedAt;
    }

    /** Dealer spread in absolute terms, or {@code null} when only one side is quoted. */
    public BigDecimal spread() {
        if (buyPrice == null || sellPrice == null) {
            return null;
        }
        return sellPrice.subtract(buyPrice);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof PricePoint point)) {
            return false;
        }
        return id != null && id.equals(point.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(observedOn);
    }
}
