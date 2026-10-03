package com.vehicle.check.dto;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 跨线恢复处置记录视图
 */
@Data
public class TransferVO {

    private Long id;
    private Long taskId;
    private String taskNo;
    private String plateNo;
    private Long fromLineId;
    private String fromLineNo;
    private Long faultStationId;
    private String faultStationType;
    private String faultStationName;
    private Integer faultSeqNo;
    private Long toLineId;
    private String toLineNo;
    private Long targetStationId;
    private String targetStationName;
    /** SUCCESS / FAILED / PENDING */
    private String result;
    private String resultName;
    private String failReason;
    private String operatorName;
    private LocalDateTime transferTime;
}
