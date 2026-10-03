package com.vehicle.check.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 处置台上一辆「卡在故障工位、仍在检测中」的受影响车辆。
 */
@Data
public class AffectedTaskVO {

    private Long taskId;
    private String taskNo;
    private String plateNo;
    private String ownerName;
    private String vehicleType;
    private String vehicleTypeName;
    private String status;
    private String statusName;
    private Integer retestTimes;
    /** 复检任务关联的原报告编号（转线前后都必须保留） */
    private String originReportNo;

    private Long fromLineId;
    private String fromLineNo;
    private String fromLineName;

    private Long faultStationId;
    private String faultStationType;
    private String faultStationName;
    private Integer faultSeqNo;
    /** 已完成工位数 / 总工位数 */
    private int finishedCount;
    private int totalCount;

    private String driverName;
    private LocalDate checkDate;

    /** 已完成工位结果（原样保留的检测链，含不合格项） */
    private List<RecordVO> records = new ArrayList<>();

    /** 该车评估出的候选线 */
    private List<CandidateLineVO> candidates = new ArrayList<>();

    /** 本处置幕是否已经被成功转线（两人同时处置时，后来者看到的是最新去向） */
    private boolean resolved;
    private Long resolvedToLineId;
    private String resolvedToLineNo;
    private Long resolvedStationId;
    private String resolvedStationName;
}
