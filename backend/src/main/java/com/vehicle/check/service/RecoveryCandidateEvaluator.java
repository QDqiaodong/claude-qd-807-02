package com.vehicle.check.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.vehicle.check.common.StationDict;
import com.vehicle.check.domain.Device;
import com.vehicle.check.domain.Line;
import com.vehicle.check.domain.Station;
import com.vehicle.check.domain.Task;
import com.vehicle.check.dto.CandidateLineVO;
import com.vehicle.check.dto.DeviceCheckVO;
import com.vehicle.check.repository.DeviceRepository;
import com.vehicle.check.repository.StationRepository;

/**
 * 候选检测线评估。硬性闸门（缺一不可）：
 * <ol>
 *   <li>检测线运行中；</li>
 *   <li>承接该车辆类型；</li>
 *   <li>从故障工位起的剩余工位顺序与故障线逐位一致；</li>
 *   <li>接续工位实时空闲（IDLE），确认瞬间仍要再复核；</li>
 *   <li>每个剩余工位的设备均适配且标定有效（超期/标定不合格直接排除，负载再低也不推荐）。</li>
 * </ol>
 * 负载（接续工位排队数、整线在检数）只用于在全部合格的候选之间排序，不能凌驾任何硬闸门。
 */
@Component
public class RecoveryCandidateEvaluator {

    private final StationRepository stationRepository;
    private final DeviceRepository deviceRepository;

    public RecoveryCandidateEvaluator(StationRepository stationRepository,
                                      DeviceRepository deviceRepository) {
        this.stationRepository = stationRepository;
        this.deviceRepository = deviceRepository;
    }

    /**
     * 确认瞬间实时复核：返回 null 表示通过，否则返回人话错误（本次确认不允许落库）。
     */
    public String liveGateError(Station faultStation, Line targetLine, String vehicleType) {
        if (!"RUNNING".equals(targetLine.getStatus())) {
            return "目标线「" + targetLine.getLineName() + "（" + targetLine.getLineNo()
                    + "）」当前为停用状态，本次转线未生效";
        }
        if (!acceptsVehicle(targetLine, vehicleType)) {
            return "目标线「" + targetLine.getLineNo() + "」不承接车辆类型「"
                    + StationDict.vehicleTypeName(vehicleType) + "」，本次转线未生效";
        }
        if (!sameRemainingSequence(targetLine, faultStation.getSeqNo())) {
            return "目标线「" + targetLine.getLineNo()
                    + "」剩余工位顺序已变化，与故障前检测链不一致，本次转线未生效";
        }
        Station target = targetStation(targetLine, faultStation.getSeqNo());
        if (target == null) {
            return "目标线缺少第 " + faultStation.getSeqNo() + " 接续工位，本次转线未生效";
        }
        if (!"IDLE".equals(target.getStatus())) {
            return "目标线接续工位「" + target.getStationName() + "」在确认瞬间已变为「"
                    + StationDict.stationStatusName(target.getStatus()) + "」，本次转线未生效，请刷新后重选";
        }
        DeviceGateResult gate = checkDevices(targetLine, faultStation.getSeqNo(), deviceMap(), LocalDate.now());
        if (!gate.valid) {
            return "目标线「" + targetLine.getLineNo() + "」存在标定无效设备："
                    + String.join("；", gate.overdueDesc) + "，本次转线未生效";
        }
        return null;
    }

    public Long targetStationId(Line line, int seqNo) {
        Station station = targetStation(line, seqNo);
        return station == null ? -1L : station.getId();
    }

