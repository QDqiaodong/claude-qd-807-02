package com.vehicle.check.dto;

import java.time.LocalDate;

import lombok.Data;

/**
 * 安排复检请求：把不合格车辆送回流水线起点
 */
@Data
public class RetestRequest {

    private Long driverId;
    private Integer scheduleHour;
    private LocalDate checkDate;
    private String reason;
}
