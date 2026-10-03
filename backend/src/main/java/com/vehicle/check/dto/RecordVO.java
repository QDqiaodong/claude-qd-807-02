package com.vehicle.check.dto;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * 工位流转记录视图
 */
@Data
public class RecordVO {

    private Long id;
    private Long taskId;
    private Long stationId;
    private String stationType;
    private String stationName;
    private Integer seqNo;
    private String itemName;
    private String result;
    private String resultName;
    private String valueText;
    private String operatorName;
    private LocalDateTime recordTime;
}
