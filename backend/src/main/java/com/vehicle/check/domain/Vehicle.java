package com.vehicle.check.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.ColumnTransformer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 车辆与送检。
 * <p>
 * 本仓技术标识：ownerIdNo / ownerPhone 两列走 Hibernate {@code @ColumnTransformer}，
 * 库里是 MySQL {@code AES_ENCRYPT} 的 VARBINARY 密文，实体与接口里直接读写明文。
 */
@Data
@Entity
@Table(name = "vc_vehicle")
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plate_no", nullable = false, unique = true)
    private String plateNo;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(name = "owner_id_no")
    @ColumnTransformer(
            read = "CONVERT(AES_DECRYPT(owner_id_no, 'seed_key') USING utf8mb4)",
            write = "AES_ENCRYPT(?, 'seed_key')")
    private String ownerIdNo;

    @Column(name = "owner_phone")
    @ColumnTransformer(
            read = "CONVERT(AES_DECRYPT(owner_phone, 'seed_key') USING utf8mb4)",
            write = "AES_ENCRYPT(?, 'seed_key')")
    private String ownerPhone;

    @Column(name = "vehicle_type", nullable = false)
    private String vehicleType;

    @Column(name = "register_date", nullable = false)
    private LocalDate registerDate;

    @Column(name = "mileage", nullable = false)
    private Integer mileage;

    @Column(name = "fuel_type", nullable = false)
    private String fuelType;

    @Column(name = "submitter", nullable = false)
    private String submitter;

    @Column(name = "appoint_time")
    private LocalDateTime appointTime;

    @Column(name = "create_time")
    private LocalDateTime createTime;
}
