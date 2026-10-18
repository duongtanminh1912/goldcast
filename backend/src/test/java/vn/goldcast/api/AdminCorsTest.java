package vn.goldcast.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import vn.goldcast.config.AppProperties;
import vn.goldcast.config.WebConfig;
import vn.goldcast.ingest.IngestService;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminCorsTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(CorsConfig.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(new AdminTokenFilter("test-token")).build();
    }

    @AfterEach
    void close() { context.close(); }

    @Test
    void allowedPreflightReachesMvcCorsWithoutCallingIngest() throws Exception {
        mvc.perform(options("/api/v1/admin/ingest")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "X-Admin-Token"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
        verifyNoInteractions(context.getBean(IngestService.class));
    }

    @Test
    void unapprovedOriginIsStillRejected() throws Exception {
        mvc.perform(options("/api/v1/admin/ingest")
                .header("Origin", "https://unapproved.example")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(context.getBean(IngestService.class));
    }

    @Test
    void actualPostStillRequiresCorrectToken() throws Exception {
        mvc.perform(post("/api/v1/admin/ingest").header("Origin", "http://localhost:3000"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/ingest").header("Origin", "http://localhost:3000")
                .header("X-Admin-Token", "test-token"))
                .andExpect(status().isOk());
        verify(context.getBean(IngestService.class), times(1)).run();
    }

    @Configuration
    @EnableWebMvc
    @Import(WebConfig.class)
    static class CorsConfig {
        @Bean AppProperties properties() {
            return new AppProperties(new AppProperties.Cors(List.of("http://localhost:3000")), null, null, null);
        }
        @Bean IngestService ingestService() { return mock(IngestService.class); }
        @Bean AdminController adminController(IngestService service) { return new AdminController(service, List.of()); }
    }
}
