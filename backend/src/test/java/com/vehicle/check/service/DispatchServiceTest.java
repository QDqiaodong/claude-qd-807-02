package com.vehicle.check.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.vehicle.check.common.BizException;
import com.vehicle.check.common.ScheduleConflictException;
import com.vehicle.check.domain.Driver;
import com.vehicle.check.domain.Line;
import com.vehicle.check.domain.ScheduleSlot;
import com.vehicle.check.domain.Station;
import com.vehicle.check.domain.Task;
import com.vehicle.check.domain.TaskStationRecord;
import com.vehicle.check.domain.Vehicle;
import com.vehicle.check.dto.DispatchEligibilityVO;
import com.vehicle.check.dto.DispatchRequest;
import com.vehicle.check.dto.DispatchResultVO;
import com.vehicle.check.dto.ReassignRequest;
import com.vehicle.check.repository.DriverRepository;
import com.vehicle.check.repository.LineRepository;
import com.vehicle.check.repository.ScheduleSlotRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.TaskRepository;
import com.vehicle.check.repository.TaskStationRecordRepository;
import com.vehicle.check.repository.VehicleRepository;

/**
 * 派发/改派业务规则单测（纯 Mockito，不依赖数据库）：
 * 五条拒绝规则、小时段合法性、改派门禁、并发冲突、台次口径。
 */
class DispatchServiceTest {

    private TaskRepository taskRepository;
    private ScheduleSlotRepository slotRepository;
    private VehicleRepository vehicleRepository;
    private LineRepository lineRepository;
    private StationRepository stationRepository;
    private DriverRepository driverRepository;
    private TaskStationRecordRepository recordRepository;
    private TaskFlowService taskFlowService;
    private DispatchService service;

    private static final LocalDate DAY = LocalDate.of(2026, 10, 3);

    @BeforeEach
    void setUp() {
        taskRepository = Mockito.mock(TaskRepository.class);
        slotRepository = Mockito.mock(ScheduleSlotRepository.class);
        vehicleRepository = Mockito.mock(VehicleRepository.class);
        lineRepository = Mockito.mock(LineRepository.class);
        stationRepository = Mockito.mock(StationRepository.class);
        driverRepository = Mockito.mock(DriverRepository.class);
        recordRepository = Mockito.mock(TaskStationRecordRepository.class);
        taskFlowService = Mockito.mock(TaskFlowService.class);
        service = new DispatchService(taskRepository, slotRepository, vehicleRepository, lineRepository,
                stationRepository, driverRepository, recordRepository, taskFlowService,
                org.mockito.Mockito.mock(com.vehicle.check.service.TaskNoGenerator.class));

        when(slotRepository.findByCheckDateAndActiveTrue(DAY)).thenReturn(List.of());
        when(driverRepository.findAllByOrderByIdAsc()).thenReturn(List.of());
    }

    private DispatchRequest request(Long vehicleId, Long lineId, Long driverId, Integer hour) {
        DispatchRequest req = new DispatchRequest();
        req.setVehicleId(vehicleId);
        req.setLineId(lineId);
        req.setDriverId(driverId);
        req.setScheduleHour(hour);
        req.setCheckDate(DAY.toString());
        return req;
    }

    @Test
    void rejectsStoppedLineNoStationsDuplicateVehicleOffDutyAndOffShift() {
        // 车辆当天已建档
        Vehicle vehicle = new Vehicle();
        vehicle.setId(1L);
        vehicle.setPlateNo("京A·00001");
        when(vehicleRepository.findById(1L)).thenReturn(Optional.of(vehicle));
        when(taskRepository.existsByVehicleIdAndCheckDate(1L, DAY)).thenReturn(true);
        // 停用检测线
        Line stopped = new Line();
        stopped.setId(5L);
        stopped.setLineNo("L05");
        stopped.setLineName("危化品车专线");
        stopped.setStatus("STOPPED");
        when(lineRepository.findById(5L)).thenReturn(Optional.of(stopped));
        when(stationRepository.findByLineIdOrderBySeqNoAsc(5L)).thenReturn(List.of());
        // 不在岗引车员
        Driver off = new Driver();
        off.setId(6L);
        off.setDriverName("周引车");
        off.setStatus("OFF_DUTY");
        off.setShiftHours("8,9,10,11");
        when(driverRepository.findById(6L)).thenReturn(Optional.of(off));

        DispatchEligibilityVO vo = service.checkDispatch(request(1L, 5L, 6L, 17));
        assertFalse(vo.getEligible());
        assertTrue(vo.getBlockReasons().stream().anyMatch(r -> r.contains("已建档送检")));
        assertTrue(vo.getBlockReasons().stream().anyMatch(r -> r.contains("停用状态")));
        assertTrue(vo.getBlockReasons().stream().anyMatch(r -> r.contains("尚未配置工位")));
        assertTrue(vo.getBlockReasons().stream().anyMatch(r -> r.contains("不在岗")));
        assertTrue(vo.getBlockReasons().stream().anyMatch(r -> r.contains("不包含 17:00")));
    }

