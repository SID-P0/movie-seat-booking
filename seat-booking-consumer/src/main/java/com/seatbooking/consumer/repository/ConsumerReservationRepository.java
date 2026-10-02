package com.seatbooking.consumer.repository;

import com.seatbooking.common.entity.ReservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ConsumerReservationRepository extends JpaRepository<ReservationEntity, UUID> {
}
