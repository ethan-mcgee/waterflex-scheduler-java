package dev.waterflex.scheduler;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingSearchOutcomeTest {
    @Test void expiredPortalDeadlineNeverStartsBooking() {
        BookingService service = mock(BookingService.class);
        var response = new BookingController(service, new SearchAdmission(2, 16), mock(BookingCoordinator.class), mock(ReservationLifecycleService.class), false).offers(
                new BookingController.JobRequest("job", false, System.currentTimeMillis() - 1));
        assertEquals(BookingController.SearchOutcome.SEARCH_INCOMPLETE, response.search().outcome());
        verifyNoInteractions(service);
    }
    @Test void insertionMissDoesNotClaimCompletedScarcitySearch() {
        BookingService service = mock(BookingService.class);
        when(service.offers("job", false)).thenReturn(new BookingService.Offers("job", Required.value(List.of())));
        var response = new BookingController(service, new SearchAdmission(2, 16), mock(BookingCoordinator.class), mock(ReservationLifecycleService.class), false).offers(new BookingController.JobRequest("job", false));
        assertEquals(BookingController.SearchOutcome.SEARCH_INCOMPLETE, response.search().outcome());
        assertFalse(response.search().prescribedSearchCompleted());
        assertTrue(response.search().retryable());
        assertTrue(response.search().elapsedMs() >= 0);
    }

    @Test void availableResponseCarriesCommittedOffersWithoutClaimingBoundedSearchCompletion() {
        BookingService service = mock(BookingService.class);
        var start = Required.value(Instant.parse("2099-10-05T14:00:00Z"));
        var end = Required.value(start.plusSeconds(7200));
        var offers = Required.value(List.<BookingService.Offer>of(new BookingService.Offer("offer", "2099-10-05", start, end, end)));
        when(service.offers("job", true)).thenReturn(new BookingService.Offers("job", offers));
        var response = new BookingController(service, new SearchAdmission(2, 16), mock(BookingCoordinator.class), mock(ReservationLifecycleService.class), false).offers(new BookingController.JobRequest("job", true));
        assertEquals(offers, response.offers());
        assertEquals(BookingController.SearchOutcome.AVAILABLE, response.search().outcome());
        assertFalse(response.search().prescribedSearchCompleted());
        assertFalse(response.search().retryable());
    }

    @Test void failuresRemainDistinctAndDoNotProduceOffers() {
        List<RuntimeException> failures = List.<RuntimeException>of(new RoadClient.RoadUnavailable("Road unavailable"),
                new CannotAcquireLockException("Busy"), new QueryTimeoutException("Timed out"),
                new ResponseStatusException(HttpStatus.CONFLICT, "Changed"));
        List<BookingController.SearchOutcome> outcomes = List.of(BookingController.SearchOutcome.ROUTING_UNAVAILABLE,
                BookingController.SearchOutcome.SERVICE_BUSY, BookingController.SearchOutcome.SEARCH_INCOMPLETE,
                BookingController.SearchOutcome.SCHEDULE_CONFLICT);
        for (int index = 0; index < failures.size(); index++) {
            BookingService service = mock(BookingService.class);
            when(service.offers("job", false)).thenThrow(failures.get(index));
            var response = new BookingController(service, new SearchAdmission(2, 16), mock(BookingCoordinator.class), mock(ReservationLifecycleService.class), false).offers(new BookingController.JobRequest("job", false));
            assertEquals(outcomes.get(index), response.search().outcome());
            assertTrue(response.offers().isEmpty());
            assertTrue(response.search().retryable());
            assertFalse(response.search().prescribedSearchCompleted());
        }
    }

    @Test void invalidJobLocationPreservesValidationError() {
        BookingService service = mock(BookingService.class);
        when(service.offers("job", false)).thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Missing coordinates"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
                () -> new BookingController(service, new SearchAdmission(2, 16), mock(BookingCoordinator.class), mock(ReservationLifecycleService.class), false).offers(new BookingController.JobRequest("job", false))).getStatusCode());
    }
}
