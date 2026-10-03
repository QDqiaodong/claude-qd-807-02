package com.vehicle.check.domain;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 设备台账：名称、型号、上次/下次标定日期、标定机构与结论、状态。
 */
@Data
@Entity
@Table(name = "vc_device")
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_name", nullable = false)
    private String deviceName;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "device_type", nullable = false)
    private String deviceType;

    @Column(name = "last_cal_date", nullable = false)
    private LocalDate lastCalDate;

    @Column(name = "next_cal_date", nullable = false)
    private LocalDate nextCalDate;

    @Column(name = "cal_org")
    private String calOrg;

    @Column(name = "cal_result")
    private String calResult;

    @Column(name = "status", nullable = false)
    private String status;
}
