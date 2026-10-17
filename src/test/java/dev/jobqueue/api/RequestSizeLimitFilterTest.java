package dev.jobqueue.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(100);

    private MockHttpServletResponse send(byte[] body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/jobs");
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void requestWithinLimitPassesThrough() throws Exception {
        MockHttpServletResponse response = send(new byte[100]);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void requestOverLimitIsRejectedWith413ProblemJson() throws Exception {
        MockHttpServletResponse response = send(new byte[101]);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"status\":413").contains("100 bytes");
    }
}
