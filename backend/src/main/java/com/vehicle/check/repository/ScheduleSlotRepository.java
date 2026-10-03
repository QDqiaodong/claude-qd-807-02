package com.vehicle.check.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vehicle.check.domain.ScheduleSlot;

public interface ScheduleSlotRepository extends JpaRepository<ScheduleSlot, Long> {

    /** 任务当前的有效占格（未结束任务最多一条） */
    Optional<ScheduleSlot> findFirstByTaskIdAndActiveTrue(Long taskId);

    /** 某日全部有效占格（排班页恢复服务端格子状态） */
    List<ScheduleSlot> findByCheckDateAndActiveTrue(LocalDate checkDate);

    /**
     * 释放任务当前小时格。任务结束（合格/不合格）或改派时调用；
     * 旧行保留为 active=0 的痕迹，唯一索引随即放行，新车辆可再占该格。
     * 条件带 active=1：并发下最多一个事务返回 1。
     * clearAutomatically：改派随后插入的新占格不能被一级缓存里的旧行污染。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ScheduleSlot s set s.active = false "
            + "where s.taskId = :taskId and s.active = true")
    int releaseByTask(@Param("taskId") Long taskId);
}
