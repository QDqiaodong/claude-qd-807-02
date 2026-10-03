package com.vehicle.check.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工位排队车辆数（JPQL 分组统计结果）
 */
@Data
@NoArgsConstructor
public class StationQueueDTO {

    private Long stationId;
    private Long queueCount;

    public StationQueueDTO(Long stationId, Long queueCount) {
        this.stationId = stationId;
        this.queueCount = queueCount;
    }
}
