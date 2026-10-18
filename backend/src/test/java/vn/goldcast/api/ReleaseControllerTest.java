package vn.goldcast.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReleaseControllerTest {
    @Test
    void returnsConfiguredIdentityWithoutCaching() {
        var response = new ReleaseController("candidate-123").release();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("candidate-123", response.getBody().get("releaseId"));
        assertEquals("no-store", response.getHeaders().getCacheControl());
    }

    @Test
    void missingIdentityIsNotReportedAsVerified() {
        assertEquals(503, new ReleaseController("").release().getStatusCode().value());
    }
}
