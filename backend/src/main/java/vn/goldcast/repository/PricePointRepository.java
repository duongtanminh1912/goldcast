package vn.goldcast.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.goldcast.domain.PricePoint;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PricePointRepository extends JpaRepository<PricePoint, Long> {

    Optional<PricePoint> findByInstrumentIdAndObservedOn(Long instrumentId, LocalDate observedOn);

    Optional<PricePoint> findTopByInstrumentIdOrderByObservedOnDesc(Long instrumentId);

    /** Most recent observation at or before a date — used to align two series on one day. */
    Optional<PricePoint> findTopByInstrumentIdAndObservedOnLessThanEqualOrderByObservedOnDesc(
            Long instrumentId, LocalDate observedOn);

    List<PricePoint> findByInstrumentIdAndObservedOnBetweenOrderByObservedOnAsc(
            Long instrumentId, LocalDate from, LocalDate to);

    long countByInstrumentId(Long instrumentId);

    /**
     * Newest observations first. Callers that need chronological order reverse the list —
     * cheaper than sorting the whole table to take the tail of it.
     */
    @Query("select p from PricePoint p where p.instrument.id = :instrumentId order by p.observedOn desc")
    List<PricePoint> findRecent(@Param("instrumentId") Long instrumentId, Pageable pageable);

    /** Dates already stored, so ingest can skip rows instead of round-tripping each one. */
    @Query("select p.observedOn from PricePoint p where p.instrument.id = :instrumentId "
            + "and p.observedOn >= :from")
    List<LocalDate> findObservedDatesSince(@Param("instrumentId") Long instrumentId,
                                           @Param("from") LocalDate from);
}
