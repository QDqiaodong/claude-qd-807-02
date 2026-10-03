package com.vehicle.check.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.vehicle.check.domain.Station;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface StationRepository extends JpaRepository<Station, Long> {

    List<Station> findByLineIdOrderBySeqNoAsc(Long lineId);

    List<Station> findByStationTypeOrderByIdAsc(String stationType);

    long countByStatus(String status);

    /**
     * 目标工位原子占位：只有仍处于 IDLE 的工位能被占成 OCCUPIED。
     * 两名值班人员同时确认、或确认瞬间工位被占用/停用/故障时，数据库行级 UPDATE 串行化，
     * 最多一个事务返回受影响行数 1，杜绝「旧线仍占位、新线已接车」的半迁移。
     */
    @Modifying
    @Query("update Station s set s.status = 'OCCUPIED' "
            + "where s.id = :stationId and s.status = 'IDLE'")
    int claimStation(@Param("stationId") Long stationId);

    /** 悲观行锁：转线确认时锁住目标工位，与条件 UPDATE 做双重防护 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("select s from Station s where s.id = :id")
    Optional<Station> findByIdForUpdate(@Param("id") Long id);
}
