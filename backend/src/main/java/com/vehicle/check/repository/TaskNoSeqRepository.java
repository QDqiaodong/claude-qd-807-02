package com.vehicle.check.repository;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.vehicle.check.domain.TaskNoSeq;

public interface TaskNoSeqRepository extends JpaRepository<TaskNoSeq, LocalDate> {

    /**
     * 原子取下一个任务序号：行存在则 +1（LAST_INSERT_ID 把新值带回本连接），不存在则插入 1。
     * 并发派发时行锁串行化，两个事务绝不会拿到同一序号。
     */
    @Modifying
    @Query(value = "INSERT INTO vc_task_no_seq(check_date, seq) VALUES (:date, LAST_INSERT_ID(1)) "
            + "ON DUPLICATE KEY UPDATE seq = LAST_INSERT_ID(seq + 1)", nativeQuery = true)
    int nextSeq(@Param("date") java.sql.Date date);
}
