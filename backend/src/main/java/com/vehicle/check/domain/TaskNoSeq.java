package com.vehicle.check.domain;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 任务号日序列：以日期为键原子取号，避免并发派发用 count()+1 撞 uk_task_no。
 */
@Data
@Entity
@Table(name = "vc_task_no_seq")
public class TaskNoSeq {

    @Id
    @Column(name = "check_date")
    private LocalDate checkDate;

    @Column(name = "seq", nullable = false)
    private Integer seq;
}
