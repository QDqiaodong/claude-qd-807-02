package com.vehicle.check.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 检测任务视图：任务 + 车牌/车主/线号/当前工位名/引车员 + 各工位记录
 */
@Data
public class TaskVO {

    private Long id;
    private String taskNo;
    private Long vehicleId;
    private String plateNo;
    private String ownerName;
    private String vehicleType;
    private String vehicleTypeName;
    private Long lineId;
    private String lineNo;
    private Long currentStationId;
    private String currentStationType;
    private String currentStationName;
    private Integer currentSeqNo;
    private String status;
    private String statusName;
    private Long driverId;
    private String driverName;
    private Integer scheduleHour;
    private LocalDate checkDate;
    private BigDecimal coValue;
    private Integer hcValue;
    private BigDecimal brakeRate;
    private Integer lightIntensity;
    private String chassisDefects;
    private Integer retestTimes;
    /** 复检任务关联的原报告编号（跨线恢复后仍保留） */
    private String originReportNo;

    /** 是否已开始检测（已产生任一工位记录）；未开始才允许改派 */
    private Boolean started;

    /** 当前是否可改派：未开始检测且任务未结束 */
    private Boolean reassignAllowed;

    private LocalDateTime createTime;
    private LocalDateTime finishTime;
    private List<RecordVO> records = new ArrayList<>();
}
