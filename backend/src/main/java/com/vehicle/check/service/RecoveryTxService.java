package com.vehicle.check.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.vehicle.check.common.BizException;
import com.vehicle.check.common.StationDict;
import com.vehicle.check.common.TransferConflictException;
import com.vehicle.check.domain.Line;
import com.vehicle.check.domain.LineTransfer;
import com.vehicle.check.domain.Station;
import com.vehicle.check.domain.Task;
import com.vehicle.check.domain.Vehicle;
import com.vehicle.check.repository.LineRepository;
import com.vehicle.check.repository.LineTransferRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.TaskRepository;
import com.vehicle.check.repository.VehicleRepository;

/**
 * 跨线恢复的事务边界：所有「状态回写」必须在同一个数据库事务内完成，
 * 只改任务指针 / 页面线号而不做工位占位与处置落库的做法在这里被整体封死。
 */
@Service
public class RecoveryTxService {

    private final TaskRepository taskRepository;
    private final StationRepository stationRepository;
    private final LineRepository lineRepository;
    private final LineTransferRepository transferRepository;
    private final VehicleRepository vehicleRepository;
    private final RecoveryCandidateEvaluator evaluator;

    public RecoveryTxService(TaskRepository taskRepository, StationRepository stationRepository,
                             LineRepository lineRepository, LineTransferRepository transferRepository,
                             VehicleRepository vehicleRepository, RecoveryCandidateEvaluator evaluator) {
        this.taskRepository = taskRepository;
        this.stationRepository = stationRepository;
        this.lineRepository = lineRepository;
        this.transferRepository = transferRepository;
        this.vehicleRepository = vehicleRepository;
        this.evaluator = evaluator;
    }

