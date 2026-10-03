package com.vehicle.check.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/**
 * 工位检测项录入请求：数值 + 自动判定所需的原始值
 */
@Data
public class StationResultRequest {

    private Long stationId;
    private String operatorName;

    /** 登录：证件是否一致 */
    private Boolean loginMatched;

    /** 外观：缺陷项数 */
    private Integer appearanceDefects;

    /** 尾气：CO 值(%) / HC 值(ppm) */
    private BigDecimal coValue;
    private Integer hcValue;

    /** 制动：制动率(%)，实体里是百分比，库里存小数 */
    private BigDecimal brakeRate;

    /** 灯光：发光强度(cd) */
    private Integer lightIntensity;

    /** 底盘：缺陷项列表 */
    private List<String> chassisDefects = new ArrayList<>();

    /** 总检：复核意见 */
    private String finalComment;
}
