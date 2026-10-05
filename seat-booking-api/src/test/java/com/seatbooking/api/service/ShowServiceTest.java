package com.seatbooking.api.service;

import com.seatbooking.api.exception.ShowNotFoundException;
import com.seatbooking.api.repository.SeatRepository;
import com.seatbooking.api.repository.ShowRepository;
import com.seatbooking.common.dto.CreateShowRequest;
import com.seatbooking.common.dto.ShowResponse;
import com.seatbooking.common.entity.SeatEntity;
import com.seatbooking.common.entity.ShowEntity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShowServiceTest {

    @Mock
    private ShowRepository showRepository;

    @Mock
    private SeatRepository seatRepository;

    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private ShowService showService;

    @Captor
    private ArgumentCaptor<ShowEntity> showCaptor;

    @Captor
    private ArgumentCaptor<List<SeatEntity>> seatsCaptor;

    @BeforeEach
    void setUp() {
        showService = new ShowService(showRepository, seatRepository, meterRegistry);
    }

    @Test
    void createShow_SavesShowAndSeats() {
        CreateShowRequest request = new CreateShowRequest();
        request.setName("Avengers");
        request.setPrice(1500L);
        request.setSeats(List.of("A1", "A2"));

        ShowEntity savedShow = new ShowEntity();
        savedShow.setShowId(UUID.randomUUID());
        savedShow.setName("Avengers");
        savedShow.setTotalSeats(2);
        savedShow.setPrice(1500L);
        savedShow.setPerUserLimit(5);

        when(showRepository.save(any(ShowEntity.class))).thenReturn(savedShow);

        ShowResponse response = showService.createShow(request);

        verify(showRepository).save(showCaptor.capture());
        ShowEntity capturedShow = showCaptor.getValue();
        assertThat(capturedShow.getName()).isEqualTo("Avengers");
        assertThat(capturedShow.getTotalSeats()).isEqualTo(2);

        verify(seatRepository).saveAll(seatsCaptor.capture());
        List<SeatEntity> capturedSeats = seatsCaptor.getValue();
        assertThat(capturedSeats).hasSize(2);
        assertThat(capturedSeats.get(0).getSeatId()).isEqualTo("A1");
        assertThat(capturedSeats.get(0).getStatus()).isEqualTo("available");

        assertThat(response.getShowId()).isEqualTo(savedShow.getShowId().toString());
        assertThat(response.getName()).isEqualTo("Avengers");
        assertThat(response.getCounts().getAvailable()).isEqualTo(2);
        assertThat(response.getCounts().getTotal()).isEqualTo(2);
    }

    @Test
    void getShow_WhenShowExists_ReturnsShowResponse() {
        UUID showId = UUID.randomUUID();
        ShowEntity show = new ShowEntity();
        show.setShowId(showId);
        show.setName("Avengers");
        show.setTotalSeats(2);

        SeatEntity seat1 = new SeatEntity();
        seat1.setSeatId("A1");
        seat1.setStatus("available");

        SeatEntity seat2 = new SeatEntity();
        seat2.setSeatId("A2");
        seat2.setStatus("held");

        when(showRepository.findById(showId)).thenReturn(Optional.of(show));
        when(seatRepository.findByShowId(showId)).thenReturn(List.of(seat1, seat2));

        ShowResponse response = showService.getShow(showId);

        assertThat(response.getShowId()).isEqualTo(showId.toString());
        assertThat(response.getCounts().getAvailable()).isEqualTo(1);
        assertThat(response.getCounts().getHeld()).isEqualTo(1);
        assertThat(response.getCounts().getTotal()).isEqualTo(2);
    }

    @Test
    void getShow_WhenShowDoesNotExist_ThrowsException() {
        UUID showId = UUID.randomUUID();
        when(showRepository.findById(showId)).thenReturn(Optional.empty());

        assertThrows(ShowNotFoundException.class, () -> showService.getShow(showId));
    }

    @Test
    void getAllShows_ReturnsList() {
        ShowEntity show1 = new ShowEntity();
        show1.setShowId(UUID.randomUUID());
        show1.setName("Show 1");

        ShowEntity show2 = new ShowEntity();
        show2.setShowId(UUID.randomUUID());
        show2.setName("Show 2");

        when(showRepository.findAll()).thenReturn(List.of(show1, show2));

        var shows = showService.getAllShows();
        assertThat(shows).hasSize(2);
        assertThat(shows.get(0).get("name")).isEqualTo("Show 1");
        assertThat(shows.get(1).get("name")).isEqualTo("Show 2");
    }
}
