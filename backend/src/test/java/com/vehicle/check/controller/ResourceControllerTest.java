package com.vehicle.check.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.vehicle.check.common.ScheduleConflictException;
import com.vehicle.check.domain.Driver;
import com.vehicle.check.dto.DispatchEligibilityVO;
import com.vehicle.check.dto.DispatchResultVO;
import com.vehicle.check.dto.DriverStatDTO;
import com.vehicle.check.dto.ReassignRequest;
import com.vehicle.check.dto.ScheduleCellVO;
import com.vehicle.check.dto.ScheduleRowVO;
import com.vehicle.check.dto.TaskVO;
import com.vehicle.check.service.DispatchService;
import com.vehicle.check.service.ResourceService;

/**
 * 排班工作流接口测试（MockMvc 切片）：
 * 派发预检/落库、改派、并发冲突 409 携带当前归属、格子表恢复、台次统计。
 */
class ResourceControllerTest {

    private final DispatchService dispatchService = org.mockito.Mockito.mock(DispatchService.class);
    private final ResourceService resourceService = org.mockito.Mockito.mock(ResourceService.class);
    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ResourceController(resourceService, dispatchService))
            .setControllerAdvice(new com.vehicle.check.common.GlobalExceptionHandler())
            .build();

    @Test
    void dispatchCheckReturnsBlockReasons() throws Exception {
        DispatchEligibilityVO vo = new DispatchEligibilityVO();
        vo.setEligible(false);
        vo.setBlockReasons(List.of("检测线「L05」当前为停用状态，不能派发",
                "引车员「周引车」当日不在岗，不能派单"));
        when(dispatchService.checkDispatch(any())).thenReturn(vo);

        mockMvc.perform(post("/api/dispatch/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":1,"lineId":5,"driverId":6,"scheduleHour":9,"checkDate":"2026-10-03"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eligible").value(false))
                .andExpect(jsonPath("$.data.blockReasons[0]").value(org.hamcrest.Matchers.containsString("停用")))
                .andExpect(jsonPath("$.data.blockReasons[1]").value(org.hamcrest.Matchers.containsString("不在岗")));
    }

    @Test
    void dispatchBindsTaskCellAndStats() throws Exception {
        when(dispatchService.dispatch(any())).thenReturn(sampleResult(false));

        mockMvc.perform(post("/api/dispatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":11,"lineId":4,"driverId":1,"scheduleHour":8,"checkDate":"2026-10-03"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("派发成功"))
                .andExpect(jsonPath("$.data.cell.taskNo").value("TASK-20261003-100"))
                .andExpect(jsonPath("$.data.cell.cellState").value("WAITING"))
                .andExpect(jsonPath("$.data.task.reassignAllowed").value(true))
                .andExpect(jsonPath("$.data.driverStats[0].taskCount").value(1))
                .andExpect(jsonPath("$.data.currentOwnership").value(false));
    }

    @Test
    void dispatchValidationErrorWhenDriverMissing() throws Exception {
        mockMvc.perform(post("/api/dispatch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"vehicleId":11,"lineId":4,"scheduleHour":8}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("引车员")));
    }

    @Test
    void concurrentReassignReturns409WithCurrentOwnership() throws Exception {
        when(dispatchService.reassign(eq(77L), any(ReassignRequest.class)))
                .thenThrow(new ScheduleConflictException("任务刚被另一笔改派落定，本次操作未生效", 77L));
        when(dispatchService.currentOwnership(77L)).thenReturn(sampleResult(true));

        mockMvc.perform(post("/api/tasks/77/reassign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"driverId":2,"scheduleHour":10}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("未生效")))
                .andExpect(jsonPath("$.data.currentOwnership").value(true))
                .andExpect(jsonPath("$.data.cell.taskId").value(77))
                .andExpect(jsonPath("$.data.cell.taskNo").value("TASK-20261003-100"));
    }

    @Test
    void reassignSuccessReturnsCellAndStats() throws Exception {
        when(dispatchService.reassign(eq(77L), any(ReassignRequest.class)))
                .thenReturn(sampleResult(false));

        mockMvc.perform(post("/api/tasks/77/reassign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"driverId":3,"scheduleHour":13}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("改派成功"))
                .andExpect(jsonPath("$.data.cell.taskNo").value("TASK-20261003-100"));
    }

    @Test
    void scheduleRestoresServerCellStates() throws Exception {
        ScheduleCellVO waiting = new ScheduleCellVO();
        waiting.setHour(8);
        waiting.setHourLabel("8:00");
        waiting.setInShift(true);
        waiting.setOnDuty(true);
        waiting.setTaskId(77L);
        waiting.setTaskNo("TASK-20261003-100");
        waiting.setPlateNo("京A·R5502");
        waiting.setStatus("CHECKING");
        waiting.setStatusName("在检");
        waiting.setCellState("WAITING");

        ScheduleCellVO off = new ScheduleCellVO();
        off.setHour(17);
        off.setHourLabel("17:00");
        off.setInShift(false);
        off.setOnDuty(false);
        off.setCellState("OFF_SHIFT");
        off.setBlockReasons(List.of("17:00 不在该引车员当日排班小时段 8:00-13:00 内"));

        ScheduleRowVO row = new ScheduleRowVO();
        row.setDriverId(1L);
        row.setDriverName("张引车");
        row.setCells(new java.util.ArrayList<>(List.of(waiting, off)));
        when(resourceService.schedule(LocalDate.parse("2026-10-03"))).thenReturn(List.of(row));

        mockMvc.perform(get("/api/drivers/schedule").param("date", "2026-10-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].driverName").value("张引车"))
                .andExpect(jsonPath("$.data[0].cells[0].cellState").value("WAITING"))
                .andExpect(jsonPath("$.data[0].cells[0].plateNo").value("京A·R5502"))
                .andExpect(jsonPath("$.data[0].cells[1].cellState").value("OFF_SHIFT"))
                .andExpect(jsonPath("$.data[0].cells[1].blockReasons[0]")
                        .value(org.hamcrest.Matchers.containsString("不在该引车员")));
    }

    @Test
    void driverStatsServedFromDispatchService() throws Exception {
        when(dispatchService.driverStats(LocalDate.parse("2026-10-03")))
                .thenReturn(List.of(new DriverStatDTO(1L, 1L)));

        mockMvc.perform(get("/api/drivers/stats").param("date", "2026-10-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].driverId").value(1))
                .andExpect(jsonPath("$.data[0].taskCount").value(1));
    }

    private DispatchResultVO sampleResult(boolean currentOwnership) {
        ScheduleCellVO cell = new ScheduleCellVO();
        cell.setHour(8);
        cell.setHourLabel("8:00");
        cell.setTaskId(77L);
        cell.setTaskNo("TASK-20261003-100");
        cell.setPlateNo("京A·R5502");
        cell.setStatus("CHECKING");
        cell.setStatusName("在检");
        cell.setCellState("WAITING");

        TaskVO task = new TaskVO();
        task.setId(77L);
        task.setTaskNo("TASK-20261003-100");
        task.setStatus("CHECKING");
        task.setStarted(false);
        task.setReassignAllowed(true);

        DriverStatDTO stat = new DriverStatDTO(1L, 1L);
        stat.setDriverName("张引车");

        DispatchResultVO result = new DispatchResultVO();
        result.setCell(cell);
        result.setTask(task);
        result.setDriverStats(List.of(stat));
        result.setCurrentOwnership(currentOwnership);
        return result;
    }
}
