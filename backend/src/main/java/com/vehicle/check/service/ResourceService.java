package com.vehicle.check.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vehicle.check.common.BizException;
import com.vehicle.check.common.StationDict;
import com.vehicle.check.domain.Device;
import com.vehicle.check.domain.Driver;
import com.vehicle.check.domain.ScheduleSlot;
import com.vehicle.check.domain.Station;
import com.vehicle.check.domain.Task;
import com.vehicle.check.domain.Vehicle;
import com.vehicle.check.dto.CalibrateRequest;
import com.vehicle.check.dto.CalibrateStatDTO;
import com.vehicle.check.dto.DeviceVO;
import com.vehicle.check.dto.ScheduleCellVO;
import com.vehicle.check.dto.ScheduleRowVO;
import com.vehicle.check.repository.DeviceRepository;
import com.vehicle.check.repository.DriverRepository;
import com.vehicle.check.repository.LineRepository;
import com.vehicle.check.repository.ScheduleSlotRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.TaskRepository;
import com.vehicle.check.repository.TaskStationRecordRepository;
import com.vehicle.check.repository.VehicleRepository;

/**
 * 设备标定与引车员：设备台账倒计时、登记标定结果；引车员排班格子表、派单与改派。
 */
@Service
public class ResourceService {

    private final DeviceRepository deviceRepository;
    private final DriverRepository driverRepository;
    private final TaskRepository taskRepository;
    private final VehicleRepository vehicleRepository;
    private final StationRepository stationRepository;
    private final ScheduleSlotRepository slotRepository;
    private final TaskStationRecordRepository recordRepository;
    private final LineRepository lineRepository;

    public ResourceService(DeviceRepository deviceRepository, DriverRepository driverRepository,
                           TaskRepository taskRepository, VehicleRepository vehicleRepository,
                           StationRepository stationRepository, ScheduleSlotRepository slotRepository,
                           TaskStationRecordRepository recordRepository, LineRepository lineRepository) {
        this.deviceRepository = deviceRepository;
        this.driverRepository = driverRepository;
        this.taskRepository = taskRepository;
        this.vehicleRepository = vehicleRepository;
        this.stationRepository = stationRepository;
        this.slotRepository = slotRepository;
        this.recordRepository = recordRepository;
        this.lineRepository = lineRepository;
    }

    // ---------------- 设备标定 ----------------

    public List<DeviceVO> listDevices() {
        LocalDate today = LocalDate.now();
        List<DeviceVO> result = new ArrayList<>();
        for (Device device : deviceRepository.findAllByOrderByNextCalDateAsc()) {
            result.add(toVO(device, today));
        }
        return result;
    }

