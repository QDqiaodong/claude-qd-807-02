package com.vehicle.check.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vehicle.check.common.BizException;
import com.vehicle.check.common.ScheduleConflictException;
import com.vehicle.check.common.StationDict;
import com.vehicle.check.domain.Driver;
import com.vehicle.check.domain.Line;
import com.vehicle.check.domain.ScheduleSlot;
import com.vehicle.check.domain.Station;
import com.vehicle.check.domain.Task;
import com.vehicle.check.domain.Vehicle;
import com.vehicle.check.dto.DispatchEligibilityVO;
import com.vehicle.check.dto.DispatchRequest;
import com.vehicle.check.dto.DispatchResultVO;
import com.vehicle.check.dto.DriverStatDTO;
import com.vehicle.check.dto.ReassignRequest;
import com.vehicle.check.dto.ScheduleCellVO;
import com.vehicle.check.dto.TaskVO;
import com.vehicle.check.repository.DriverRepository;
import com.vehicle.check.repository.LineRepository;
import com.vehicle.check.repository.ScheduleSlotRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.TaskRepository;
import com.vehicle.check.repository.TaskStationRecordRepository;
import com.vehicle.check.repository.VehicleRepository;

/**
 * 引车员排班派发工作流：
 * 待检车辆进入运行中的检测线时，把【任务 + 小时格 + 车辆队列（起点工位占位）】在同一事务内绑定一次完成；
 * 尚未开始检测的任务可改派，行锁 + 小时格双唯一索引保证并发下只允许一次成功、同一任务绝不占两格。
 */
@Service
public class DispatchService {

    private final TaskRepository taskRepository;
    private final ScheduleSlotRepository slotRepository;
    private final VehicleRepository vehicleRepository;
    private final LineRepository lineRepository;
    private final StationRepository stationRepository;
    private final DriverRepository driverRepository;
    private final TaskStationRecordRepository recordRepository;
    private final TaskFlowService taskFlowService;
    private final TaskNoGenerator taskNoGenerator;

    public DispatchService(TaskRepository taskRepository, ScheduleSlotRepository slotRepository,
                           VehicleRepository vehicleRepository, LineRepository lineRepository,
                           StationRepository stationRepository, DriverRepository driverRepository,
                           TaskStationRecordRepository recordRepository, TaskFlowService taskFlowService,
                           TaskNoGenerator taskNoGenerator) {
        this.taskRepository = taskRepository;
        this.slotRepository = slotRepository;
        this.vehicleRepository = vehicleRepository;
        this.lineRepository = lineRepository;
        this.stationRepository = stationRepository;
        this.driverRepository = driverRepository;
        this.recordRepository = recordRepository;
        this.taskFlowService = taskFlowService;
        this.taskNoGenerator = taskNoGenerator;
    }

    // ---------------- 资格预检（不落库，调度员先看阻断原因） ----------------

