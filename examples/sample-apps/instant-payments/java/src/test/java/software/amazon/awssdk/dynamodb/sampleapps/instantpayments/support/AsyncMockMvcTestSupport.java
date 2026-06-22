package software.amazon.awssdk.dynamodb.sampleapps.instantpayments.support;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultHandler;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

/**
 * Helpers for {@link MockMvc} tests against controllers that return {@code CompletableFuture}.
 */
public final class AsyncMockMvcTestSupport {

    /** Prevents instantiation of this static helper holder. */
    private AsyncMockMvcTestSupport() {
    }

    /**
     * Performs an HTTP request and waits for async MVC processing to complete.
     *
     * @param mvc     configured {@link MockMvc} instance
     * @param request request to perform
     * @return result actions after async dispatch for further expectations
     */
    public static ResultActions performAsync(MockMvc mvc, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult mvcResult = mvc.perform(request).andReturn();
        if (mvcResult.getRequest().isAsyncStarted()) {
            return mvc.perform(asyncDispatch(mvcResult));
        }
        return new SyncMvcResultActions(mvcResult);
    }

    /**
     * {@link ResultActions} wrapper for requests that completed synchronously without starting async MVC.
     */
    private static final class SyncMvcResultActions implements ResultActions {

        private final MvcResult mvcResult;

        /**
         * Wraps a finished {@link MvcResult} so a synchronous response exposes the
         * {@link ResultActions} API.
         *
         * @param mvcResult result of a request that completed without starting async dispatch
         */
        private SyncMvcResultActions(MvcResult mvcResult) {
            this.mvcResult = mvcResult;
        }

        /**
         * Applies a {@link ResultMatcher} to the wrapped result.
         *
         * @param matcher expectation to evaluate against the wrapped result
         * @return this instance so expectations can be chained
         * @throws Exception when the matcher reports a failed expectation
         */
        @Override
        public ResultActions andExpect(ResultMatcher matcher) throws Exception {
            matcher.match(mvcResult);
            return this;
        }

        /**
         * Runs a {@link ResultHandler} against the wrapped result.
         *
         * @param handler action to perform on the wrapped result
         * @return this instance so actions can be chained
         * @throws Exception when the handler fails
         */
        @Override
        public ResultActions andDo(ResultHandler handler) throws Exception {
            handler.handle(mvcResult);
            return this;
        }

        /**
         * Returns the wrapped synchronous result.
         *
         * @return the underlying {@link MvcResult}
         */
        @Override
        public MvcResult andReturn() {
            return mvcResult;
        }
    }
}
