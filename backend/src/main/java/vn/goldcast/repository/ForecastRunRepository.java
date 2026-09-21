package vn.goldcast.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import vn.goldcast.domain.ForecastRun;

import java.util.List;

/**
 * Stored forecast runs.
 *
 * <p>Runs are written when a caller asks for {@code ?persist=true}. Keeping them is what
 * makes past accuracy claims checkable later against what actually happened, rather than
 * only ever showing a forecast's accuracy as measured on the day it was made.
 */
public interface ForecastRunRepository extends JpaRepository<ForecastRun, Long> {

    @EntityGraph(attributePaths = {"instrument"})
    List<ForecastRun> findTop50ByInstrumentIdOrderByCreatedAtDesc(Long instrumentId);
}