    /**
     * 派发资格预检：逐条给出阻断原因，全部通过才可落库。
     */
    public DispatchEligibilityVO checkDispatch(DispatchRequest request) {
        LocalDate day = parseDate(request.getCheckDate());
        DispatchEligibilityVO vo = new DispatchEligibilityVO();
        vo.setVehicleId(request.getVehicleId());
        vo.setLineId(request.getLineId());
        vo.setDriverId(request.getDriverId());
        vo.setScheduleHour(request.getScheduleHour());
        vo.setCheckDate(day == null ? null : day.toString());

        Vehicle vehicle = request.getVehicleId() == null ? null
                : vehicleRepository.findById(request.getVehicleId()).orElse(null);
        Line line = request.getLineId() == null ? null
                : lineRepository.findById(request.getLineId()).orElse(null);
        Driver driver = request.getDriverId() == null ? null
                : driverRepository.findById(request.getDriverId()).orElse(null);
        if (vehicle != null) {
            vo.setPlateNo(vehicle.getPlateNo());
        }
        if (line != null) {
            vo.setLineNo(line.getLineNo());
        }
        if (driver != null) {
            vo.setDriverName(driver.getDriverName());
        }

        List<String> reasons = new ArrayList<>();
        if (vehicle == null) {
            reasons.add("待检车辆不存在，请重新选择");
        } else if (day != null
                && taskRepository.existsByVehicleIdAndCheckDate(vehicle.getId(), day)) {
            reasons.add("车辆「" + vehicle.getPlateNo() + "」当日（" + day + "）已建档送检，不能重复派发");
        }
        if (line == null) {
            reasons.add("检测线不存在，请重新选择");
        } else {
            if (!"RUNNING".equals(line.getStatus())) {
                reasons.add("检测线「" + line.getLineNo() + "（" + line.getLineName() + "）」当前为停用状态，不能派发");
            }
            List<Station> stations = stationRepository.findByLineIdOrderBySeqNoAsc(line.getId());
            if (stations.isEmpty()) {
                reasons.add("检测线「" + line.getLineNo() + "」尚未配置工位，不能派发");
            } else if (!"IDLE".equals(stations.get(0).getStatus())) {
                reasons.add("检测线「" + line.getLineNo() + "」起点工位（"
                        + stations.get(0).getStationName() + "）当前为"
                        + StationDict.stationStatusName(stations.get(0).getStatus()) + "，暂无空位接入车辆");
            }
        }
        checkDriverGate(driver, request.getScheduleHour(), reasons);
        if (driver != null && request.getScheduleHour() != null && day != null
                && isShiftHour(driver, request.getScheduleHour())) {
            slotRepository.findByCheckDateAndActiveTrue(day).stream()
                    .filter(s -> driver.getId().equals(s.getDriverId()))
                    .filter(s -> s.getScheduleHour().equals(request.getScheduleHour()))
                    .findFirst()
                    .ifPresent(s -> reasons.add("引车员「" + driver.getDriverName() + "」"
                            + request.getScheduleHour() + ":00 时段已有未结束任务（任务号 "
                            + taskRepository.findById(s.getTaskId()).map(Task::getTaskNo).orElse("")
                            + "），同一小时不能再派"));
        }

        vo.setBlockReasons(reasons);
        vo.setEligible(reasons.isEmpty());
        return vo;
    }

    // ---------------- 派发（单事务：任务 + 小时格 + 队列起点一次绑定） ----------------

    /**
     * 派发。任务、小时格、起点工位占位在同一事务内提交，
     * 任何一步失败整体回滚——不会出现「有任务无格子」或「占了格没进队列」的半派发记录。
     */
    @Transactional
    public DispatchResultVO dispatch(DispatchRequest request) {
        LocalDate day = parseDate(request.getCheckDate());
        // 落库瞬间再复核一次：预检只是页面提示，资格以提交这一刻的数据为准
        DispatchEligibilityVO gate = checkDispatch(request);
        if (Boolean.FALSE.equals(gate.getEligible())) {
            throw new BizException(String.join("；", gate.getBlockReasons()));
        }

        Vehicle vehicle = vehicleRepository.findById(request.getVehicleId()).orElseThrow();
        Line line = lineRepository.findById(request.getLineId()).orElseThrow();
        Driver driver = driverRepository.findById(request.getDriverId()).orElseThrow();
        List<Station> stations = stationRepository.findByLineIdOrderBySeqNoAsc(line.getId());
        Station first = stations.get(0);

        Task task = new Task();
        task.setTaskNo(taskNoGenerator.nextTaskNo(day));
        task.setVehicleId(vehicle.getId());
        task.setLineId(line.getId());
        task.setCurrentStationId(first.getId());
        task.setCurrentSeqNo(first.getSeqNo());
        task.setStatus("CHECKING");
        task.setDriverId(driver.getId());
        task.setScheduleHour(request.getScheduleHour());
        task.setCheckDate(day);
        task.setRetestTimes(0);
        task.setCreateTime(LocalDateTime.now());

        // 原子占位起点工位：确认瞬间被别的车占用/停用/转故障时受影响行数为 0，整笔回滚
        int claimed = stationRepository.claimStation(first.getId());
        if (claimed == 0) {
            Station fresh = stationRepository.findById(first.getId()).orElse(first);
            throw new BizException("检测线「" + line.getLineNo() + "」起点工位（"
                    + fresh.getStationName() + "）在确认瞬间已变为"
                    + StationDict.stationStatusName(fresh.getStatus()) + "，本次派发未生效，请刷新后重试");
        }

        Task saved = taskRepository.save(task);
        ScheduleSlot slot = new ScheduleSlot();
        slot.setTaskId(saved.getId());
        slot.setDriverId(driver.getId());
        slot.setCheckDate(day);
        slot.setScheduleHour(request.getScheduleHour());
        slot.setActive(true);
        try {
            slotRepository.save(slot);
            // flush 让生成列唯一索引在本事务内立即生效：同格并发派发的后来者在此失败回滚（含起点占位）
            slotRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 本事务即将整体回滚（任务与起点占位都不落库），不带任务归属，前端按提示刷新格子即可
            throw new ScheduleConflictException("引车员「" + driver.getDriverName() + "」"
                    + request.getScheduleHour() + ":00 时段刚被另一笔派发占用，本次派发未生效，请刷新格子后重选",
                    null);
        }

        TaskVO taskVO = taskFlowService.getTask(saved.getId());
        return result(toCell(saved, vehicle, line, driver, day), taskVO, day, false);
    }

