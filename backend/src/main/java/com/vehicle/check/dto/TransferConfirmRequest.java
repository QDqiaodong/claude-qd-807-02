package com.vehicle.check.dto;

import lombok.Data;

/**
 * 跨线恢复确认请求
 */
@Data
public class TransferConfirmRequest {

    /** 目标检测线 id（为空表示放弃转线、保持待处置） */
    private Long targetLineId;

    /** 处置值班人员 */
    private String operatorName;

    /** 备注（保持待处置时可填故障解除后的打算） */
    private String remark;
}
