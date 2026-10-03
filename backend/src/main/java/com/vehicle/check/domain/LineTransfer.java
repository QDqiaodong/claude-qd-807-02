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
 * 在检车辆跨线恢复处置记录。
 * <p>
 * success_episode_key 仅在 {@code result=SUCCESS} 时落值（MySQL 唯一索引允许多个 NULL），
 * 与「目标工位原子占位 UPDATE」一起保证：同一处置幕（任务 + 故障工位）只允许一次跨线恢复成功，
 * 两名值班人员同时确认、或确认瞬间目标工位被占用/停用，都不会产生半迁移。
 * <p>
 * FAILED / PENDING 允许留多条操作痕迹。
 */
@Data
@Entity
@Table(name = "vc_line_transfer")
public class LineTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "task_no", nullable = false)
    private String taskNo;

    @Column(name = "plate_no", nullable = false)
    private String plateNo;

    @Column(name = "from_line_id", nullable = false)
    private Long fromLineId;

    @Column(name = "from_line_no", nullable = false)
    private String fromLineNo;

    @Column(name = "fault_station_id", nullable = false)
    private Long faultStationId;

    @Column(name = "fault_station_type", nullable = false)
    private String faultStationType;

    @Column(name = "fault_seq_no", nullable = false)
    private Integer faultSeqNo;

    @Column(name = "to_line_id")
    private Long toLineId;

    @Column(name = "to_line_no")
    private String toLineNo;

    @Column(name = "target_station_id")
    private Long targetStationId;

    /** SUCCESS 转线成功 / FAILED 尝试失败 / PENDING 保持待处置 */
    @Column(name = "result", nullable = false)
    private String result;

    @Column(name = "fail_reason")
    private String failReason;

    /** 处置幕：taskId + faultStationId */
    @Column(name = "episode_key", nullable = false)
    private String episodeKey;

    /** SUCCESS 幂等键，同幕唯一；非成功为 NULL */
    @Column(name = "success_episode_key")
    private String successEpisodeKey;

    @Column(name = "operator_name", nullable = false)
    private String operatorName;

    @Column(name = "transfer_time", nullable = false)
    private LocalDateTime transferTime;
}