    // ---------------- 改派（只允许一次成功，并发返回当前归属） ----------------

    /**
     * 改派。规则：
     * ① 只有尚未开始检测（无任一工位记录）且未结束的任务可改派；检测结果、当前工位、任务号一律不动；
     * ② 任务行锁串行化并发改派；新格唯一索引只允许一次成功；
     * ③ 重复点击同目标 / 已被别人改到别处时，不报错，返回任务当前归属；
     * ④ 原格释放与新格占用同事务，要么全成要么全不动，同一任务不可能占两格。
     */
    @Transactional
    public DispatchResultVO reassign(Long taskId, ReassignRequest request) {
        Task task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BizException("检测任务不存在：id=" + taskId));
        LocalDate day = task.getCheckDate();
        Integer targetHour = request.getScheduleHour() == null ? task.getScheduleHour()
                : request.getScheduleHour();

        Optional<ScheduleSlot> currentSlot = slotRepository.findFirstByTaskIdAndActiveTrue(taskId);
        Driver targetDriver = request.getDriverId() == null ? null
                : driverRepository.findById(request.getDriverId())
                        .orElseThrow(() -> new BizException("引车员不存在：id=" + request.getDriverId()));

        // 幂等：重复点击、或两人同时改派到同一目标 → 当前归属已是请求目标，直接返回，不算失败
        if (targetDriver.getId().equals(task.getDriverId())
                && targetHour.equals(task.getScheduleHour())
                && currentSlot.isPresent()) {
            return currentResult(task, day, true);
        }

        if (isFinished(task)) {
            return conflictCurrent(task, day, "任务 " + task.getTaskNo() + " 已结束（"
                    + StationDict.taskStatusName(task.getStatus())
                    + "），不能改派，当前归属保持不变");
        }
        if (hasStarted(taskId)) {
            return conflictCurrent(task, day, "任务 " + task.getTaskNo() + " 已开始检测（当前第 "
                    + task.getCurrentSeqNo() + " 工位），不能改派，当前归属保持不变");
        }

        List<String> reasons = new ArrayList<>();
        checkDriverGate(targetDriver, targetHour, reasons);
        if (!reasons.isEmpty()) {
            throw new BizException(String.join("；", reasons));
        }

        // 目标格是否已被别的未结束任务占住（行锁后读的是最新数据）
        Optional<ScheduleSlot> occupied = slotRepository.findByCheckDateAndActiveTrue(day).stream()
                .filter(s -> targetDriver.getId().equals(s.getDriverId()))
                .filter(s -> s.getScheduleHour().equals(targetHour))
                .filter(s -> !s.getTaskId().equals(taskId))
                .findFirst();
        if (occupied.isPresent()) {
            Task holder = taskRepository.findById(occupied.get().getTaskId()).orElse(null);
            return conflictCurrent(task, day, "引车员「" + targetDriver.getDriverName() + "」"
                    + targetHour + ":00 时段已有未结束任务"
                    + (holder == null ? "" : "（" + holder.getTaskNo() + "）")
                    + "，本次改派未生效，任务仍在原格");
        }

        // 先把任务新归属落库并 flush，再释放旧格、插入新格——同一事务内顺序执行，
        // 任何一步失败整体回滚，旧格释放不会先于新格占用提交，外部任何时刻都看不到「无格」或「两格」
        task.setDriverId(targetDriver.getId());
        task.setScheduleHour(targetHour);
        taskRepository.save(task);
        taskRepository.flush();
        if (currentSlot.isPresent()) {
            slotRepository.releaseByTask(taskId);
        }

        ScheduleSlot slot = new ScheduleSlot();
        slot.setTaskId(taskId);
        slot.setDriverId(targetDriver.getId());
        slot.setCheckDate(day);
        slot.setScheduleHour(targetHour);
        slot.setActive(true);
        try {
            slotRepository.save(slot);
            slotRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // uk_task_active / uk_slot_active 兜底：并发下另一个事务已落定 → 回滚本事务，返回当前归属
            return conflictCurrent(task, day, "任务 " + task.getTaskNo()
                    + " 刚被另一笔改派落定，本次操作未生效，已按最新归属返回");
        }

