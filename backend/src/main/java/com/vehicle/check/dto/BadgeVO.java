package com.vehicle.check.dto;

import lombok.Data;

/**
 * 判定徽章：尾气/制动/灯光/外观/底盘 各一枚，合格绿、不合格红
 */
@Data
public class BadgeVO {

    private String code;
    private String name;
    private String result;
    private String valueText;
    private Boolean keyItem;
}
