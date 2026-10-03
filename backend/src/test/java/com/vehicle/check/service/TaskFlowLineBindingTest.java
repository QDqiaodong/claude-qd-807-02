package com.vehicle.check.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.vehicle.check.common.BizException;
import com.vehicle.check.domain.Line;
import com.vehicle.check.domain.Station;
import com.vehicle.check.domain.Task;
import com.vehicle.check.domain.Vehicle;
import com.vehicle.check.dto.StationResultRequest;
import com.vehicle.check.repository.DriverRepository;
import com.vehicle.check.repository.LineRepository;
import com.vehicle.check.repository.ScheduleSlotRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.TaskRepository;
import com.vehicle.check.repository.TaskStationRecordRepository;
import com.vehicle.check.repository.VehicleRepository;

/**
 * 工位录入接口的任务-线路绑定校验单测（纯 Mockito）：
 * 缺陷复现——多线路下底部导航给的是第一条线节点，拿着 L01 的 stationId 给 L02 任务提交，
 * 后端必须以「工位不属于任务所在检测线」拒绝（且不得落任何检测记录/推进状态）。
 */
class TaskFlowLineBindingTest {

    private TaskRepository taskRepository;
    private TaskStationRecordRepository recordRepository;
    private VehicleRepository vehicleRepository;
    private LineRepository lineRepository;
    private StationRepository stationRepository;
    private DriverRepository driverRepository;
    private ScheduleSlotRepository slotRepository;
    private TaskNoGenerator taskNoGenerator;
    private StringRedisTemplate redisTemplate;
    private TaskFlowService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        recordRepository = mock(TaskStationRecordRepository.class);
        vehicleRepository = mock(VehicleRepository.class);
        lineRepository = mock(LineRepository.class);
        stationRepository = mock(StationRepository.class);
        driverRepository = mock(DriverRepository.class);
        slotRepository = mock(ScheduleSlotRepository.class);
        taskNoGenerator = mock(TaskNoGenerator.class);
        redisTemplate = mock(StringRedisTemplate.class);
        service = new TaskFlowService(taskRepository, recordRepository, vehicleRepository, lineRepository,
                stationRepository, driverRepository, slotRepository, taskNoGenerator, redisTemplate);
    }

    private Task task(long id, long lineId, long stationId, int seqNo, String status) {
        Task t = new Task();
        t.setId(id);
        t.setTaskNo("T-" + id);
        t.setVehicleId(100L);
        t.setLineId(lineId);
        t.setCurrentStationId(stationId);
        t.setCurrentSeqNo(seqNo);
        t.setStatus(status);
        return t;
    }

    private Station station(long id, long lineId, int seqNo, String type) {
        Station s = new Station();
        s.setId(id);
        s.setLineId(lineId);
        s.setSeqNo(seqNo);
        s.setStationType(type);
        s.setStationName("工位" + seqNo);
        s.setStatus("OCCUPIED");
        return s;
    }

    private Vehicle vehicle() {
        Vehicle v = new Vehicle();
        v.setId(100L);
        v.setPlateNo("京B·L2208挂");
        return v;
    }

    @Test
    void rejectsStationOfAnotherLineWithClearBizMessage() {
        // 任务在 L02（lineId=2），当前工位 id=8；底部导航错给了 L01（lineId=1）尾气工位 id=3
        Task l02Task = task(77L, 2L, 8L, 1, "CHECKING");
        Station l01Station = station(3L, 1L, 3, "EMISSION");

        when(taskRepository.findById(77L)).thenReturn(Optional.of(l02Task));
        when(vehicleRepository.findById(100L)).thenReturn(Optional.of(vehicle()));
        when(stationRepository.findById(3L)).thenReturn(Optional.of(l01Station));

        StationResultRequest req = new StationResultRequest();
        req.setStationId(3L);

        BizException ex = assertThrows(BizException.class,
                () -> service.submitStationResult(77L, req));
        assertEquals("工位「工位3」不属于任务所在检测线，不能录入检测值", ex.getMessage());

        // 拒绝必须发生在任何写入之前：不落记录、不推进、不释放/占用工位
        verify(recordRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(taskRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(stationRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void acceptsStationOfTheTasksOwnLineAtCurrentSeq() {
        // 同一 stationId=3，但任务确实属于 L01：放行进入判定/推进流程
        Task l01Task = task(78L, 1L, 3L, 3, "CHECKING");
        Station current = station(3L, 1L, 3, "EMISSION");
        Station next = station(4L, 1L, 4, "BRAKE");
        Line l01 = new Line();
        l01.setId(1L);
        l01.setLineNo("L01");

        when(taskRepository.findById(78L)).thenReturn(Optional.of(l01Task));
        when(vehicleRepository.findById(100L)).thenReturn(Optional.of(vehicle()));
        when(stationRepository.findById(3L)).thenReturn(Optional.of(current));
        when(recordRepository.findByTaskIdOrderBySeqNoAsc(78L)).thenReturn(List.of());
        when(stationRepository.findByLineIdOrderBySeqNoAsc(1L)).thenReturn(List.of(current, next));
        when(lineRepository.findById(1L)).thenReturn(Optional.of(l01));
        when(taskRepository.save(l01Task)).thenAnswer(inv -> inv.getArgument(0));

        StationResultRequest req = new StationResultRequest();
        req.setStationId(3L);
        req.setCoValue(new java.math.BigDecimal("0.12"));
        req.setHcValue(30);

        // 不抛异常即通过跨线闸门，返回推进后的任务视图
        var vo = service.submitStationResult(78L, req);
        assertEquals(78L, vo.getId());
        assertEquals(4, vo.getCurrentSeqNo());
        verify(recordRepository).save(org.mockito.ArgumentMatchers.any());
        verify(stationRepository).save(next);
        verify(stationRepository, never()).save(current); // 当前工位先置 IDLE 仅在有下一工位时
    }

    @Test
    void rejectsWhenStationDoesNotExist() {
        when(taskRepository.findById(77L)).thenReturn(Optional.of(task(77L, 2L, 8L, 1, "CHECKING")));
        when(vehicleRepository.findById(100L)).thenReturn(Optional.of(vehicle()));
        when(stationRepository.findById(anyLong())).thenReturn(Optional.empty());

        StationResultRequest req = new StationResultRequest();
        req.setStationId(999L);
        assertThrows(BizException.class, () -> service.submitStationResult(77L, req));
        verify(recordRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
