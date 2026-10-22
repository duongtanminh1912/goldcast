package vn.goldcast.market.live;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class GoldReferenceSessionTest {
    @Test
    void weekendWindowFollowsNewYorkIncludingDst() {
        assertFalse(GoldReferenceSession.expectsUpdates(Instant.parse("2026-10-23T21:01:00Z")));
        assertFalse(GoldReferenceSession.expectsUpdates(Instant.parse("2026-10-25T21:59:00Z")));
        assertTrue(GoldReferenceSession.expectsUpdates(Instant.parse("2026-10-25T22:00:00Z")));
        assertFalse(GoldReferenceSession.expectsUpdates(Instant.parse("2026-11-08T22:59:00Z")));
        assertTrue(GoldReferenceSession.expectsUpdates(Instant.parse("2026-11-08T23:00:00Z")));
    }

    @Test
    void weekdayReferenceBreakIsSuppressed() {
        assertTrue(GoldReferenceSession.expectsUpdates(Instant.parse("2026-10-21T20:59:00Z")));
        assertFalse(GoldReferenceSession.expectsUpdates(Instant.parse("2026-10-21T21:30:00Z")));
        assertTrue(GoldReferenceSession.expectsUpdates(Instant.parse("2026-10-21T22:01:00Z")));
    }
}