    /**
     * 确认转线。同一时刻只允许一次成功：
     * ① 行锁锁任务（两人同时处置同一车辆 → 后来者拿到最新状态）；
     * ② 行锁锁目标工位 + 条件 UPDATE（仅 IDLE 可占成 OCCUPIED）；
     * ③ SUCCESS 处置记录同幕唯一索引兜底。
     * 任务指针、工位占用、处置记录同一事务提交，要么全成要么全不动。
     */
    @Transactional
    public LineTransfer transfer(Long taskId, Long targetLineId, String operatorName) {
        Task task = taskRepository.findByIdForUpdate(taskId)
                .orElseThrow(() -> new BizException("检测任务不存在：id=" + taskId));
        if (!"CHECKING".equals(task.getStatus()) && !"RETEST".equals(task.getStatus())) {
            throw new BizException("任务 " + task.getTaskNo() + " 已结束（"
                    + StationDict.taskStatusName(task.getStatus()) + "），无需跨线恢复");
        }

        Station faultStation = stationRepository.findById(task.getCurrentStationId())
                .orElseThrow(() -> new BizException("任务当前工位数据丢失"));

        // 并发兜底（关键顺序）：后拿到行锁的一方此时读到的是已迁移后的新指针，
        // 因此先查该车最近一次成功转线；若当前指针正落在那次转线的接续工位上，
        // 说明另一值班人员已处置成功，直接告知最新去向。
        Optional<LineTransfer> latestSuccess =
                transferRepository.findFirstByTaskIdAndResultOrderByIdDesc(taskId, "SUCCESS");
        if (latestSuccess.isPresent()) {
            LineTransfer last = latestSuccess.get();
            if (last.getTargetStationId() != null
                    && last.getTargetStationId().equals(task.getCurrentStationId())
                    && !"FAULT".equals(faultStation.getStatus())) {
                throw new TransferConflictException("该车辆本工位故障已被另一名值班人员处置：已转至 "
                        + last.getToLineNo() + " 线「" + faultStation.getStationName() + "」工位继续检测，"
                        + "本次操作未生效，请按最新去向跟进");
            }
            // 车辆当前又停在一个新的故障工位上（接续线也坏了）→ 属于下一处置幕，允许再次转线
        }

        if (!"FAULT".equals(faultStation.getStatus())) {
            throw new TransferConflictException("车辆 " + task.getTaskNo() + " 当前所在工位「"
                    + faultStation.getStationName() + "」已不是故障状态，可能已被其他值班人员转线"
                    + "或故障已解除，请刷新页面查看最新去向");
        }

        String episodeKey = episodeKey(taskId, faultStation.getId());
        Optional<LineTransfer> done = transferRepository.findBySuccessEpisodeKey(episodeKey);
        if (done.isPresent()) {
            LineTransfer t = done.get();
            throw new TransferConflictException("该车辆本工位故障已被处置：已转至 " + t.getToLineNo()
                    + " 线第 " + faultStation.getSeqNo() + " 工位（"
                    + StationDict.stationName(faultStation.getStationType()) + "），无需重复转线");
        }

        Line fromLine = lineRepository.findById(task.getLineId())
                .orElseThrow(() -> new BizException("故障发生线数据丢失"));
        Line targetLine = lineRepository.findById(targetLineId)
                .orElseThrow(() -> new BizException("目标检测线不存在：id=" + targetLineId));

        // 确认瞬间的实时复核：候选资格以「落库这一刻」的数据为准，不相信打开页面时的旧快照
        Vehicle vehicle = vehicleRepository.findById(task.getVehicleId()).orElse(null);
        String vehicleType = vehicle == null ? "" : vehicle.getVehicleType();
        String gateError = evaluator.liveGateError(faultStation, targetLine, vehicleType);
        if (gateError != null) {
            throw new TransferConflictException(gateError);
        }

        Station targetStation = stationRepository
                .findByIdForUpdate(evaluator.targetStationId(targetLine, faultStation.getSeqNo()))
                .orElseThrow(() -> new TransferConflictException("目标线接续工位已不存在，请刷新后重选"));

        // 原子占位：确认瞬间工位被别的车占用 / 被停用 / 转故障时，受影响行数为 0
        int claimed;
        try {
            claimed = stationRepository.claimStation(targetStation.getId());
        } catch (DataIntegrityViolationException e) {
            throw new TransferConflictException("目标工位「" + targetStation.getStationName()
                    + "」刚刚发生状态变化，本次转线未生效，请刷新后重选");
        }
        if (claimed == 0) {
            Station fresh = stationRepository.findById(targetStation.getId()).orElse(targetStation);
            throw new TransferConflictException("目标工位「" + fresh.getStationName() + "（"
                    + StationDict.stationStatusName(fresh.getStatus()) + "）」在确认瞬间已被占用或停用，"
                    + "本次转线未生效，请刷新后选择其他目标线");
        }

        // 检测链不换号：只接续目标线同序工位，状态保持在检/复检，originReportNo（复检关联）原样不动
        task.setLineId(targetLine.getId());
        task.setCurrentStationId(targetStation.getId());
        task.setCurrentSeqNo(targetStation.getSeqNo());
        taskRepository.save(task);
        // 旧故障工位保持 FAULT，不回写 IDLE —— 它本来就还坏着，由设备维修解除故障后再恢复

        LineTransfer transfer = baseRecord(task, vehicle, fromLine, faultStation, operatorName);
        transfer.setToLineId(targetLine.getId());
        transfer.setToLineNo(targetLine.getLineNo());
        transfer.setTargetStationId(targetStation.getId());
        transfer.setResult("SUCCESS");
        transfer.setSuccessEpisodeKey(episodeKey);
        try {
            return transferRepository.save(transfer);
        } catch (DataIntegrityViolationException e) {
            // 同幕唯一索引：并发下另一个事务已成功 → 回滚本事务（含占位），由上层记 FAILED 痕迹
            throw new TransferConflictException("另一名值班人员已先行完成转线，本次操作未生效，请刷新查看最新去向");
        }
    }

    /** 无可用目标：保持待处置，保留故障前进度，留一条处置痕迹 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LineTransfer markPending(Long taskId, String operatorName, String remark) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new BizException("检测任务不存在：id=" + taskId));
        Station faultStation = stationRepository.findById(task.getCurrentStationId())
                .orElseThrow(() -> new BizException("任务当前工位数据丢失"));
        Line fromLine = lineRepository.findById(task.getLineId())
                .orElseThrow(() -> new BizException("故障发生线数据丢失"));
        Vehicle vehicle = vehicleRepository.findById(task.getVehicleId()).orElse(null);

        LineTransfer transfer = baseRecord(task, vehicle, fromLine, faultStation, operatorName);
        transfer.setResult("PENDING");
        transfer.setFailReason(remark == null || remark.isBlank()
                ? "暂无可用目标检测线，车辆保持待处置并保留故障前进度；故障解除后可回原线继续"
                : remark);
        return transferRepository.save(transfer);
    }

    /** 转线尝试失败留痕（与主事务隔离：主事务回滚了，痕迹也要留下） */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LineTransfer recordFailed(Long taskId, Long targetLineId, String operatorName, String reason) {
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            return null;
        }
        Vehicle vehicle = vehicleRepository.findById(task.getVehicleId()).orElse(null);

