package com.vehicle.check.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.ColumnTransformer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 检测任务：车辆 + 检测线 + 当前工位 + 各工位结果值 + 状态。
 * <p>
 * 本仓技术标识之二：brakeRate 走 {@code @ColumnTransformer} 做单位换算，
 * 库里存小数（0.6230），实体与接口里是百分比（62.30）。
 */
@Data
@Entity
@Table(name = "vc_task")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_no", nullable = false, unique = true)
    private String taskNo;

    @Column(name = "vehicle_id", nullable = false)
    private Long vehicleId;

    @Column(name = "line_id", nullable = false)
    private Long lineId;

    @Column(name = "current_station_id")
    private Long currentStationId;

    @Column(name = "current_seq_no", nullable = false)
    private Integer currentSeqNo;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "driver_id")
    private Long driverId;

    @Column(name = "schedule_hour")
    private Integer scheduleHour;

    @Column(name = "check_date", nullable = false)
    private LocalDate checkDate;

    @Column(name = "co_value")
    private BigDecimal coValue;

    @Column(name = "hc_value")
    private Integer hcValue;

    @Column(name = "brake_rate")
    @ColumnTransformer(read = "brake_rate * 100", write = "? / 100")
    private BigDecimal brakeRate;

    @Column(name = "light_intensity")
    private Integer lightIntensity;

    @Column(name = "chassis_defects")
    private String chassisDefects;

    @Column(name = "retest_times", nullable = false)
    private Integer retestTimes;

    /** 复检任务关联的原报告编号；跨线恢复后必须原样保留，不能因换线洗掉 */
    @Column(name = "origin_report_no")
    private String originReportNo;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "finish_time")
    private LocalDateTime finishTime;
}
