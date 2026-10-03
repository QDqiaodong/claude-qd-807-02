package com.vehicle.check.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 跨线恢复候选检测线评估结果。
 * eligible=false 时 reasons 逐条说明排除原因（工位顺序、车型承接、实时占用、设备标定……），
 * 页面必须明确展示「为什么推荐 / 为什么排除」，而不是只给一个灰色按钮。
 */
@Data
public class CandidateLineVO {

    private Long lineId;
    private String lineNo;
    private String lineName;
    private String lineType;
    private String lineTypeName;
    private String lineStatus;
    private String lineStatusName;
    private String acceptVehicleTypes;
    private String acceptVehicleTypeNames;
    private boolean vehicleAccepted;

    /** 候选线与故障线工位顺序是否一致（按剩余工位的类型与顺序逐位比对） */
    private boolean seqMatched;

    /** 接续工位（候选线上同 seqNo 的工位）实时状态 */
    private Long targetStationId;
    private String targetStationStatus;
    private String targetStationStatusName;
    /** 接续工位之后的排队车辆数 */
    private Long targetQueueCount;
    /** 整条候选线当前在检车辆数（负载） */
    private Long lineLoad;

    /** 剩余各工位的设备适配 / 标定明细 */
    private List<DeviceCheckVO> deviceChecks = new ArrayList<>();
    /** 设备适配是否全部通过（无超期/标定不合格设备） */
    private boolean devicesValid;

    /** 硬性闸门是否全部通过 */
    private boolean eligible;
    /** 是否系统首推（eligible 中接续排队最短、负载最低） */
    private boolean recommended;
    /** 推荐/排除理由（人话，逐条） */
    private List<String> reasons = new ArrayList<>();
    /** 排序分：仅在 eligible 时有意义，越小越优先 */
    private int rankScore;
}
