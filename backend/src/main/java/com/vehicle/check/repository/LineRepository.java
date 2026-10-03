package com.vehicle.check.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.vehicle.check.domain.Line;

public interface LineRepository extends JpaRepository<Line, Long> {

    List<Line> findAllByOrderByIdAsc();

    List<Line> findByStatus(String status);
}
