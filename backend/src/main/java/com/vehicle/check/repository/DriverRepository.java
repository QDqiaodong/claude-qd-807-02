package com.vehicle.check.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vehicle.check.domain.Driver;

public interface DriverRepository extends JpaRepository<Driver, Long> {

    List<Driver> findAllByOrderByIdAsc();

    List<Driver> findByStatusOrderByIdAsc(String status);
}
