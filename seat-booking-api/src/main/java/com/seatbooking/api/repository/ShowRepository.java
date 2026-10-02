package com.seatbooking.api.repository;

import com.seatbooking.common.entity.ShowEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ShowRepository extends JpaRepository<ShowEntity, UUID> {
}