    @Test
    void rejectsHourOutsideEightToEighteen() {
        Driver driver = new Driver();
        driver.setId(1L);
        driver.setDriverName("张引车");
        driver.setStatus("ON_DUTY");
        driver.setShiftHours("8,9,10,11,12");
        when(driverRepository.findById(1L)).thenReturn(Optional.of(driver));

        DispatchEligibilityVO vo = service.checkDispatch(request(99L, 99L, 1L, 18));
        assertTrue(vo.getBlockReasons().stream().anyMatch(r -> r.contains("8:00-18:00")));
    }

    @Test
    void rejectsOccupiedStartStation() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(2L);
        when(vehicleRepository.findById(2L)).thenReturn(Optional.of(vehicle));
        Line line = new Line();
        line.setId(1L);
        line.setLineNo("L01");
        line.setStatus("RUNNING");
        when(lineRepository.findById(1L)).thenReturn(Optional.of(line));
        Station first = new Station();
        first.setId(10L);
        first.setStationName("登录");
        first.setStatus("OCCUPIED");
        when(stationRepository.findByLineIdOrderBySeqNoAsc(1L)).thenReturn(List.of(first));
        Driver driver = new Driver();
        driver.setId(1L);
        driver.setDriverName("张引车");
        driver.setStatus("ON_DUTY");
        driver.setShiftHours("8,9");
        when(driverRepository.findById(1L)).thenReturn(Optional.of(driver));

