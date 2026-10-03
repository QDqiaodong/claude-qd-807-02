package com.vehicle.check.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vehicle.check.domain.Vehicle;

public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    Optional<Vehicle> findByPlateNo(String plateNo);

    List<Vehicle> findAllByOrderByIdAsc();

    List<Vehicle> findByVehicleTypeOrderByIdAsc(String vehicleType);
}