    /**
     * 评估全部候选线（含排除原因），eligible 的按 接续工位排队 → 整线负载 → 线id 排序并标记推荐。
     */
    public List<CandidateLineVO> evaluateAll(Task task, Station faultStation, String vehicleType,
                                             List<Line> lines, Map<Long, Long> queueMap,
                                             Map<Long, Long> lineLoadMap,
                                             Map<Long, Device> deviceMap, LocalDate today) {
        List<CandidateLineVO> result = new ArrayList<>();
        for (Line line : lines) {
            if (line.getId().equals(task.getLineId())) {
                continue;
            }
            result.add(evaluate(line, faultStation, vehicleType, queueMap, lineLoadMap, deviceMap, today));
        }
        result.sort((a, b) -> {
            if (a.isEligible() != b.isEligible()) {
                return a.isEligible() ? -1 : 1;
            }
            if (a.isEligible()) {
                int byQueue = Long.compare(a.getTargetQueueCount(), b.getTargetQueueCount());
                if (byQueue != 0) {
                    return byQueue;
                }
                int byLoad = Long.compare(a.getLineLoad(), b.getLineLoad());
                if (byLoad != 0) {
                    return byLoad;
                }
            }
            return Long.compare(a.getLineId(), b.getLineId());
        });
        boolean first = true;
        int score = 0;
        for (CandidateLineVO vo : result) {
            if (vo.isEligible()) {
                vo.setRankScore(score++);
                if (first) {
                    vo.setRecommended(true);
                    vo.getReasons().add("系统推荐：在所有硬闸门满足的候选中，接续工位排队最短、整线负载最低");
                    first = false;
                } else {
                    vo.getReasons().add("可承接（备选）：硬闸门全部满足，负载高于推荐线");
                }
            }
        }
        return result;
    }

    private CandidateLineVO evaluate(Line line, Station faultStation, String vehicleType,
                                     Map<Long, Long> queueMap, Map<Long, Long> lineLoadMap,
                                     Map<Long, Device> deviceMap, LocalDate today) {
        CandidateLineVO vo = new CandidateLineVO();
        vo.setLineId(line.getId());
        vo.setLineNo(line.getLineNo());
        vo.setLineName(line.getLineName());
        vo.setLineType(line.getLineType());
        vo.setLineTypeName(StationDict.lineTypeName(line.getLineType()));
        vo.setLineStatus(line.getStatus());
        vo.setLineStatusName(StationDict.lineStatusName(line.getStatus()));
        vo.setAcceptVehicleTypes(line.getAcceptVehicleTypes());
        vo.setAcceptVehicleTypeNames(describeVehicleTypes(line.getAcceptVehicleTypes()));
        vo.setLineLoad(lineLoadMap.getOrDefault(line.getId(), 0L));

        List<String> rejects = new ArrayList<>();
        boolean gate = true;

        if (!"RUNNING".equals(line.getStatus())) {
            gate = false;
            rejects.add("检测线已停用，不能接车");
        }

        boolean accepted = acceptsVehicle(line, vehicleType);
        vo.setVehicleAccepted(accepted);
        if (!accepted) {
            gate = false;
            rejects.add("不承接车辆类型「" + StationDict.vehicleTypeName(vehicleType)
                    + "」，该线仅承接 " + vo.getAcceptVehicleTypeNames());
        }

        boolean seqMatched = sameRemainingSequence(line, faultStation.getSeqNo());
        vo.setSeqMatched(seqMatched);
        if (!seqMatched) {
            gate = false;
            rejects.add("剩余工位顺序不匹配：自第 " + faultStation.getSeqNo() + " 工位「"
                    + faultStation.getStationName() + "」起缺少或错位 "
                    + describeMissingSequence(line, faultStation.getSeqNo()));
        }

        Station target = targetStation(line, faultStation.getSeqNo());
        if (target != null) {
            vo.setTargetStationId(target.getId());
            vo.setTargetStationStatus(target.getStatus());
            vo.setTargetStationStatusName(StationDict.stationStatusName(target.getStatus()));
            long targetQueue = queueMap.getOrDefault(target.getId(), 0L);
            vo.setTargetQueueCount(targetQueue);
            if ("FAULT".equals(target.getStatus())) {
                gate = false;
                rejects.add("接续工位「" + target.getStationName() + "」本身处于故障状态");
            } else if ("STOPPED".equals(target.getStatus())) {
                gate = false;
                rejects.add("接续工位「" + target.getStationName() + "」已停用");
            } else if ("OCCUPIED".equals(target.getStatus())) {
                gate = false;
                rejects.add("接续工位「" + target.getStationName() + "」实时被占用（其后 "
                        + targetQueue + " 台在排队）");
            }
        } else if (seqMatched) {
            gate = false;
            rejects.add("缺少第 " + faultStation.getSeqNo() + " 接续工位");
        }

        DeviceGateResult deviceGate = buildDeviceChecks(line, faultStation.getSeqNo(),
                deviceMap, today, vo);
        vo.setDevicesValid(deviceGate.valid);
        if (!deviceGate.valid) {
            gate = false;
            for (String desc : deviceGate.overdueDesc) {
                rejects.add("设备适配/标定不通过：" + desc + "（负载再低也不得承接）");
            }
        }

        vo.setEligible(gate);
        if (gate) {
            int remaining = StationDict.STATION_ORDER.size() - faultStation.getSeqNo() + 1;
            vo.getReasons().add("检测线运行中，且承接「" + StationDict.vehicleTypeName(vehicleType) + "」");
            vo.getReasons().add("剩余 " + remaining + " 个工位类型与顺序逐位一致，已完成工位不重做");
            vo.getReasons().add("接续工位「" + (target == null ? "" : target.getStationName())
                    + "」实时空闲，工位后排队 " + vo.getTargetQueueCount() + " 台");
            vo.getReasons().add("剩余工位设备全部适配且标定有效");
            vo.getReasons().add("实时负载：接续排队 " + vo.getTargetQueueCount() + " 台 / 整线在检 "
                    + vo.getLineLoad() + " 台（仅在硬闸门全部通过后才参与排序）");
        } else {
            vo.getReasons().addAll(rejects);
        }
        return vo;
    }

