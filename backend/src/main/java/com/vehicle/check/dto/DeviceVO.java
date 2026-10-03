package com.vehicle.check.dto;

import java.time.LocalDate;

import lombok.Data;

/**
 * 设备视图：台账 + 下次标定剩余天数 + 预警级别（NORMAL/DUE/OVERDUE）
 */
@Data
public class DeviceVO {

    private Long id;
    private String deviceName;
    private String model;
    private String deviceType;
    private String deviceTypeName;
    private LocalDate lastCalDate;
    private LocalDate nextCalDate;
    private String calOrg;
    private String calResult;
    private String status;
    private String statusName;
    private Long remainDays;
    private String level;
}
