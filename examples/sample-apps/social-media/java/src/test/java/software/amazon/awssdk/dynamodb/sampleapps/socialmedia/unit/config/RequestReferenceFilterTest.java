package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.unit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config.RequestReferenceFilter;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.util.RequestReference;

/**
 * Unit coverage for per-request reference assignment.
 *
 * <p>A valid incoming {@code X-Request-Id} is reused. An absent or invalid value is replaced with a
 * generated id. MDC is populated for the filter chain and cleared afterwards. No Docker is required.
 */
@Tag("unit")
class RequestReferenceFilterTest {

    private final RequestReferenceFilter filter = new RequestReferenceFilter();

    /**
     * Clears MDC so a leftover request id cannot leak into the next test.
     */
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void doFilterInternal_whenHeaderIsValid_reusesIncomingId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestReference.HEADER_NAME, "req_client_abc");
        AtomicReference<String> seenMdc = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                seenMdc.set(MDC.get(RequestReference.MDC_KEY)));

        assertThat(seenMdc.get()).isEqualTo("req_client_abc");
        assertThat(request.getAttribute(RequestReference.REQUEST_ATTRIBUTE)).isEqualTo("req_client_abc");
        assertThat(MDC.get(RequestReference.MDC_KEY)).isNull();
    }

    @Test
    void doFilterInternal_whenHeaderIsAbsent_generatesId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AtomicReference<String> seenMdc = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                seenMdc.set(MDC.get(RequestReference.MDC_KEY)));

        assertThat(seenMdc.get()).isNotBlank();
        assertThat(RequestReference.isValid(seenMdc.get())).isTrue();
        assertThat(request.getAttribute(RequestReference.REQUEST_ATTRIBUTE)).isEqualTo(seenMdc.get());
        assertThat(MDC.get(RequestReference.MDC_KEY)).isNull();
    }

    @Test
    void doFilterInternal_whenHeaderIsInvalid_generatesReplacementId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestReference.HEADER_NAME, "not a valid id!");
        AtomicReference<String> seenMdc = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                seenMdc.set(MDC.get(RequestReference.MDC_KEY)));

        assertThat(seenMdc.get()).isNotEqualTo("not a valid id!");
        assertThat(RequestReference.isValid(seenMdc.get())).isTrue();
        assertThat(MDC.get(RequestReference.MDC_KEY)).isNull();
    }

    @Test
    void doFilterInternal_whenAttributeAlreadySet_restoresSameId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestReference.REQUEST_ATTRIBUTE, "req_async_restore");
        request.addHeader(RequestReference.HEADER_NAME, "req_other");
        AtomicReference<String> seenMdc = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                seenMdc.set(MDC.get(RequestReference.MDC_KEY)));

        assertThat(seenMdc.get()).isEqualTo("req_async_restore");
        assertThat(request.getAttribute(RequestReference.REQUEST_ATTRIBUTE)).isEqualTo("req_async_restore");
    }

    @Test
    void resolveIncomingOrGenerate_whenValueIsValid_returnsSameValue() {
        assertThat(RequestReference.resolveIncomingOrGenerate("req_ok")).isEqualTo("req_ok");
        assertThat(RequestReference.isValid(null)).isFalse();
        assertThat(RequestReference.isValid("")).isFalse();
    }
}
