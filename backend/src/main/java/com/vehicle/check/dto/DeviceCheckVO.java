package com.vehicle.check.dto;

import java.time.LocalDate;

import lombok.Data;

/**
 * 候选线上单个工位的设备适配 / 标定校验明细。
 * 负载较低不能凌驾设备适配与标定有效性，因此每个剩余工位都要逐项交代。
 */
@Data
public class DeviceCheckVO {

    private Integer seqNo;
    private String stationType;
    private String stationName;
    private String stationStatus;
    private String stationStatusName;
    private Long deviceId;
    private String deviceName;
    private String model;
    private LocalDate nextCalDate;
    private Long remainDays;
    /** OK 标定有效 / DUE 临期（仍可用） / OVERDUE 已超期 / NONE 无设备（登录、总检允许） */
    private String level;
    private String levelName;
    private boolean valid;
}
