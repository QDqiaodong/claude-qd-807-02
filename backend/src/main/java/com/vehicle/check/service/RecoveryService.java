package com.vehicle.check.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vehicle.check.common.BizException;
import com.vehicle.check.common.StationDict;
import com.vehicle.check.common.TransferConflictException;
import com.vehicle.check.domain.Device;
import com.vehicle.check.domain.Driver;
import com.vehicle.check.domain.Line;
import com.vehicle.check.domain.LineTransfer;
import com.vehicle.check.domain.Station;
import com.vehicle.check.domain.Task;
import com.vehicle.check.domain.TaskStationRecord;
import com.vehicle.check.domain.Vehicle;
import com.vehicle.check.dto.AffectedTaskVO;
import com.vehicle.check.dto.CandidateLineVO;
import com.vehicle.check.dto.RecordVO;
import com.vehicle.check.dto.RecoveryBoardVO;
import com.vehicle.check.dto.StationQueueDTO;
import com.vehicle.check.dto.TransferConfirmRequest;
import com.vehicle.check.dto.TransferVO;
import com.vehicle.check.repository.DeviceRepository;
import com.vehicle.check.repository.DriverRepository;
import com.vehicle.check.repository.LineRepository;
import com.vehicle.check.repository.LineTransferRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.TaskRepository;
import com.vehicle.check.repository.TaskStationRecordRepository;
import com.vehicle.check.repository.VehicleRepository;

/**
 * 在检车辆跨线恢复处置台：
 * 查出卡在故障工位的在检车辆 → 逐条评估候选线（说明推荐/排除原因）→ 确认转线或保持待处置。
 * <p>
 * 写操作的真正状态回写在 {@link RecoveryTxService}（单一事务）；本类负责编排：
 * 捕获并发冲突、给失败的一方留 FAILED 痕迹并抛出最新去向提示。
 */
@Service
public class RecoveryService {

    private final TaskRepository taskRepository;
    private final StationRepository stationRepository;
    private final LineRepository lineRepository;
    private final VehicleRepository vehicleRepository;
    private final DeviceRepository deviceRepository;
    private final DriverRepository driverRepository;
    private final TaskStationRecordRepository recordRepository;
    private final LineTransferRepository transferRepository;
    private final RecoveryCandidateEvaluator evaluator;
    private final RecoveryTxService txService;

    public RecoveryService(TaskRepository taskRepository, StationRepository stationRepository,
                           LineRepository lineRepository, VehicleRepository vehicleRepository,
                           DeviceRepository deviceRepository, DriverRepository driverRepository,
                           TaskStationRecordRepository recordRepository,
                           LineTransferRepository transferRepository,
                           RecoveryCandidateEvaluator evaluator, RecoveryTxService txService) {
        this.taskRepository = taskRepository;
        this.stationRepository = stationRepository;
        this.lineRepository = lineRepository;
        this.vehicleRepository = vehicleRepository;
        this.deviceRepository = deviceRepository;
        this.driverRepository = driverRepository;
        this.recordRepository = recordRepository;
        this.transferRepository = transferRepository;
        this.evaluator = evaluator;
        this.txService = txService;
    }

    // ---------------- 查询：处置台 ----------------

    @Transactional(readOnly = true)
    public RecoveryBoardVO board() {
        RecoveryBoardVO vo = new RecoveryBoardVO();
        Map<Long, Line> lineMap = lineRepository.findAllByOrderByIdAsc().stream()
                .collect(Collectors.toMap(Line::getId, l -> l, (a, b) -> a));
        Map<Long, Vehicle> vehicleMap = vehicleRepository.findAll().stream()
                .collect(Collectors.toMap(Vehicle::getId, v -> v, (a, b) -> a));
        Map<Long, Device> deviceMap = deviceRepository.findAll().stream()
                .collect(Collectors.toMap(Device::getId, d -> d, (a, b) -> a));
        Map<Long, Long> queueMap = taskRepository.countQueueGroupByStation().stream()
                .collect(Collectors.toMap(StationQueueDTO::getStationId, StationQueueDTO::getQueueCount, (a, b) -> a));
        Map<Long, Long> lineLoadMap = loadByLine();
        List<Line> lines = new ArrayList<>(lineMap.values());
        LocalDate today = LocalDate.now();

        List<Task> inProgress = taskRepository.findAllByOrderByIdAsc().stream()
                .filter(t -> "CHECKING".equals(t.getStatus()) || "RETEST".equals(t.getStatus()))
                .toList();

        int faultCount = 0;
        for (Task task : inProgress) {
            Station station = task.getCurrentStationId() == null ? null
                    : stationRepository.findById(task.getCurrentStationId()).orElse(null);
            if (station == null || !"FAULT".equals(station.getStatus())) {
                continue;
            }
            faultCount++;
            Vehicle vehicle = vehicleMap.get(task.getVehicleId());
            List<CandidateLineVO> candidates = evaluator.evaluateAll(task, station,
                    vehicle == null ? "" : vehicle.getVehicleType(), lines, queueMap, lineLoadMap,
                    deviceMap, today);
            vo.getAffectedTasks().add(toAffectedVO(task, station,
                    lineMap.get(task.getLineId()), vehicle, candidates));
        }
        vo.setFaultStationCount(faultCount);

        for (LineTransfer t : transferRepository.findTop20ByOrderByTransferTimeDesc()) {
            vo.getRecentTransfers().add(toTransferVO(t));
        }
        return vo;
    }

