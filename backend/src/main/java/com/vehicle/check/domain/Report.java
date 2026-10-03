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
 * 检测报告：报告编号、判定结论、分项结果快照、不合格项、签发人与签发日期、复检关联。
 */
@Data
@Entity
@Table(name = "vc_report")
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_no", nullable = false, unique = true)
    private String reportNo;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "plate_no", nullable = false)
    private String plateNo;

    @Column(name = "line_no", nullable = false)
    private String lineNo;

    @Column(name = "conclusion", nullable = false)
    private String conclusion;

    @Column(name = "conclusion_name", nullable = false)
    private String conclusionName;

    @Column(name = "snapshot")
    private String snapshot;

    @Column(name = "fail_items")
    private String failItems;

    @Column(name = "signer", nullable = false)
    private String signer;

    @Column(name = "sign_date", nullable = false)
    private LocalDate signDate;

    @Column(name = "retest_of")
    private String retestOf;

    @Column(name = "create_time")
    private LocalDateTime createTime;
}
