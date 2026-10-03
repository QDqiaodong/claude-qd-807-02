package com.vehicle.check.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vehicle.check.domain.LineTransfer;

public interface LineTransferRepository extends JpaRepository<LineTransfer, Long> {

    List<LineTransfer> findTop20ByOrderByTransferTimeDesc();

    List<LineTransfer> findByTaskIdOrderByTransferTimeAsc(Long taskId);

    Optional<LineTransfer> findBySuccessEpisodeKey(String successEpisodeKey);

    /** 该车最近一次成功转线（并发失败方据此看到最新去向） */
    Optional<LineTransfer> findFirstByTaskIdAndResultOrderByIdDesc(Long taskId, String result);
}