    private DeviceGateResult buildDeviceChecks(Line line, int fromSeqNo, Map<Long, Device> deviceMap,
                                               LocalDate today, CandidateLineVO vo) {
        DeviceGateResult result = new DeviceGateResult();
        for (Station station : stationRepository.findByLineIdOrderBySeqNoAsc(line.getId())) {
            if (station.getSeqNo() < fromSeqNo) {
                continue;
            }
            DeviceCheckVO dc = new DeviceCheckVO();
            dc.setSeqNo(station.getSeqNo());
            dc.setStationType(station.getStationType());
            dc.setStationName(station.getStationName());
            dc.setStationStatus(station.getStatus());
            dc.setStationStatusName(StationDict.stationStatusName(station.getStatus()));
            Device device = station.getDeviceId() == null ? null : deviceMap.get(station.getDeviceId());
            if (device == null) {
                boolean needDevice = StationDict.isKeyItem(station.getStationType())
                        || "APPEARANCE".equals(station.getStationType());
                dc.setLevel("NONE");
                dc.setLevelName("无设备");
                dc.setValid(!needDevice);
                if (needDevice) {
                    result.valid = false;
                    result.overdueDesc.add("第 " + station.getSeqNo() + " 工位「" + station.getStationName()
                            + "」未配置检测设备");
                }
            } else {
                dc.setDeviceId(device.getId());
                dc.setDeviceName(device.getDeviceName());
                dc.setModel(device.getModel());
                dc.setNextCalDate(device.getNextCalDate());
                long remain = ChronoUnit.DAYS.between(today, device.getNextCalDate());
                dc.setRemainDays(remain);
                boolean overdue = device.getNextCalDate().isBefore(today)
                        || "FAIL".equals(device.getCalResult());
                boolean due = !overdue && remain <= StationDict.CAL_DUE_DAYS;
                if (overdue) {
                    dc.setLevel("OVERDUE");
                    dc.setLevelName("标定已超期");
                    dc.setValid(false);
                    result.valid = false;
                    String why = "FAIL".equals(device.getCalResult())
                            ? "标定结论不合格"
                            : "标定已超期 " + (-remain) + " 天（应于 " + device.getNextCalDate() + " 前完成）";
                    result.overdueDesc.add("第 " + station.getSeqNo() + " 工位「" + station.getStationName()
                            + "」" + device.getDeviceName() + "（" + device.getModel() + "）" + why);
                } else if (due) {
                    dc.setLevel("DUE");
                    dc.setLevelName("标定临期（仍有效）");
                    dc.setValid(true);
                } else {
                    dc.setLevel("OK");
                    dc.setLevelName("标定有效");
                    dc.setValid(true);
                }
            }
            vo.getDeviceChecks().add(dc);
        }
        return result;
    }

