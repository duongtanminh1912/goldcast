package vn.goldcast;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CiGateTest {

    @Test
    void coTinhSaiDeKiemTraCongCI() {
        assertEquals(1, 2, "Test nay co tinh sai — dung de chung minh CI biet chan");
    }
}