        // 并发失败方读到的任务指针可能已被成功者迁到新线：故障线/故障工位以最近一次成功转线的「来源」为准，
        // 若尚无成功记录（如目标工位确认瞬间被占），则用任务当前所在故障工位。
        LineTransfer lastSuccess = transferRepository
                .findFirstByTaskIdAndResultOrderByIdDesc(taskId, "SUCCESS").orElse(null);
        Long fromLineId = lastSuccess != null ? lastSuccess.getFromLineId() : task.getLineId();
        String fromLineNo = lastSuccess != null ? lastSuccess.getFromLineNo() : null;
        Long faultStationId = lastSuccess != null ? lastSuccess.getFaultStationId() : task.getCurrentStationId();
        String faultStationType = lastSuccess != null ? lastSuccess.getFaultStationType() : null;
        Integer faultSeqNo = lastSuccess != null ? lastSuccess.getFaultSeqNo() : task.getCurrentSeqNo();
        Line fromLine = fromLineNo != null ? null : lineRepository.findById(fromLineId).orElse(null);
        Station faultStation = lastSuccess != null ? null
                : stationRepository.findById(task.getCurrentStationId()).orElse(null);

        LineTransfer transfer = new LineTransfer();
        transfer.setTaskId(task.getId());
        transfer.setTaskNo(task.getTaskNo());
        transfer.setPlateNo(vehicle == null ? "" : vehicle.getPlateNo());
        transfer.setFromLineId(fromLineId);
        transfer.setFromLineNo(lastSuccess != null ? fromLineNo
                : (fromLine == null ? "" : fromLine.getLineNo()));
        transfer.setFaultStationId(faultStationId == null ? 0L : faultStationId);
        transfer.setFaultStationType(faultStationType != null ? faultStationType
                : (faultStation == null ? "" : faultStation.getStationType()));
        transfer.setFaultSeqNo(faultSeqNo == null ? task.getCurrentSeqNo() : faultSeqNo);
        transfer.setOperatorName(operatorName);
        transfer.setTransferTime(LocalDateTime.now());
        transfer.setEpisodeKey(episodeKey(taskId,
                faultStationId == null ? (task.getCurrentStationId() == null ? 0L : task.getCurrentStationId())
                        : faultStationId));
        if (targetLineId != null) {
            lineRepository.findById(targetLineId).ifPresent(line -> {
                transfer.setToLineId(line.getId());
                transfer.setToLineNo(line.getLineNo());
            });
        }
        transfer.setResult("FAILED");
        transfer.setFailReason(truncate(reason, 300));
        return transferRepository.save(transfer);
    }

    private LineTransfer baseRecord(Task task, Vehicle vehicle, Line fromLine,
                                    Station faultStation, String operatorName) {
        LineTransfer transfer = new LineTransfer();
        transfer.setTaskId(task.getId());
        transfer.setTaskNo(task.getTaskNo());
        transfer.setPlateNo(vehicle == null ? "" : vehicle.getPlateNo());
        transfer.setFromLineId(fromLine.getId());
        transfer.setFromLineNo(fromLine.getLineNo());
        transfer.setFaultStationId(faultStation.getId());
        transfer.setFaultStationType(faultStation.getStationType());
        transfer.setFaultSeqNo(faultStation.getSeqNo());
        transfer.setEpisodeKey(episodeKey(task.getId(), faultStation.getId()));
        transfer.setOperatorName(operatorName);
        transfer.setTransferTime(LocalDateTime.now());
        return transfer;
    }

    static String episodeKey(Long taskId, Long faultStationId) {
        return taskId + "-" + faultStationId;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
