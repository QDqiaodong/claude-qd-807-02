package com.vehicle.check.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 排班小时格占用：某个未结束任务（CHECKING/RETEST）占住「引车员 × 日期 × 小时段」一格。
 * <p>
 * 库内两道唯一索引（MySQL 生成列，已结束行为 NULL，不占名额）：
 * <ul>
 *   <li>{@code uk_slot_active(driver_id, check_date, schedule_hour)}：同一引车员同日同小时只能有一个未结束任务；</li>
 *   <li>{@code uk_task_active(task_id)}：同一任务任何时刻只能占一个格，并发改派下不可能一格变两格。</li>
 * </ul>
 * 任务合格/不合格后 {@link #active} 置 0，格子释放给后续车辆。
 */
@Data
@Entity
@Table(name = "vc_schedule_slot")
public class ScheduleSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "driver_id", nullable = false)
    private Long driverId;

    @Column(name = "check_date", nullable = false)
    private LocalDate checkDate;

    @Column(name = "schedule_hour", nullable = false)
    private Integer scheduleHour;

    @Column(name = "active", nullable = false)
    private Boolean active = Boolean.TRUE;

    @Column(name = "create_time", insertable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time", insertable = false, updatable = false)
    private LocalDateTime updateTime;
}
