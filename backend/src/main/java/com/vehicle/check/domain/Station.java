package com.vehicle.check.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 检测工位：登录/外观/尾气/制动/灯光/底盘/总检，含顺序号与状态（空闲/占用/故障）
 */
@Data
@Entity
@Table(name = "vc_station")
public class Station {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "line_id", nullable = false)
    private Long lineId;

    @Column(name = "station_type", nullable = false)
    private String stationType;

    @Column(name = "station_name", nullable = false)
    private String stationName;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "remark")
    private String remark;
}
