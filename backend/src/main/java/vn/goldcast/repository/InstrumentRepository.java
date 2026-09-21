package vn.goldcast.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import vn.goldcast.domain.Instrument;
import vn.goldcast.domain.InstrumentKind;

import java.util.List;
import java.util.Optional;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {

    Optional<Instrument> findByCodeIgnoreCase(String code);

    List<Instrument> findByActiveTrueOrderBySortOrderAscCodeAsc();

    List<Instrument> findByKindAndActiveTrueOrderBySortOrderAsc(InstrumentKind kind);
}