        Task fresh = taskRepository.findById(taskId).orElse(task);
        Vehicle vehicle = vehicleRepository.findById(fresh.getVehicleId()).orElse(null);
        Line line = lineRepository.findById(fresh.getLineId()).orElse(null);
        ScheduleCellVO cell = toCell(fresh, vehicle, line, targetDriver, day);
        return result(cell, taskFlowService.getTask(taskId), day, false);
    }

    // ---------------- 改派资格预检（不落库） ----------------

    /**
     * 改派目标格预检：任务必须尚未开始检测，目标引车员/小时格必须通过在岗、在班、同格未占用闸门。
     * 与落库改派同一套规则，页面点格时先看阻断原因。
     */
    @Transactional(readOnly = true)
    public DispatchEligibilityVO checkReassign(Long taskId, Long driverId, Integer hour) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException("检测任务不存在：id=" + taskId));
        LocalDate day = task.getCheckDate();
        Integer targetHour = hour == null ? task.getScheduleHour() : hour;
        Driver driver = driverId == null ? null
                : driverRepository.findById(driverId)
                        .orElseThrow(() -> new BizException("引车员不存在：id=" + driverId));

        DispatchEligibilityVO vo = new DispatchEligibilityVO();
        vo.setVehicleId(task.getVehicleId());
        vo.setLineId(task.getLineId());
        vo.setLineNo(lineRepository.findById(task.getLineId()).map(Line::getLineNo).orElse(""));
        vehicleRepository.findById(task.getVehicleId())
                .ifPresent(v -> vo.setPlateNo(v.getPlateNo()));
        vo.setDriverId(driverId);
        if (driver != null) {
            vo.setDriverName(driver.getDriverName());
        }
        vo.setScheduleHour(targetHour);
        vo.setCheckDate(day.toString());

        List<String> reasons = new ArrayList<>();
        if (isFinished(task)) {
            reasons.add("任务 " + task.getTaskNo() + " 已结束（"
                    + StationDict.taskStatusName(task.getStatus()) + "），不能改派");
        } else if (hasStarted(taskId)) {
            reasons.add("任务 " + task.getTaskNo() + " 已开始检测（当前第 " + task.getCurrentSeqNo()
                    + " 工位），不能改派");
        }
        checkDriverGate(driver, targetHour, reasons);
        if (driver != null && targetHour != null && isShiftHour(driver, targetHour)
                && "ON_DUTY".equals(driver.getStatus())) {
            slotRepository.findByCheckDateAndActiveTrue(day).stream()
                    .filter(s -> driver.getId().equals(s.getDriverId()))
                    .filter(s -> s.getScheduleHour().equals(targetHour))
                    .filter(s -> !s.getTaskId().equals(taskId))
                    .findFirst()
                    .ifPresent(s -> reasons.add("引车员「" + driver.getDriverName() + "」"
                            + targetHour + ":00 时段已有未结束任务，不能改派到该格"));
        }
        vo.setBlockReasons(reasons);
        vo.setEligible(reasons.isEmpty());
        return vo;
    }

    // ---------------- 台次统计（有效占格口径，与派发/改派同步） ----------------

    /**
     * 按引车员统计当日台次：未结束台次取有效小时格，已检台次取 driver.checked_count。
     */
    public List<DriverStatDTO> driverStats(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        Map<Long, Long> activeCount = slotRepository.findByCheckDateAndActiveTrue(day).stream()
                .collect(Collectors.groupingBy(ScheduleSlot::getDriverId, Collectors.counting()));
        List<DriverStatDTO> result = new ArrayList<>();
        for (Driver driver : driverRepository.findAllByOrderByIdAsc()) {
            DriverStatDTO dto = new DriverStatDTO(driver.getId(),
                    activeCount.getOrDefault(driver.getId(), 0L));
            dto.setDriverName(driver.getDriverName());
            result.add(dto);
        }
        result.sort((a, b) -> Long.compare(b.getTaskCount(), a.getTaskCount()));
        return result;
    }

    // ---------------- 校验工具 ----------------

    private void checkDriverGate(Driver driver, Integer hour, List<String> reasons) {
        if (driver == null) {
            reasons.add("引车员不存在，请重新选择");
            return;
        }
        if (!"ON_DUTY".equals(driver.getStatus())) {
            reasons.add("引车员「" + driver.getDriverName() + "」当日不在岗，不能派单");
        }
        if (hour == null || hour < StationDict.SHIFT_START_HOUR || hour >= StationDict.SHIFT_END_HOUR) {
            reasons.add("排班小时段必须在 " + StationDict.SHIFT_START_HOUR + ":00-"
                    + StationDict.SHIFT_END_HOUR + ":00 之间");
            return;
        }
        if (!isShiftHour(driver, hour)) {
            reasons.add("引车员「" + driver.getDriverName() + "」当日排班小时段为 "
                    + TaskFlowService.formatShift(driver.getShiftHours())
                    + "，不包含 " + hour + ":00 时段");
        }
    }

    static boolean isShiftHour(Driver driver, int hour) {
        if (driver.getShiftHours() == null || driver.getShiftHours().isBlank()) {
            return false;
        }
        Set<String> hours = new HashSet<>(List.of(driver.getShiftHours().split(",")));
        return hours.contains(String.valueOf(hour));
    }

    private boolean hasStarted(Long taskId) {
        // 登录工位记录是第一道检测动作；尚未产生任何工位记录即「尚未开始检测」
        return !recordRepository.findByTaskIdOrderBySeqNoAsc(taskId).isEmpty();
    }

    private boolean isFinished(Task task) {
        return "PASS".equals(task.getStatus()) || "FAIL".equals(task.getStatus());
    }

    private DispatchResultVO conflictCurrent(Task task, LocalDate day, String message) {
        // 行锁内读到的 task 可能已被并发事务改写：抛带 taskId 的冲突，由控制器用新事务回读最新归属
        throw new ScheduleConflictException(message, task.getId());
    }

    /**
     * 并发冲突后回读任务当前归属（独立只读事务）：格子、任务详情、台次统计都以库里最新数据为准。
     */
    @Transactional(readOnly = true)
    public DispatchResultVO currentOwnership(Long taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException("检测任务不存在：id=" + taskId));
        return currentResult(task, task.getCheckDate(), true);
    }

    private DispatchResultVO currentResult(Task task, LocalDate day, boolean currentOwnership) {
        Vehicle vehicle = vehicleRepository.findById(task.getVehicleId()).orElse(null);
        Line line = lineRepository.findById(task.getLineId()).orElse(null);
        Driver driver = driverRepository.findById(task.getDriverId()).orElse(null);
        return result(toCell(task, vehicle, line, driver, day),
                taskFlowService.getTask(task.getId()), day, currentOwnership);
    }

    private DispatchResultVO result(ScheduleCellVO cell, TaskVO task, LocalDate day,
                                    boolean currentOwnership) {
        DispatchResultVO vo = new DispatchResultVO();
        vo.setCell(cell);
        vo.setTask(task);
        vo.setDriverStats(driverStats(day));
        vo.setCurrentOwnership(currentOwnership);
        return vo;
    }

    private ScheduleCellVO toCell(Task task, Vehicle vehicle, Line line, Driver driver, LocalDate day) {
        ScheduleCellVO cell = new ScheduleCellVO();
        cell.setHour(task.getScheduleHour());
        cell.setHourLabel(task.getScheduleHour() == null ? "" : task.getScheduleHour() + ":00");
        cell.setTaskId(task.getId());
        cell.setTaskNo(task.getTaskNo());
        cell.setPlateNo(vehicle == null ? "" : vehicle.getPlateNo());
        cell.setLineId(task.getLineId());
        cell.setLineNo(line == null ? "" : line.getLineNo());
        cell.setStatus(task.getStatus());
        cell.setStatusName(StationDict.taskStatusName(task.getStatus()));
        Station station = task.getCurrentStationId() == null ? null
                : stationRepository.findById(task.getCurrentStationId()).orElse(null);
        cell.setStationName(station == null ? "" : station.getStationName());
        if (driver != null) {
            cell.setInShift(isShiftHour(driver, task.getScheduleHour()));
            cell.setOnDuty("ON_DUTY".equals(driver.getStatus()));
        }
        if (isFinished(task)) {
            cell.setCellState(task.getStatus());
        } else if (hasStarted(task.getId())) {
            cell.setCellState("RETEST".equals(task.getStatus()) ? "RETEST" : "CHECKING");
        } else {
            cell.setCellState("WAITING");
        }
        return cell;
    }

    private static LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return LocalDate.now();
        }
        return LocalDate.parse(date);
    }
}