    @Transactional(readOnly = true)
    public AffectedTaskVO affectedTask(Long taskId) {
        RecoveryBoardVO board = board();
        return board.getAffectedTasks().stream().filter(a -> a.getTaskId().equals(taskId)).findFirst()
                .orElseThrow(() -> new BizException("任务 " + taskId + " 当前不在故障工位待处置，可能已被处置或故障已解除，请刷新处置台"));
    }

    // ---------------- 写操作：确认转线 / 保持待处置 ----------------

    /**
     * 确认转线。两名值班人员同时确认 / 目标工位瞬间被占用时：
     * 成功者正常返回；失败者收到最新去向提示，且留 FAILED 操作痕迹，任务指针绝不半迁移。
     */
    public TransferVO confirmTransfer(Long taskId, TransferConfirmRequest request) {
        if (request.getTargetLineId() == null) {
            throw new BizException("请选择目标检测线；若无可用目标请点「保持待处置」");
        }
        String operator = operatorOf(request);
        try {
            LineTransfer success = txService.transfer(taskId, request.getTargetLineId(), operator);
            return toTransferVO(success);
        } catch (TransferConflictException e) {
            // 主事务已整体回滚（含工位占位与任务指针），在独立新事务里给失败一方留痕
            txService.recordFailed(taskId, request.getTargetLineId(), operator, e.getMessage());
            throw e;
        }
    }

