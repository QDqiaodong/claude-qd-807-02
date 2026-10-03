package com.vehicle.check.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 检测线：线号、类型（安检/环保/综检）、状态（运行/停用）
 */
@Data
@Entity
@Table(name = "vc_line")
public class Line {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "line_no", nullable = false, unique = true)
    private String lineNo;

    @Column(name = "line_name", nullable = false)
    private String lineName;

    @Column(name = "line_type", nullable = false)
    private String lineType;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "daily_capacity")
    private Integer dailyCapacity;

    /** 承接车辆类型，逗号分隔：SMALL_BUS/TRUCK/HAZMAT */
    @Column(name = "accept_vehicle_types", nullable = false)
    private String acceptVehicleTypes = "SMALL_BUS,TRUCK,HAZMAT";

    @Column(name = "remark")
    private String remark;
}
