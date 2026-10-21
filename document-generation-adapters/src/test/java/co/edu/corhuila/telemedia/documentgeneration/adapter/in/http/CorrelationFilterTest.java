package co.edu.corhuila.telemedia.documentgeneration.adapter.in.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Numeral 5.3.9: reuse the received X-Correlation-Id or generate one, and return it. */
class CorrelationFilterTest {

    private final CorrelationFilter filter = new CorrelationFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void reusesTheReceivedIdInTheResponseTheRequestAndTheLog() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.addHeader(CorrelationFilter.HEADER, "e2e-saga-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> logged = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> logged.set(MDC.get(CorrelationFilter.MDC_KEY)));

        assertThat(response.getHeader(CorrelationFilter.HEADER)).isEqualTo("e2e-saga-1");
        assertThat(CorrelationFilter.current(request)).isEqualTo("e2e-saga-1");
        assertThat(logged.get()).isEqualTo("e2e-saga-1");
    }

    @Test
    void generatesAUuidWhenTheHeaderIsAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String generated = response.getHeader(CorrelationFilter.HEADER);
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(CorrelationFilter.current(request)).isEqualTo(generated);
    }

    @Test
    void replacesAnUnsafeOrTooLongValueWithANewUuid() throws Exception {
        for (String unsafe : new String[] {"line\nbreak", "with space", "x".repeat(129), ""}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
            request.addHeader(CorrelationFilter.HEADER, unsafe);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, (req, res) -> { });

            String returned = response.getHeader(CorrelationFilter.HEADER);
            assertThat(returned).as("value %s", unsafe).isNotEqualTo(unsafe);
            assertThat(UUID.fromString(returned)).isNotNull();
        }
    }

    @Test
    void acceptsTheLongestAllowedValue() throws Exception {
        String longest = "a".repeat(128);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.addHeader(CorrelationFilter.HEADER, longest);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(CorrelationFilter.HEADER)).isEqualTo(longest);
    }

    @Test
    void theLogContextIsClearedAfterTheRequestEvenWhenItFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.addHeader(CorrelationFilter.HEADER, "e2e-saga-1");

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new IllegalStateException("downstream failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(CorrelationFilter.MDC_KEY)).isNull();
    }
}
