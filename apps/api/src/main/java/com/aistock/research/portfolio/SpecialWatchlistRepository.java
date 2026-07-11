package com.aistock.research.portfolio;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpecialWatchlistRepository extends JpaRepository<SpecialWatchlistEntity, String> {

    List<SpecialWatchlistEntity> findAllByOrderByUpdatedAtDesc();
}
