package vn.goldcast.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Public deployment identity, checked through the frontend during G2/G3. */
@RestController
public class ReleaseController {
    private final String releaseId;

    public ReleaseController(@Value("${app.release-id:}") String releaseId) {
        this.releaseId = releaseId;
    }

    @GetMapping("/api/v1/release")
    public ResponseEntity<Map<String, String>> release() {
        if (releaseId.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("status", "unconfigured"));
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(Map.of("releaseId", releaseId));
    }
}
