package com.vehicle.check.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 按引车员统计当日台次
 */
@Data
@NoArgsConstructor
public class DriverStatDTO {

    private Long driverId;
    private String driverName;
    private Long taskCount;

    public DriverStatDTO(Long driverId, Long taskCount) {
        this.driverId = driverId;
        this.taskCount = taskCount == null ? 0L : taskCount;
    }
}
