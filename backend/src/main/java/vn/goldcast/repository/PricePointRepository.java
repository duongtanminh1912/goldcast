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

    /**
     * Rows stored since a date, loaded once per instrument so ingest can skip or update
     * quotes it already has without a query per quote.
     */
    @Query("select p from PricePoint p where p.instrument.id = :instrumentId "
            + "and p.observedOn >= :from")
    List<PricePoint> findSince(@Param("instrumentId") Long instrumentId,
                               @Param("from") LocalDate from);
}