    public DeviceVO getDevice(Long id) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new BizException("设备不存在：id=" + id));
        return toVO(device, LocalDate.now());
    }

    /**
     * 登记标定结果：标定机构、结论、下次标定日期，并自动重置倒计时
     */
    @Transactional
    public DeviceVO calibrate(Long id, CalibrateRequest request) {
        Device device = deviceRepository.findById(id)
                .orElseThrow(() -> new BizException("设备不存在：id=" + id));
        if (request.getCalOrg() == null || request.getCalOrg().isBlank()) {
            throw new BizException("标定机构不能为空");
        }
        if (!Set.of("PASS", "FAIL").contains(request.getCalResult())) {
            throw new BizException("标定结论只能是 合格(PASS) / 不合格(FAIL)");
        }
        LocalDate today = LocalDate.now();
        LocalDate next = request.getNextCalDate();
        if (next == null) {
            throw new BizException("下次标定日期不能为空");
        }
        if (!next.isAfter(today)) {
            throw new BizException("下次标定日期必须晚于今天（" + today + "）");
        }
        if ("FAIL".equals(request.getCalResult())) {
            throw new BizException("设备「" + device.getDeviceName() + "」标定结论为不合格，需停用整改后再登记合格结论");
        }

        device.setCalOrg(request.getCalOrg().trim());
        device.setCalResult("PASS");
        device.setLastCalDate(request.getLastCalDate() == null ? today : request.getLastCalDate());
        device.setNextCalDate(next);
        device.setStatus(remainDays(next, today) <= StationDict.CAL_DUE_DAYS ? "DUE" : "NORMAL");
        return toVO(deviceRepository.save(device), today);
    }

    public CalibrateStatDTO calStat() {
        LocalDate today = LocalDate.now();
        return deviceRepository.statCalibration(today, today.plusDays(StationDict.CAL_DUE_DAYS));
    }

    // ---------------- 引车员排班 ----------------

    /**
     * 排班格子表（服务端状态恢复）：纵轴引车员、横轴小时段（8:00-18:00）。
     * 每个格子带状态：可派空格 / 待开始（可改派）/ 在检 / 复检 / 合格 / 不合格 / 不在班 / 不在岗，
     * 并带点格时的阻断原因；格子内容只来自有效小时格 vc_schedule_slot（active=1）与任务落库状态。
     */
    public List<ScheduleRowVO> schedule(LocalDate date) {
        LocalDate day = date == null ? LocalDate.now() : date;
        Map<Long, String> plateMap = vehicleRepository.findAll().stream()
                .collect(Collectors.toMap(Vehicle::getId, Vehicle::getPlateNo, (a, b) -> a));
        Map<Long, String> stationMap = stationRepository.findAll().stream()
                .collect(Collectors.toMap(Station::getId, Station::getStationName, (a, b) -> a));
        Map<Long, String> lineMap = lineRepository.findAll().stream()
                .collect(Collectors.toMap(com.vehicle.check.domain.Line::getId,
                        com.vehicle.check.domain.Line::getLineNo, (a, b) -> a));
        Map<Long, Task> taskMap = taskRepository.findAll().stream()
                .filter(task -> day.equals(task.getCheckDate()))
                .collect(Collectors.toMap(Task::getId, t -> t, (a, b) -> a));
        // 格子归属只认真实占格记录：已结束的任务 active=0 已释放，历史格在按 task 补展示时单独取
        Map<String, ScheduleSlot> activeSlotByDriverHour = slotRepository.findByCheckDateAndActiveTrue(day).stream()
                .collect(Collectors.toMap(s -> s.getDriverId() + "-" + s.getScheduleHour(),
                        s -> s, (a, b) -> a));
        // 已释放（任务合格/不合格或改走）的当日格子仅作历史痕迹；同一格若又被新任务占走，
        // active 槽位优先（上面已取），这里只在没有活动占格时展示最近一次痕迹
        Map<String, ScheduleSlot> finishedSlotByDriverHour = slotRepository.findAll().stream()
                .filter(s -> day.equals(s.getCheckDate()) && Boolean.FALSE.equals(s.getActive()))
                .collect(Collectors.toMap(s -> s.getDriverId() + "-" + s.getScheduleHour(),
                        s -> s, (a, b) -> a.getId() >= b.getId() ? a : b));
        Map<Long, Long> taskStationCount = taskMap.keySet().stream()
                .collect(Collectors.toMap(id -> id,
                        id -> (long) recordRepository.findByTaskIdOrderBySeqNoAsc(id).size()));

        List<ScheduleRowVO> rows = new ArrayList<>();
        for (Driver driver : driverRepository.findAllByOrderByIdAsc()) {
            ScheduleRowVO row = new ScheduleRowVO();
            row.setDriverId(driver.getId());
            row.setDriverName(driver.getDriverName());
            row.setCertLevel(driver.getCertLevel());
            row.setCertNo(driver.getCertNo());
            row.setShiftHours(driver.getShiftHours());
            row.setCheckedCount(driver.getCheckedCount());
            row.setStatus(driver.getStatus());

            List<ScheduleCellVO> cells = new ArrayList<>();
            boolean onDuty = "ON_DUTY".equals(driver.getStatus());
            for (int hour = StationDict.SHIFT_START_HOUR; hour < StationDict.SHIFT_END_HOUR; hour++) {
                String key = driver.getId() + "-" + hour;
                ScheduleCellVO cell = new ScheduleCellVO();
                cell.setHour(hour);
                cell.setHourLabel(hour + ":00");
                boolean inShift = DispatchService.isShiftHour(driver, hour);
                cell.setInShift(inShift);
                cell.setOnDuty(onDuty);

                ScheduleSlot slot = activeSlotByDriverHour.get(key);
                if (slot == null) {
                    slot = finishedSlotByDriverHour.get(key);
                }
                if (slot != null) {
                    Task task = taskMap.get(slot.getTaskId());
                    // 已被改派释放的旧格（active=0 且任务仍未结束）不渲染——任务已不在该格，
                    // 只渲染当前有效占格，以及已合格/不合格任务留在格内的结果痕迹
                    boolean releasedAway = Boolean.FALSE.equals(slot.getActive()) && task != null
                            && !"PASS".equals(task.getStatus()) && !"FAIL".equals(task.getStatus());
                    if (task != null && !releasedAway) {
                        fillTaskCell(cell, task, plateMap, stationMap,
                                lineMap.getOrDefault(task.getLineId(), ""),
                                taskStationCount.getOrDefault(task.getId(), 0L) > 0);
                    } else if (releasedAway) {
                        cell.setCellState(inShift ? (onDuty ? "EMPTY_SHIFT" : "OFF_DUTY") : "OFF_SHIFT");
                        if (!onDuty) {
                            cell.getBlockReasons().add("引车员「" + driver.getDriverName() + "」当日不在岗，不能派单");
                        }
                        if (!inShift) {
                            cell.getBlockReasons().add(hour + ":00 不在该引车员当日排班小时段 "
                                    + TaskFlowService.formatShift(driver.getShiftHours()) + " 内");
                        }
                    }
                } else {
                    cell.setCellState(inShift ? (onDuty ? "EMPTY_SHIFT" : "OFF_DUTY") : "OFF_SHIFT");
                    if (!onDuty) {
                        cell.getBlockReasons().add("引车员「" + driver.getDriverName() + "」当日不在岗，不能派单");
                    }
                    if (!inShift) {
                        cell.getBlockReasons().add(hour + ":00 不在该引车员当日排班小时段 "
                                + TaskFlowService.formatShift(driver.getShiftHours()) + " 内");
                    }
                }
                cells.add(cell);
            }
            row.setCells(cells);
            rows.add(row);
        }
        return rows;
    }

    private void fillTaskCell(ScheduleCellVO cell, Task task, Map<Long, String> plateMap,
                              Map<Long, String> stationMap, String lineNo, boolean started) {
        cell.setTaskId(task.getId());
        cell.setTaskNo(task.getTaskNo());
        cell.setPlateNo(plateMap.getOrDefault(task.getVehicleId(), ""));
        cell.setLineId(task.getLineId());
        cell.setLineNo(lineNo);
        cell.setStatus(task.getStatus());
        cell.setStatusName(StationDict.taskStatusName(task.getStatus()));
        cell.setStationName(task.getCurrentStationId() == null ? ""
                : stationMap.getOrDefault(task.getCurrentStationId(), ""));
        boolean finished = "PASS".equals(task.getStatus()) || "FAIL".equals(task.getStatus());
        if (finished) {
            cell.setCellState(task.getStatus());
            cell.getBlockReasons().add("任务已" + cell.getStatusName() + "，不能改派");
        } else if (started) {
            cell.setCellState("RETEST".equals(task.getStatus()) ? "RETEST" : "CHECKING");
            cell.getBlockReasons().add("任务已开始检测（当前第 " + task.getCurrentSeqNo() + " 工位"
                    + (cell.getStationName().isBlank() ? "" : "·" + cell.getStationName())
                    + "），不能改派");
        } else {
            cell.setCellState("WAITING");
        }
        // 防御性提示：正常派发事务保证不会出现，若数据被手工改动越界，格子如实标红
        if (Boolean.FALSE.equals(cell.getOnDuty())) {
            cell.getBlockReasons().add("引车员当前不在岗，请刷新后改派到其他引车员");
        }
        if (Boolean.FALSE.equals(cell.getInShift())) {
            cell.getBlockReasons().add("该小时当前不在引车员排班时段内，请刷新后改派");
        }
    }

    public List<Driver> listDrivers() {
        return driverRepository.findAllByOrderByIdAsc();
    }

    // ---------------- 工具 ----------------

    private static long remainDays(LocalDate nextCalDate, LocalDate today) {
        return ChronoUnit.DAYS.between(today, nextCalDate);
    }

    private DeviceVO toVO(Device device, LocalDate today) {
        DeviceVO vo = new DeviceVO();
        vo.setId(device.getId());
        vo.setDeviceName(device.getDeviceName());
        vo.setModel(device.getModel());
        vo.setDeviceType(device.getDeviceType());
        vo.setDeviceTypeName(StationDict.stationName(device.getDeviceType()));
        vo.setLastCalDate(device.getLastCalDate());
        vo.setNextCalDate(device.getNextCalDate());
        vo.setCalOrg(device.getCalOrg());
        vo.setCalResult(device.getCalResult());
        long remain = remainDays(device.getNextCalDate(), today);
        vo.setRemainDays(remain);
        vo.setLevel(remain < 0 ? "OVERDUE" : (remain <= StationDict.CAL_DUE_DAYS ? "DUE" : "NORMAL"));
        vo.setStatus(vo.getLevel());
        vo.setStatusName(StationDict.deviceStatusName(vo.getLevel()));
        return vo;
    }
}
