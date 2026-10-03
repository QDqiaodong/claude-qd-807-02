package com.vehicle.check.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 工位流转记录：任务在每个工位的检测项、结果值、操作员、时间。
 */
@Data
@Entity
@Table(name = "vc_task_station_record")
public class TaskStationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "station_id", nullable = false)
    private Long stationId;

    @Column(name = "station_type", nullable = false)
    private String stationType;

    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "result", nullable = false)
    private String result;

    @Column(name = "value_text")
    private String valueText;

    @Column(name = "operator_name")
    private String operatorName;

    @Column(name = "driver_id")
    private Long driverId;

    @Column(name = "record_time")
    private LocalDateTime recordTime;
}
