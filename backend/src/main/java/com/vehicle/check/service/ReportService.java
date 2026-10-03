package com.vehicle.check.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vehicle.check.common.BizException;
import com.vehicle.check.common.ScheduleConflictException;
import com.vehicle.check.common.StationDict;
import com.vehicle.check.domain.Device;
import com.vehicle.check.domain.Driver;
import com.vehicle.check.domain.Line;
import com.vehicle.check.domain.Report;
import com.vehicle.check.domain.ScheduleSlot;
import com.vehicle.check.domain.Station;
import com.vehicle.check.domain.Task;
import com.vehicle.check.domain.TaskStationRecord;
import com.vehicle.check.domain.Vehicle;
import com.vehicle.check.dto.BadgeVO;
import com.vehicle.check.dto.IssueReportRequest;
import com.vehicle.check.dto.LineReportStatDTO;
import com.vehicle.check.dto.ReportVO;
import com.vehicle.check.dto.RetestRequest;
import com.vehicle.check.dto.TaskVO;
import com.vehicle.check.repository.DeviceRepository;
import com.vehicle.check.repository.DriverRepository;
import com.vehicle.check.repository.LineRepository;
import com.vehicle.check.repository.ReportRepository;
import com.vehicle.check.repository.ScheduleSlotRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.TaskRepository;
import com.vehicle.check.repository.TaskStationRecordRepository;
import com.vehicle.check.repository.VehicleRepository;

/**
 * 检测报告：签发（关键项判定 + 设备标定有效期校验）、徽章墙、安排复检。
 */
@Service
public class ReportService {

    /** 徽章墙顺序：尾气 / 制动 / 灯光 / 外观 / 底盘 */
    private static final List<String> BADGE_ORDER = List.of("EMISSION", "BRAKE", "LIGHT", "APPEARANCE", "CHASSIS");

    private final ReportRepository reportRepository;
    private final TaskRepository taskRepository;
    private final TaskStationRecordRepository recordRepository;
    private final VehicleRepository vehicleRepository;
    private final LineRepository lineRepository;
    private final StationRepository stationRepository;
    private final DeviceRepository deviceRepository;
    private final DriverRepository driverRepository;
    private final ScheduleSlotRepository slotRepository;

    public ReportService(ReportRepository reportRepository, TaskRepository taskRepository,
                         TaskStationRecordRepository recordRepository, VehicleRepository vehicleRepository,
                         LineRepository lineRepository, StationRepository stationRepository,
                         DeviceRepository deviceRepository, DriverRepository driverRepository,
                         ScheduleSlotRepository slotRepository) {
        this.reportRepository = reportRepository;
        this.taskRepository = taskRepository;
        this.recordRepository = recordRepository;
        this.vehicleRepository = vehicleRepository;
        this.lineRepository = lineRepository;
        this.stationRepository = stationRepository;
        this.deviceRepository = deviceRepository;
        this.driverRepository = driverRepository;
        this.slotRepository = slotRepository;
    }

    public List<ReportVO> listReports() {
        List<ReportVO> result = new ArrayList<>();
        for (Report report : reportRepository.findAllByOrderByIdDesc()) {
            result.add(toVO(report));
        }
        return result;
    }

    public ReportVO getReport(Long id) {
        Report report = reportRepository.findById(id)
                .orElseThrow(() -> new BizException("检测报告不存在：id=" + id));
        return toVO(report);
    }

    public ReportVO getReportByNo(String reportNo) {
        return reportRepository.findAll().stream()
                .filter(r -> r.getReportNo().equals(reportNo))
                .findFirst()
                .map(this::toVO)
                .orElseThrow(() -> new BizException("检测报告不存在：" + reportNo));
    }

