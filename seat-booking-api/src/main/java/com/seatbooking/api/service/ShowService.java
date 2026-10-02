package com.seatbooking.api.service;

import com.seatbooking.api.repository.SeatRepository;
import com.seatbooking.api.repository.ShowRepository;
import com.seatbooking.common.dto.CreateShowRequest;
import com.seatbooking.common.dto.ShowResponse;
import com.seatbooking.common.entity.SeatEntity;
import com.seatbooking.common.entity.ShowEntity;
import com.seatbooking.api.exception.ShowNotFoundException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class ShowService {

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final MeterRegistry meterRegistry;

    // Live gauges per show
    private final Map<String, AtomicInteger> availableGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> confirmedGauges = new ConcurrentHashMap<>();

    public ShowService(ShowRepository showRepository, SeatRepository seatRepository,
                       MeterRegistry meterRegistry) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ShowResponse createShow(CreateShowRequest request) {
        ShowEntity show = ShowEntity.builder()
                .name(request.getName())
                .totalSeats(request.getSeats().size())
                .price(request.getPrice())
                .build();

        show = showRepository.save(show);

        // Bulk create seat records
        List<SeatEntity> seats = new ArrayList<>();
        for (String seatId : request.getSeats()) {
            seats.add(SeatEntity.builder()
                    .showId(show.getShowId())
                    .seatId(seatId)
                    .status("available")
                    .build());
        }
        seatRepository.saveAll(seats);

        // Register metrics gauges for this show
        registerShowGauges(show.getShowId().toString(), request.getSeats().size());

        log.info("Show created: id={} name={} seats={}", show.getShowId(), show.getName(), show.getTotalSeats());

        return buildShowResponse(show, seats);
    }

    public ShowResponse getShow(UUID showId) {
        ShowEntity show = showRepository.findById(showId)
                .orElseThrow(() -> new ShowNotFoundException(showId));

        List<SeatEntity> seats = seatRepository.findByShowId(showId);
        return buildShowResponse(show, seats);
    }

    public List<Map<String, Object>> getAllShows() {
        return showRepository.findAll().stream().map(show -> {
            Map<String, Object> map = new HashMap<>();
            map.put("showId", show.getShowId().toString());
            map.put("name", show.getName());
            return map;
        }).toList();
    }

    // --- Private helpers ---

    private ShowResponse buildShowResponse(ShowEntity show, List<SeatEntity> seats) {
        Map<String, String> seatMap = new LinkedHashMap<>();
        int available = 0, held = 0, confirmed = 0;

        for (SeatEntity seat : seats) {
            seatMap.put(seat.getSeatId(), seat.getStatus());
            switch (seat.getStatus()) {
                case "available" -> available++;
                case "held" -> held++;
                case "confirmed" -> confirmed++;
            }
        }

        int total = available + held + confirmed;

        // Assert reconciliation invariant
        if (total != show.getTotalSeats()) {
            log.warn("RECONCILIATION DRIFT: show={} expected={} actual={} (avail={} held={} conf={})",
                    show.getShowId(), show.getTotalSeats(), total, available, held, confirmed);
        }

        // Update gauges
        updateShowGauges(show.getShowId().toString(), available, confirmed);

        return ShowResponse.builder()
                .showId(show.getShowId().toString())
                .name(show.getName())
                .totalSeats(show.getTotalSeats())
                .price(show.getPrice())
                .perUserLimit(show.getPerUserLimit())
                .seatMap(seatMap)
                .counts(ShowResponse.SeatCounts.builder()
                        .available(available)
                        .held(held)
                        .confirmed(confirmed)
                        .total(total)
                        .build())
                .build();
    }

    private void registerShowGauges(String showId, int totalSeats) {
        AtomicInteger availGauge = new AtomicInteger(totalSeats);
        AtomicInteger confGauge = new AtomicInteger(0);
        availableGauges.put(showId, availGauge);
        confirmedGauges.put(showId, confGauge);

        Gauge.builder("seats.available", availGauge, AtomicInteger::get)
                .tag("show_id", showId)
                .register(meterRegistry);
        Gauge.builder("seats.confirmed", confGauge, AtomicInteger::get)
                .tag("show_id", showId)
                .register(meterRegistry);
    }

    private void updateShowGauges(String showId, int available, int confirmed) {
        AtomicInteger availGauge = availableGauges.get(showId);
        AtomicInteger confGauge = confirmedGauges.get(showId);
        if (availGauge != null) availGauge.set(available);
        if (confGauge != null) confGauge.set(confirmed);
    }
}
