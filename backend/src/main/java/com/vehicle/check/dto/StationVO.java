package com.vehicle.check.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工位视图：工位基础信息 + 排队车辆数（底部流水线徽章用）
 */
@Data
@NoArgsConstructor
public class StationVO {

    private Long id;
    private Long lineId;
    private String lineNo;
    private String stationType;
    private String stationName;
    private Integer seqNo;
    private String status;
    private String statusName;
    private Long deviceId;
    private String deviceName;
    private Long queueCount;
    private String remark;
    private String itemName;

    public StationVO(Long id, Long lineId, String lineNo, String stationType, String stationName,
                     Integer seqNo, String status, Long deviceId, String remark) {
        this.id = id;
        this.lineId = lineId;
        this.lineNo = lineNo;
        this.stationType = stationType;
        this.stationName = stationName;
        this.seqNo = seqNo;
        this.status = status;
        this.deviceId = deviceId;
        this.remark = remark;
    }
}
