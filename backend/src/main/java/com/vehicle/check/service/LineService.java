package com.vehicle.check.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.vehicle.check.common.BizException;
import com.vehicle.check.common.StationDict;
import com.vehicle.check.domain.Device;
import com.vehicle.check.domain.Line;
import com.vehicle.check.domain.Station;
import com.vehicle.check.dto.StationQueueDTO;
import com.vehicle.check.dto.StationVO;
import com.vehicle.check.repository.DeviceRepository;
import com.vehicle.check.repository.LineRepository;
import com.vehicle.check.repository.StationRepository;
import com.vehicle.check.repository.TaskRepository;

/**
 * 检测线与工位
 */
@Service
public class LineService {

    private final LineRepository lineRepository;
    private final StationRepository stationRepository;
    private final TaskRepository taskRepository;
    private final DeviceRepository deviceRepository;

    public LineService(LineRepository lineRepository, StationRepository stationRepository,
                       TaskRepository taskRepository, DeviceRepository deviceRepository) {
        this.lineRepository = lineRepository;
        this.stationRepository = stationRepository;
        this.taskRepository = taskRepository;
        this.deviceRepository = deviceRepository;
    }

    public List<Line> listLines() {
        return lineRepository.findAllByOrderByIdAsc();
    }

    /**
     * 底部流水线导航：默认取第一条运行中的检测线的 7 个工位，并带排队车辆数徽章
     */
    public List<StationVO> pipeline() {
        List<Line> lines = lineRepository.findByStatus("RUNNING");
        if (lines.isEmpty()) {
            throw new BizException("暂无运行中的检测线，请先启用一条检测线");
        }
        return stationsOfLine(lines.get(0).getId());
    }

    public List<StationVO> stationsOfLine(Long lineId) {
        Line line = lineRepository.findById(lineId)
                .orElseThrow(() -> new BizException("检测线不存在：id=" + lineId));
        Map<Long, Long> queueMap = taskRepository.countQueueGroupByStation().stream()
                .collect(Collectors.toMap(StationQueueDTO::getStationId, StationQueueDTO::getQueueCount, (a, b) -> a));
        Map<Long, String> deviceMap = deviceRepository.findAll().stream()
                .collect(Collectors.toMap(Device::getId, Device::getDeviceName, (a, b) -> a));

        List<StationVO> result = new ArrayList<>();
        for (Station station : stationRepository.findByLineIdOrderBySeqNoAsc(lineId)) {
            StationVO vo = new StationVO(station.getId(), station.getLineId(), line.getLineNo(),
                    station.getStationType(), station.getStationName(), station.getSeqNo(),
                    station.getStatus(), station.getDeviceId(), station.getRemark());
            vo.setStatusName(StationDict.stationStatusName(station.getStatus()));
            vo.setItemName(StationDict.stationItem(station.getStationType()));
            vo.setQueueCount(queueMap.getOrDefault(station.getId(), 0L));
            vo.setDeviceName(station.getDeviceId() == null ? null : deviceMap.get(station.getDeviceId()));
            result.add(vo);
        }
        return result;
    }

    public List<StationVO> listAllStations() {
        List<StationVO> result = new ArrayList<>();
        for (Line line : lineRepository.findAllByOrderByIdAsc()) {
            result.addAll(stationsOfLine(line.getId()));
        }
        return result;
    }

    public Station updateStationStatus(Long stationId, String status) {
        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new BizException("工位不存在：id=" + stationId));
        if (!List.of("IDLE", "OCCUPIED", "FAULT").contains(status)) {
            throw new BizException("工位状态只能是 空闲(IDLE) / 占用(OCCUPIED) / 故障(FAULT)");
        }
        // 工位故障时允许保留上面的在检车辆：它们正是「跨线恢复处置台」的处置对象；
        // 车辆不会丢——处置台会把任务指针与工位占位原子迁到目标线。故障解除（改回 IDLE）后，
        // 仍在原线等待的车辆可直接回原线继续。
        station.setStatus(status);
        return stationRepository.save(station);
    }

    public Map<String, Long> stationStatusCount() {
        return Map.of(
                "IDLE", stationRepository.countByStatus("IDLE"),
                "OCCUPIED", stationRepository.countByStatus("OCCUPIED"),
                "FAULT", stationRepository.countByStatus("FAULT"));
    }

    /** 工位类型 → 工位（按检测线） */
    public Map<String, Station> stationMapOfLine(Long lineId) {
        return stationRepository.findByLineIdOrderBySeqNoAsc(lineId).stream()
                .collect(Collectors.toMap(Station::getStationType, Function.identity(), (a, b) -> a));
    }
}