    /** 无可用目标：保持待处置，保留故障前进度，故障解除后可回原线继续 */
    @Transactional
    public TransferVO keepPending(Long taskId, TransferConfirmRequest request) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException("检测任务不存在：id=" + taskId));
        Station station = stationRepository.findById(task.getCurrentStationId())
                .orElseThrow(() -> new BizException("任务当前工位数据丢失"));
        if (!"FAULT".equals(station.getStatus())) {
            throw new TransferConflictException("车辆当前工位已不是故障状态（"
                    + StationDict.stationStatusName(station.getStatus())
                    + "），可能已被处置或故障已解除，请刷新处置台");
        }
        Optional<LineTransfer> already = transferRepository.findBySuccessEpisodeKey(
                RecoveryTxService.episodeKey(taskId, station.getId()));
        if (already.isPresent()) {
            throw new TransferConflictException("该车辆已转至 " + already.get().getToLineNo()
                    + " 线，不能再登记待处置，请刷新查看最新去向");
        }
        LineTransfer pending = txService.markPending(taskId, operatorOf(request),
                request.getRemark());
        return toTransferVO(pending);
    }

    // ---------------- 组装 ----------------

    private AffectedTaskVO toAffectedVO(Task task, Station faultStation, Line fromLine,
                                        Vehicle vehicle, List<CandidateLineVO> candidates) {
        AffectedTaskVO vo = new AffectedTaskVO();
        vo.setTaskId(task.getId());
        vo.setTaskNo(task.getTaskNo());
        vo.setPlateNo(vehicle == null ? "" : vehicle.getPlateNo());
        vo.setOwnerName(vehicle == null ? "" : vehicle.getOwnerName());
        vo.setVehicleType(vehicle == null ? null : vehicle.getVehicleType());
        vo.setVehicleTypeName(vehicle == null ? "" : StationDict.vehicleTypeName(vehicle.getVehicleType()));
        vo.setStatus(task.getStatus());
        vo.setStatusName(StationDict.taskStatusName(task.getStatus()));
        vo.setRetestTimes(task.getRetestTimes());
        vo.setOriginReportNo(task.getOriginReportNo());
        vo.setFromLineId(fromLine == null ? null : fromLine.getId());
        vo.setFromLineNo(fromLine == null ? "" : fromLine.getLineNo());
        vo.setFromLineName(fromLine == null ? "" : fromLine.getLineName());
        vo.setFaultStationId(faultStation.getId());
        vo.setFaultStationType(faultStation.getStationType());
        vo.setFaultStationName(faultStation.getStationName());
        vo.setFaultSeqNo(faultStation.getSeqNo());
        vo.setTotalCount(StationDict.STATION_ORDER.size());
        vo.setCheckDate(task.getCheckDate());
        vo.setDriverName(task.getDriverId() == null ? "" : driverRepository.findById(task.getDriverId())
                .map(Driver::getDriverName).orElse("未派单"));

        List<RecordVO> records = new ArrayList<>();
        for (TaskStationRecord record : recordRepository.findByTaskIdOrderBySeqNoAsc(task.getId())) {
            RecordVO rvo = new RecordVO();
            rvo.setId(record.getId());
            rvo.setTaskId(record.getTaskId());
            rvo.setStationId(record.getStationId());
            rvo.setStationType(record.getStationType());
            rvo.setStationName(StationDict.stationName(record.getStationType()));
            rvo.setSeqNo(record.getSeqNo());
            rvo.setItemName(record.getItemName());
            rvo.setResult(record.getResult());
            rvo.setResultName("PASS".equals(record.getResult()) ? "合格" : "不合格");
            rvo.setValueText(record.getValueText());
            rvo.setOperatorName(record.getOperatorName());
            rvo.setRecordTime(record.getRecordTime());
            records.add(rvo);
        }
        vo.setRecords(records);
        vo.setFinishedCount(records.size());
        vo.setCandidates(candidates);

        // 本处置幕是否已有成功转线（后来值班人员打开时直接看到最新去向）
        transferRepository.findBySuccessEpisodeKey(
                RecoveryTxService.episodeKey(task.getId(), faultStation.getId()))
                .ifPresent(done -> {
                    vo.setResolved(true);
                    vo.setResolvedToLineId(done.getToLineId());
                    vo.setResolvedToLineNo(done.getToLineNo());
                    vo.setResolvedStationId(done.getTargetStationId());
                    vo.setResolvedStationName(stationRepository.findById(
                            Optional.ofNullable(done.getTargetStationId()).orElse(-1L))
                            .map(Station::getStationName).orElse(""));
                });
        return vo;
    }

    private TransferVO toTransferVO(LineTransfer t) {
        TransferVO vo = new TransferVO();
        vo.setId(t.getId());
        vo.setTaskId(t.getTaskId());
        vo.setTaskNo(t.getTaskNo());
        vo.setPlateNo(t.getPlateNo());
        vo.setFromLineId(t.getFromLineId());
        vo.setFromLineNo(t.getFromLineNo());
        vo.setFaultStationId(t.getFaultStationId());
        vo.setFaultStationType(t.getFaultStationType());
        vo.setFaultStationName(StationDict.stationName(t.getFaultStationType()));
        vo.setFaultSeqNo(t.getFaultSeqNo());
        vo.setToLineId(t.getToLineId());
        vo.setToLineNo(t.getToLineNo());
        vo.setTargetStationId(t.getTargetStationId());
        if (t.getTargetStationId() != null) {
            vo.setTargetStationName(stationRepository.findById(t.getTargetStationId())
                    .map(Station::getStationName).orElse(""));
        }
        vo.setResult(t.getResult());
        vo.setResultName(switch (t.getResult()) {
            case "SUCCESS" -> "转线成功";
            case "FAILED" -> "尝试失败";
            default -> "保持待处置";
        });
        vo.setFailReason(t.getFailReason());
        vo.setOperatorName(t.getOperatorName());
        vo.setTransferTime(t.getTransferTime());
        return vo;
    }

    private Map<Long, Long> loadByLine() {
        return taskRepository.findAllByOrderByIdAsc().stream()
                .filter(t -> "CHECKING".equals(t.getStatus()) || "RETEST".equals(t.getStatus()))
                .collect(Collectors.groupingBy(Task::getLineId, Collectors.counting()));
    }

    private static String operatorOf(TransferConfirmRequest request) {
        if (request.getOperatorName() == null || request.getOperatorName().isBlank()) {
            return "值班员";
        }
        return request.getOperatorName().trim();
    }
}