    private DeviceGateResult checkDevices(Line line, int fromSeqNo, Map<Long, Device> deviceMap,
                                          LocalDate today) {
        DeviceGateResult result = new DeviceGateResult();
        for (Station station : stationRepository.findByLineIdOrderBySeqNoAsc(line.getId())) {
            if (station.getSeqNo() < fromSeqNo) {
                continue;
            }
            Device device = station.getDeviceId() == null ? null : deviceMap.get(station.getDeviceId());
            if (device == null) {
                boolean needDevice = StationDict.isKeyItem(station.getStationType())
                        || "APPEARANCE".equals(station.getStationType());
                if (needDevice) {
                    result.valid = false;
                    result.overdueDesc.add("第 " + station.getSeqNo() + " 工位「" + station.getStationName()
                            + "」未配置检测设备");
                }
                continue;
            }
            if (device.getNextCalDate().isBefore(today) || "FAIL".equals(device.getCalResult())) {
                result.valid = false;
                result.overdueDesc.add("第 " + station.getSeqNo() + " 工位「" + station.getStationName()
                        + "」" + device.getDeviceName());
            }
        }
        return result;
    }

    private static class DeviceGateResult {
        private boolean valid = true;
        private final List<String> overdueDesc = new ArrayList<>();
    }

    private boolean sameRemainingSequence(Line line, int fromSeqNo) {
        List<Station> mine = stationRepository.findByLineIdOrderBySeqNoAsc(line.getId());
        for (int seq = fromSeqNo; seq <= StationDict.STATION_ORDER.size(); seq++) {
            String expectType = StationDict.STATION_ORDER.get(seq - 1);
            final int seqKey = seq;
            Station found = mine.stream().filter(s -> s.getSeqNo().equals(seqKey)).findFirst().orElse(null);
            if (found == null || !expectType.equals(found.getStationType())) {
                return false;
            }
        }
        return true;
    }

    private String describeMissingSequence(Line line, int fromSeqNo) {
        List<Station> mine = stationRepository.findByLineIdOrderBySeqNoAsc(line.getId());
        List<String> missing = new ArrayList<>();
        for (int seq = fromSeqNo; seq <= StationDict.STATION_ORDER.size(); seq++) {
            String expectType = StationDict.STATION_ORDER.get(seq - 1);
            final int s = seq;
            Station found = mine.stream().filter(x -> x.getSeqNo().equals(s)).findFirst().orElse(null);
            if (found == null || !expectType.equals(found.getStationType())) {
                missing.add(StationDict.stationName(expectType));
            }
        }
        return String.join("、", missing);
    }

    private Station targetStation(Line line, int seqNo) {
        return stationRepository.findByLineIdOrderBySeqNoAsc(line.getId()).stream()
                .filter(s -> s.getSeqNo().equals(seqNo))
                .findFirst().orElse(null);
    }

    private Map<Long, Device> deviceMap() {
        return deviceRepository.findAll().stream()
                .collect(Collectors.toMap(Device::getId, d -> d, (a, b) -> a));
    }

    private static String describeVehicleTypes(String acceptTypes) {
        if (acceptTypes == null || acceptTypes.isBlank()) {
            return "无";
        }
        return java.util.Arrays.stream(acceptTypes.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(StationDict::vehicleTypeName)
                .collect(Collectors.joining("、"));
    }

    private static boolean acceptsVehicle(Line line, String vehicleType) {
        if (vehicleType == null || vehicleType.isBlank()) {
            return false;
        }
        Set<String> accepts = line.getAcceptVehicleTypes() == null ? Set.of()
                : Set.of(line.getAcceptVehicleTypes().split(",")).stream()
                .map(String::trim).collect(Collectors.toSet());
        return accepts.contains(vehicleType);
    }
}