    /**
     * 签发报告。业务规则：
     * ① 任务必须走完全部工位；
     * ② 设备超过标定日期不允许出报告（给出超期天数）；
     * ③ 任一关键项不合格则结论必须为不合格。
     */
    @Transactional
    public ReportVO issueReport(Long taskId, IssueReportRequest request) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException("检测任务不存在：id=" + taskId));
        if ("CHECKING".equals(task.getStatus()) || "RETEST".equals(task.getStatus())) {
            throw new BizException("任务 " + task.getTaskNo() + " 尚在第 " + task.getCurrentSeqNo() + " 个工位（"
                    + StationDict.stationName(stationTypeOf(task)) + "）未完成全部工位检测，不能签发报告");
        }
        checkDeviceCalibration(task.getLineId());

        List<TaskStationRecord> records = recordRepository.findByTaskIdOrderBySeqNoAsc(taskId);
        List<TaskStationRecord> failedKey = records.stream()
                .filter(r -> StationDict.isKeyItem(r.getStationType()) && "FAIL".equals(r.getResult()))
                .toList();
        String conclusion = failedKey.isEmpty() ? "PASS" : "FAIL";
        if (!conclusion.equals(task.getStatus())) {
            throw new BizException("任务结论（" + StationDict.taskStatusName(task.getStatus())
                    + "）与关键项判定结果（" + (failedKey.isEmpty() ? "合格" : "不合格") + "）不一致，请先在总检工位重新复核");
        }

        Vehicle vehicle = vehicleRepository.findById(task.getVehicleId())
                .orElseThrow(() -> new BizException("任务关联车辆已丢失"));
        Line line = lineRepository.findById(task.getLineId())
                .orElseThrow(() -> new BizException("任务关联检测线已丢失"));

        String snapshot = records.stream()
                .filter(r -> !"LOGIN".equals(r.getStationType()) && !"FINAL".equals(r.getStationType()))
                .map(r -> StationDict.stationName(r.getStationType()) + ":" + r.getValueText()
                        + ("PASS".equals(r.getResult()) ? " 合格" : " 不合格"))
                .collect(Collectors.joining(" | "));
        String failItems = failedKey.stream()
                .map(r -> StationDict.stationName(r.getStationType()) + " " + r.getValueText())
                .collect(Collectors.joining(";"));

        LocalDate signDate = request.getSignDate() == null ? LocalDate.now() : request.getSignDate();
        Report report = new Report();
        report.setReportNo(nextReportNo(signDate));
        report.setTaskId(taskId);
        report.setPlateNo(vehicle.getPlateNo());
        report.setLineNo(line.getLineNo());
        report.setConclusion(conclusion);
        report.setConclusionName("PASS".equals(conclusion) ? "合格" : "不合格");
        report.setSnapshot(snapshot);
        report.setFailItems(failItems);
        report.setSigner(request.getSigner() == null ? "授权签字人 何工" : request.getSigner());
        report.setSignDate(signDate);
        report.setRetestOf(request.getRetestOf());
        report.setCreateTime(LocalDateTime.now());
        return toVO(reportRepository.save(report));
    }

    /**
     * 安排复检：把不合格车辆送回流水线起点
     */
    @Transactional
    public TaskVO retest(Long reportId, RetestRequest request) {
        Report report = reportRepository.findById(reportId)
                .orElseThrow(() -> new BizException("检测报告不存在：id=" + reportId));
        if (!"FAIL".equals(report.getConclusion())) {
            throw new BizException("报告 " + report.getReportNo() + " 结论为「" + report.getConclusionName()
                    + "」，只有不合格报告才能安排复检");
        }
        Task origin = taskRepository.findById(report.getTaskId())
                .orElseThrow(() -> new BizException("报告关联的检测任务已丢失"));
        Line line = lineRepository.findById(origin.getLineId())
                .orElseThrow(() -> new BizException("检测线已丢失"));
        if (!"RUNNING".equals(line.getStatus())) {
            throw new BizException("检测线「" + line.getLineNo() + "」已停用，无法安排复检");
        }
        List<Station> stations = stationRepository.findByLineIdOrderBySeqNoAsc(line.getId());
        Station first = stations.get(0);
        if (!"IDLE".equals(first.getStatus())) {
            throw new BizException("检测线「" + line.getLineNo() + "」起点工位（" + first.getStationName()
                    + "）当前为" + StationDict.stationStatusName(first.getStatus()) + "，复检车辆暂无法进入");
        }

        LocalDate checkDate = request.getCheckDate() == null ? LocalDate.now() : request.getCheckDate();
        Long driverId = request.getDriverId() == null ? origin.getDriverId() : request.getDriverId();
        Integer scheduleHour = request.getScheduleHour() == null
                ? origin.getScheduleHour() : request.getScheduleHour();
        Driver driver = null;
        if (driverId != null) {
            driver = driverRepository.findById(driverId)
                    .orElseThrow(() -> new BizException("引车员不存在：id=" + driverId));
            if (!"ON_DUTY".equals(driver.getStatus())) {
                throw new BizException("引车员「" + driver.getDriverName() + "」当日不在岗，不能安排复检派单");
            }
            if (scheduleHour == null || scheduleHour < StationDict.SHIFT_START_HOUR
                    || scheduleHour >= StationDict.SHIFT_END_HOUR) {
                throw new BizException("排班小时段必须在 " + StationDict.SHIFT_START_HOUR + ":00 - "
                        + StationDict.SHIFT_END_HOUR + ":00 之间");
            }
            if (!DispatchService.isShiftHour(driver, scheduleHour)) {
                throw new BizException("引车员「" + driver.getDriverName() + "」当日排班小时段为 "
                        + TaskFlowService.formatShift(driver.getShiftHours())
                        + "，不包含 " + scheduleHour + ":00 时段");
            }
        }

        Task task = new Task();
        task.setTaskNo(String.format("TASK-%s-R%03d", checkDate.toString().replace("-", ""),
                origin.getRetestTimes() + 1));
        task.setVehicleId(origin.getVehicleId());
        task.setLineId(origin.getLineId());
        task.setCurrentStationId(first.getId());
        task.setCurrentSeqNo(first.getSeqNo());
        task.setStatus("RETEST");
        task.setDriverId(driverId);
        task.setScheduleHour(scheduleHour);
        task.setCheckDate(checkDate);
        task.setRetestTimes(origin.getRetestTimes() + 1);
        // 复检链必须咬住原报告：转线只换检测线，不换任务/不换报告关联，
        // 全部剩余工位完成前不允许签发新报告（issueReport 的在检拦截天然保证）
        String originReportNo = report.getRetestOf() == null || report.getRetestOf().isBlank()
                ? report.getReportNo() : report.getRetestOf();
        task.setOriginReportNo(originReportNo);
        task.setCreateTime(LocalDateTime.now());

        // 复检同样是「任务 + 小时格 + 起点工位」一次绑定，任何一环冲突整笔回滚
        int claimed = stationRepository.claimStation(first.getId());
        if (claimed == 0) {
            throw new BizException("检测线「" + line.getLineNo() + "」起点工位（" + first.getStationName()
                    + "）在确认瞬间已被占用，复检未生效，请刷新后重试");
        }
        report.setRetestOf(report.getRetestOf() == null || report.getRetestOf().isBlank()
                ? report.getReportNo() : report.getRetestOf());
        reportRepository.save(report);

        Task saved = taskRepository.save(task);
        if (driver != null && scheduleHour != null) {
            ScheduleSlot slot = new ScheduleSlot();
            slot.setTaskId(saved.getId());
            slot.setDriverId(driver.getId());
            slot.setCheckDate(checkDate);
            slot.setScheduleHour(scheduleHour);
            slot.setActive(true);
            try {
                slotRepository.save(slot);
                slotRepository.flush();
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // 整笔事务回滚（复检任务与起点占位都不落库），不带任务归属
                throw new ScheduleConflictException("引车员「" + driver.getDriverName() + "」"
                        + scheduleHour + ":00 时段已有未结束任务，复检派单未生效，请改派到其他时段",
                        null);
            }
        }
        return taskFlowView(saved);
    }

    public List<LineReportStatDTO> lineStats() {
        return reportRepository.statReportByLine();
    }

    public List<ReportVO> failReportsOfDate(LocalDate date) {
        List<ReportVO> result = new ArrayList<>();
        for (Report report : reportRepository.findFailReportsOfDate(date)) {
            result.add(toVO(report));
        }
        return result;
    }

    /**
     * 设备超过标定日期不允许出报告，给出超期天数
     */
    private void checkDeviceCalibration(Long lineId) {
        LocalDate today = LocalDate.now();
        List<Device> devices = deviceRepository.findDevicesOfLine(lineId);
        List<String> overdue = new ArrayList<>();
        for (Device device : devices) {
            if (device.getNextCalDate().isBefore(today)) {
                long days = ChronoUnit.DAYS.between(device.getNextCalDate(), today);
                overdue.add("设备「" + device.getDeviceName() + "（" + device.getModel() + "）」标定已超期 " + days
                        + " 天（应于 " + device.getNextCalDate() + " 前完成标定）");
            }
        }
        if (!overdue.isEmpty()) {
            throw new BizException(String.join("；", overdue) + "，请先完成标定再签发报告");
        }
    }

    private String stationTypeOf(Task task) {
        return stationRepository.findById(task.getCurrentStationId())
                .map(Station::getStationType).orElse("LOGIN");
    }

    private String nextReportNo(LocalDate date) {
        long count = reportRepository.count() + 1;
        return String.format("RPT-%s-%03d", date.toString().replace("-", ""), count);
    }

    private ReportVO toVO(Report report) {
        ReportVO vo = new ReportVO();
        vo.setId(report.getId());
        vo.setReportNo(report.getReportNo());
        vo.setTaskId(report.getTaskId());
        vo.setTaskNo(taskRepository.findById(report.getTaskId()).map(Task::getTaskNo).orElse(""));
        vo.setPlateNo(report.getPlateNo());
        vo.setLineNo(report.getLineNo());
        vo.setConclusion(report.getConclusion());
        vo.setConclusionName(report.getConclusionName());
        vo.setSnapshot(report.getSnapshot());
        vo.setSigner(report.getSigner());
        vo.setSignDate(report.getSignDate());
        vo.setRetestOf(report.getRetestOf());
        vo.setCreateTime(report.getCreateTime());
        if (report.getFailItems() != null && !report.getFailItems().isBlank()) {
            vo.setFailItems(List.of(report.getFailItems().split(";")));
        }
        vehicleRepository.findAll().stream()
                .filter(v -> v.getPlateNo().equals(report.getPlateNo()))
                .findFirst()
                .ifPresent(vehicle -> {
                    vo.setOwnerName(vehicle.getOwnerName());
                    vo.setVehicleTypeName(StationDict.vehicleTypeName(vehicle.getVehicleType()));
                });

        Map<String, TaskStationRecord> recordMap = recordRepository.findByTaskIdOrderBySeqNoAsc(report.getTaskId())
                .stream()
                .collect(Collectors.toMap(TaskStationRecord::getStationType, r -> r, (a, b) -> b));
        List<BadgeVO> badges = new ArrayList<>();
        for (String type : BADGE_ORDER) {
            BadgeVO badge = new BadgeVO();
            badge.setCode(type);
            badge.setName(StationDict.stationName(type));
            badge.setKeyItem(StationDict.isKeyItem(type));
            TaskStationRecord record = recordMap.get(type);
            if (record == null) {
                badge.setResult("PENDING");
                badge.setValueText("未检测");
            } else {
                badge.setResult(record.getResult());
                badge.setValueText(record.getValueText());
            }
            badges.add(badge);
        }
        vo.setBadges(badges);
        return vo;
    }

    private TaskVO taskFlowView(Task task) {
        TaskVO vo = new TaskVO();
        vo.setId(task.getId());
        vo.setTaskNo(task.getTaskNo());
        vo.setVehicleId(task.getVehicleId());
        vo.setPlateNo(vehicleRepository.findById(task.getVehicleId()).map(Vehicle::getPlateNo).orElse(""));
        vo.setLineId(task.getLineId());
        vo.setLineNo(lineRepository.findById(task.getLineId()).map(Line::getLineNo).orElse(""));
        vo.setCurrentStationId(task.getCurrentStationId());
        vo.setCurrentStationType(stationTypeOf(task));
        vo.setCurrentStationName(StationDict.stationName(vo.getCurrentStationType()));
        vo.setCurrentSeqNo(task.getCurrentSeqNo());
        vo.setStatus(task.getStatus());
        vo.setStatusName(StationDict.taskStatusName(task.getStatus()));
        vo.setDriverName(task.getDriverId() == null ? ""
                : driverRepository.findById(task.getDriverId()).map(Driver::getDriverName).orElse(""));
        vo.setScheduleHour(task.getScheduleHour());
        vo.setCheckDate(task.getCheckDate());
        vo.setRetestTimes(task.getRetestTimes());
        vo.setOriginReportNo(task.getOriginReportNo());
        boolean started = !recordRepository.findByTaskIdOrderBySeqNoAsc(task.getId()).isEmpty();
        boolean finished = "PASS".equals(task.getStatus()) || "FAIL".equals(task.getStatus());
        vo.setStarted(started);
        vo.setReassignAllowed(!started && !finished);
        return vo;
    }
}
