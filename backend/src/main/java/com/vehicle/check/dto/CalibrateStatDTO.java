package com.vehicle.check.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 设备标定状态统计（超期台数 / 临期台数）
 */
@Data
@NoArgsConstructor
public class CalibrateStatDTO {

    private Long overdueCount;
    private Long dueCount;

    public CalibrateStatDTO(Long overdueCount, Long dueCount) {
        this.overdueCount = overdueCount == null ? 0L : overdueCount;
        this.dueCount = dueCount == null ? 0L : dueCount;
    }
}
