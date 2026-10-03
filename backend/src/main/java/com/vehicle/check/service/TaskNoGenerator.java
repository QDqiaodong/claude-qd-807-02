package com.vehicle.check.service;

import java.sql.Date;
import java.time.LocalDate;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.vehicle.check.repository.TaskNoSeqRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 任务号发号器：以日期为键原子取号。
 * 派发/复检在各自事务内取号；{@code MANDATORY} 保证不会脱离绑定事务单独运行，
 * 发号随业务事务一起提交或回滚，失败不留序号垃圾也不出现重号。
 */
@Component
public class TaskNoGenerator {

    private final TaskNoSeqRepository seqRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public TaskNoGenerator(TaskNoSeqRepository seqRepository) {
        this.seqRepository = seqRepository;
    }

    /** 下一个普通检测任务号：TASK-yyyyMMdd-NNN */
    @Transactional(propagation = Propagation.MANDATORY)
    public String nextTaskNo(LocalDate date) {
        return String.format("TASK-%s-%03d", date.toString().replace("-", ""), nextSeq(date));
    }

    private long nextSeq(LocalDate date) {
        seqRepository.nextSeq(Date.valueOf(date));
        Number value = (Number) entityManager.createNativeQuery("SELECT LAST_INSERT_ID()")
                .getSingleResult();
        return value.longValue();
    }
}
