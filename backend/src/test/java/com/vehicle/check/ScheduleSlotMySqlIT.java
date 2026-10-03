package com.vehicle.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

/**
 * MySQL 8 真实约束集成测试（有 Docker 时运行，无 Docker 自动跳过）：
 * 验证 vc_schedule_slot 两道生成列唯一索引——
 * 同引车员同日同小时只能有一个未结束任务；同一任务只能占一个格；释放后名额立即归还；
 * 改派「旧格释放 + 新格占用」必须同一事务提交（模拟回滚则两格都不动）。
 */
class ScheduleSlotMySqlIT {

    static MySQLContainer<?> mysql;

    @BeforeAll
    static void dockerAvailable() {
        boolean docker;
        try {
            docker = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable e) {
            docker = false;
        }
        assumeTrue(docker, "环境无 Docker，跳过 MySQL 集成测试");
        mysql = new MySQLContainer<>("mysql:8.0")
                .withDatabaseName("vehicle_check")
                .withUsername("test").withPassword("test")
                .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci");
        mysql.start();
    }

    @BeforeEach
    void initSchema() throws Exception {
        try (Connection conn = conn(); Statement st = conn.createStatement()) {
            st.executeUpdate("DROP TABLE IF EXISTS vc_schedule_slot");
            st.executeUpdate("""
                    CREATE TABLE vc_schedule_slot (
                      id BIGINT NOT NULL AUTO_INCREMENT,
                      task_id BIGINT NOT NULL,
                      driver_id BIGINT NOT NULL,
                      check_date DATE NOT NULL,
                      schedule_hour INT NOT NULL,
                      active TINYINT(1) NOT NULL DEFAULT 1,
                      active_task_key VARCHAR(40) GENERATED ALWAYS AS
                          (CASE WHEN active = 1 THEN CONCAT(task_id) ELSE NULL END) VIRTUAL,
                      active_slot_key VARCHAR(64) GENERATED ALWAYS AS
                          (CASE WHEN active = 1 THEN CONCAT(driver_id, '-', check_date, '-', schedule_hour)
                                ELSE NULL END) VIRTUAL,
                      create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                      PRIMARY KEY (id),
                      UNIQUE KEY uk_task_active (active_task_key),
                      UNIQUE KEY uk_slot_active (active_slot_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4""");
        }
    }

    @Test
    void oneUnfinishedTaskPerDriverHourAndOneSlotPerTask() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 3);
        insertSlot(1L, 10L, 1L, day, 9, true);
        // 同引车员同时段不同任务：第二条必须被 uk_slot_active 拒绝
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> insertSlot(2L, 11L, 1L, day, 9, true));
        // 不同小时可以
        insertSlot(3L, 11L, 1L, day, 10, true);
        // 同任务占第二格（换引车员）：被 uk_task_active 拒绝
        assertThrows(SQLIntegrityConstraintViolationException.class,
                () -> insertSlot(4L, 10L, 2L, day, 9, true));
        // 已结束行（active=0）不占名额：同格可留多条历史痕迹
        insertSlot(5L, 99L, 1L, day, 9, false);
        insertSlot(6L, 98L, 1L, day, 9, false);

        // 释放任务 10 的格子后，同格立即可以给新任务（任务结束/原格释放场景）
        assertEquals(1, releaseSlot(10L));
        insertSlot(7L, 20L, 1L, day, 9, true);

        // 同一任务释放后允许重新占格（改派到新格），且库里只有它一条 active
        assertEquals(1, releaseSlot(20L));
        insertSlot(8L, 20L, 2L, day, 14, true);
        List<long[]> active = activeSlots();
        assertEquals(3, active.size());                       // 11@h10、20@d2h14，+ 无其它
        assertEquals(1, active.stream().filter(r -> r[0] == 20L).count());
        assertTrue(active.stream().anyMatch(r -> r[0] == 11L && r[1] == 1L && r[2] == 10));
        assertTrue(active.stream().anyMatch(r -> r[0] == 20L && r[1] == 2L && r[2] == 14));
        assertTrue(active.stream().noneMatch(r -> r[0] == 20L && r[1] == 1L));
    }

    @Test
    void reassignCommitsReleaseAndNewSlotAtomically() throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 3);
        insertSlot(1L, 50L, 1L, day, 8, true);

        // 模拟改派事务中途失败：回滚后原格仍在、新格不存在，绝不出现一任务占两格
        try (Connection conn = conn()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE vc_schedule_slot SET active=0 WHERE task_id=? AND active=1")) {
                ps.setLong(1, 50L);
                assertEquals(1, ps.executeUpdate());
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO vc_schedule_slot(task_id,driver_id,check_date,schedule_hour,active) "
                            + "VALUES(?,?,?,?,1)")) {
                ps.setLong(1, 50L);
                ps.setLong(2, 2L);
                ps.setDate(3, java.sql.Date.valueOf(day));
                ps.setInt(4, 13);
                ps.executeUpdate();
            }
            conn.rollback();
        }
        List<long[]> afterRollback = activeSlots();
        assertEquals(1, afterRollback.size());
        assertEquals(1L, afterRollback.get(0)[1]); // 原格未被释放

        // 正常提交：旧格释放、新格落定，active 总数仍为 1
        try (Connection conn = conn()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE vc_schedule_slot SET active=0 WHERE task_id=? AND active=1")) {
                ps.setLong(1, 50L);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO vc_schedule_slot(task_id,driver_id,check_date,schedule_hour,active) "
                            + "VALUES(?,?,?,?,1)")) {
                ps.setLong(1, 50L);
                ps.setLong(2, 3L);
                ps.setDate(3, java.sql.Date.valueOf(day));
                ps.setInt(4, 15);
                ps.executeUpdate();
            }
            conn.commit();
        }
        List<long[]> afterCommit = activeSlots();
        assertEquals(1, afterCommit.size());
        assertEquals(3L, afterCommit.get(0)[1]);
        assertEquals(15, afterCommit.get(0)[2]);
    }

    private Connection conn() throws Exception {
        return DriverManager.getConnection(mysql.getJdbcUrl(), "test", "test");
    }

    private void insertSlot(long id, long taskId, long driverId, LocalDate day, int hour, boolean active)
            throws Exception {
        try (Connection conn = conn();
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO vc_schedule_slot(id,task_id,driver_id,check_date,schedule_hour,active) "
                                + "VALUES(?,?,?,?,?,?)")) {
            ps.setLong(1, id);
            ps.setLong(2, taskId);
            ps.setLong(3, driverId);
            ps.setDate(4, java.sql.Date.valueOf(day));
            ps.setInt(5, hour);
            ps.setBoolean(6, active);
            ps.executeUpdate();
        }
    }

    private int releaseSlot(long taskId) throws Exception {
        try (Connection conn = conn();
                PreparedStatement ps = conn.prepareStatement(
                        "UPDATE vc_schedule_slot SET active=0 WHERE task_id=? AND active=1")) {
            ps.setLong(1, taskId);
            return ps.executeUpdate();
        }
    }

    /** [taskId, driverId, hour] 的全部 active 行 */
    private List<long[]> activeSlots() throws Exception {
        List<long[]> rows = new ArrayList<>();
        try (Connection conn = conn();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT task_id, driver_id, schedule_hour FROM vc_schedule_slot WHERE active=1")) {
            while (rs.next()) {
                rows.add(new long[] { rs.getLong(1), rs.getLong(2), rs.getInt(3) });
            }
        }
        return rows;
    }
}
