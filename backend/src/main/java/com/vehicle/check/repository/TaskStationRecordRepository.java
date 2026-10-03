package com.vehicle.check.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.vehicle.check.domain.TaskStationRecord;
import com.vehicle.check.dto.FailItemDTO;
import com.vehicle.check.dto.StationStatDTO;

public interface TaskStationRecordRepository extends JpaRepository<TaskStationRecord, Long> {

    List<TaskStationRecord> findByTaskIdOrderBySeqNoAsc(Long taskId);

    /**
     * 按工位统计通过率
     */
    @Query("select new com.vehicle.check.dto.StationStatDTO(r.stationType, count(r), "
            + "sum(case when r.result = 'PASS' then 1 else 0 end)) "
            + "from TaskStationRecord r "
            + "group by r.stationType")
    List<StationStatDTO> statPassRateByStation();

    /**
     * 不合格项排行
     */
    @Query("select new com.vehicle.check.dto.FailItemDTO(r.stationType, r.itemName, count(r)) "
            + "from TaskStationRecord r "
            + "where r.result = 'FAIL' "
            + "group by r.stationType, r.itemName "
            + "order by count(r) desc")
    List<FailItemDTO> rankFailItems();
}
