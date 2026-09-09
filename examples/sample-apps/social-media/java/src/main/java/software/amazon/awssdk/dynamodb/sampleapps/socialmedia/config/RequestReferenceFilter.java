package software.amazon.awssdk.dynamodb.sampleapps.socialmedia.config;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import software.amazon.awssdk.dynamodb.sampleapps.socialmedia.util.RequestReference;

/**
 * Assigns one request reference id per HTTP request and stores it in MDC.
 *
 * <p>A valid {@code X-Request-Id} is reused. The id is also stored as a request attribute so Spring
 * MVC async dispatch can restore MDC after the first filter pass clears it.
 */
@Component
public class RequestReferenceFilter extends OncePerRequestFilter {

    /**
     * {@inheritDoc}
     *
     * <p>Runs again on async dispatch so MDC is restored for exception handling after the original
     * Tomcat worker has already left the filter.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs again on error dispatch so the unexpected-error handler can read MDC.
     */
    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }

    /**
     * Assigns or restores the request reference id, then clears MDC for this thread.
     *
     * @param request     current HTTP request
     * @param response    current HTTP response
     * @param filterChain remaining filters
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Object existing = request.getAttribute(RequestReference.REQUEST_ATTRIBUTE);
        String requestId = existing instanceof String id && RequestReference.isValid(id)
                ? id
                : RequestReference.resolveIncomingOrGenerate(request.getHeader(RequestReference.HEADER_NAME));
        request.setAttribute(RequestReference.REQUEST_ATTRIBUTE, requestId);
        MDC.put(RequestReference.MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(RequestReference.MDC_KEY);
        }
    }
}
