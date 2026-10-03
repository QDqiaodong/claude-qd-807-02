package com.vehicle.check.dto;

import java.time.LocalDate;

import lombok.Data;

/**
 * 建档并派发检测任务请求
 */
@Data
public class TaskCreateRequest {

    private Long vehicleId;
    private Long lineId;
    private Long driverId;
    private Integer scheduleHour;
    private LocalDate checkDate;
}