        DispatchEligibilityVO vo = service.checkDispatch(request(2L, 1L, 1L, 8));
        assertTrue(vo.getBlockReasons().stream().anyMatch(r -> r.contains("起点工位") && r.contains("占用")));
    }

    @Test
    void dispatchRollsBackWhenStartStationClaimMisses() {
        Vehicle vehicle = new Vehicle();
        vehicle.setId(3L);
        when(vehicleRepository.findById(3L)).thenReturn(Optional.of(vehicle));
        Line line = new Line();
        line.setId(4L);
        line.setLineNo("L04");
        line.setStatus("RUNNING");
        when(lineRepository.findById(4L)).thenReturn(Optional.of(line));
        Station first = new Station();
        first.setId(15L);
        first.setStationName("登录");
        first.setStatus("IDLE");
        when(stationRepository.findByLineIdOrderBySeqNoAsc(4L)).thenReturn(List.of(first));
        Driver driver = new Driver();
        driver.setId(1L);
        driver.setDriverName("张引车");
        driver.setStatus("ON_DUTY");
        driver.setShiftHours("8,9");
        when(driverRepository.findById(1L)).thenReturn(Optional.of(driver));
        when(stationRepository.claimStation(15L)).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> service.dispatch(request(3L, 4L, 1L, 8)));
        assertTrue(ex.getMessage().contains("确认瞬间"));
        // 占位失败：任务与格子都不能落库（半派发记录的防线）
        verify(slotRepository, never()).save(any());
    }

    @Test
    void reassignRejectsStartedTask() {
        Task task = checkingTask(77L, 1L, 8);
        when(taskRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(task));
        when(taskRepository.findById(77L)).thenReturn(Optional.of(task));
        when(recordRepository.findByTaskIdOrderBySeqNoAsc(77L))
                .thenReturn(List.of(new TaskStationRecord()));
        Driver target = driver(2L, "李引车", "10,11");
        when(driverRepository.findById(2L)).thenReturn(Optional.of(target));
        when(slotRepository.findFirstByTaskIdAndActiveTrue(77L)).thenReturn(Optional.of(new ScheduleSlot()));

        ReassignRequest req = new ReassignRequest();
        req.setDriverId(2L);
        req.setScheduleHour(10);
        ScheduleConflictException ex = assertThrows(ScheduleConflictException.class,
                () -> service.reassign(77L, req));
        assertTrue(ex.getMessage().contains("已开始检测"));
        assertEquals(77L, ex.getTaskId());
        verify(slotRepository, never()).releaseByTask(anyLong());
        verify(slotRepository, never()).save(any());
    }

    @Test
    void reassignRejectsFinishedTaskAndKeepsAssignment() {
        Task task = checkingTask(78L, 1L, 8);
        task.setStatus("PASS");
        when(taskRepository.findByIdForUpdate(78L)).thenReturn(Optional.of(task));
        when(taskRepository.findById(78L)).thenReturn(Optional.of(task));
        when(recordRepository.findByTaskIdOrderBySeqNoAsc(78L)).thenReturn(List.of());
        when(slotRepository.findFirstByTaskIdAndActiveTrue(78L)).thenReturn(Optional.empty());
        Driver target = driver(2L, "李引车", "10,11");
        when(driverRepository.findById(2L)).thenReturn(Optional.of(target));

        ReassignRequest req = new ReassignRequest();
        req.setDriverId(2L);
        req.setScheduleHour(10);
        ScheduleConflictException ex = assertThrows(ScheduleConflictException.class,
                () -> service.reassign(78L, req));
        assertTrue(ex.getMessage().contains("已结束"));
        verify(taskRepository, never()).save(any(Task.class));
    }

    @Test
    void reassignMovesWaitingTaskReleasesOldCellAndOccupiesNew() {
        Task task = checkingTask(77L, 1L, 8);
        when(taskRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(task));
        when(taskRepository.findById(77L)).thenReturn(Optional.of(task));
        when(recordRepository.findByTaskIdOrderBySeqNoAsc(77L)).thenReturn(List.of());
        ScheduleSlot old = new ScheduleSlot();
        old.setTaskId(77L);
        old.setDriverId(1L);
        when(slotRepository.findFirstByTaskIdAndActiveTrue(77L)).thenReturn(Optional.of(old));
        Driver target = driver(2L, "李引车", "10,11");
        when(driverRepository.findById(2L)).thenReturn(Optional.of(target));
        Vehicle vehicle = new Vehicle();
        vehicle.setId(9L);
        when(vehicleRepository.findById(9L)).thenReturn(Optional.of(vehicle));
        Line line = new Line();
        line.setId(4L);
        line.setLineNo("L04");
        when(lineRepository.findById(4L)).thenReturn(Optional.of(line));
        Station station = new Station();
        station.setId(15L);
        station.setStationName("登录");
        when(stationRepository.findById(15L)).thenReturn(Optional.of(station));
        when(slotRepository.releaseByTask(77L)).thenReturn(1);
        when(taskRepository.save(any(Task.class))).thenAnswer(inv -> inv.getArgument(0));
        when(taskFlowService.getTask(77L)).thenReturn(new com.vehicle.check.dto.TaskVO());

        ReassignRequest req = new ReassignRequest();
        req.setDriverId(2L);
        req.setScheduleHour(10);
        DispatchResultVO result = service.reassign(77L, req);

        assertEquals(2L, task.getDriverId());
        assertEquals(10, task.getScheduleHour());
        verify(slotRepository).releaseByTask(77L);
        verify(slotRepository).save(argThatSlot(2L, 10));
        assertFalse(result.isCurrentOwnership());
    }

    @Test
    void duplicateReassignToSameTargetIsIdempotent() {
        Task task = checkingTask(77L, 1L, 8);
        when(taskRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(task));
        when(recordRepository.findByTaskIdOrderBySeqNoAsc(77L)).thenReturn(List.of());
        when(slotRepository.findFirstByTaskIdAndActiveTrue(77L)).thenReturn(Optional.of(new ScheduleSlot()));
        when(driverRepository.findById(1L)).thenReturn(Optional.of(driver(1L, "张引车", "8,9")));
        when(taskFlowService.getTask(77L)).thenReturn(new com.vehicle.check.dto.TaskVO());

        ReassignRequest req = new ReassignRequest();
        req.setDriverId(1L);
        req.setScheduleHour(8);
        DispatchResultVO result = service.reassign(77L, req);

        assertTrue(result.isCurrentOwnership());
        verify(slotRepository, never()).releaseByTask(anyLong());
        verify(slotRepository, never()).save(any());
    }

    @Test
    void shiftHourCheck() {
        Driver driver = driver(1L, "张引车", "8,9,10,11,12");
        assertTrue(DispatchService.isShiftHour(driver, 8));
        assertTrue(DispatchService.isShiftHour(driver, 12));
        assertFalse(DispatchService.isShiftHour(driver, 13));
        assertFalse(DispatchService.isShiftHour(driver, 17));
    }

    private ScheduleSlot argThatSlot(Long driverId, int hour) {
        return Mockito.argThat(s -> driverId.equals(s.getDriverId()) && s.getScheduleHour() == hour
                && DAY.equals(s.getCheckDate()) && Boolean.TRUE.equals(s.getActive()));
    }

    private Task checkingTask(Long id, Long driverId, int hour) {
        Task task = new Task();
        task.setId(id);
        task.setTaskNo("TASK-X");
        task.setVehicleId(9L);
        task.setLineId(4L);
        task.setCurrentStationId(15L);
        task.setCurrentSeqNo(1);
        task.setStatus("CHECKING");
        task.setDriverId(driverId);
        task.setScheduleHour(hour);
        task.setCheckDate(DAY);
        return task;
    }

    private Driver driver(Long id, String name, String shift) {
        Driver driver = new Driver();
        driver.setId(id);
        driver.setDriverName(name);
        driver.setStatus("ON_DUTY");
        driver.setShiftHours(shift);
        return driver;
    }
}
