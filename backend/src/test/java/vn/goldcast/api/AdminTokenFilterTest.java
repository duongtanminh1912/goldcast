package vn.goldcast.api;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AdminTokenFilterTest {

    private static final String TOKEN_DUNG = "token-bi-mat-de-test";

    @Test
    void chanKhiThieuHeader() throws Exception {
        MockFilterChain chuoi = goiAdmin(TOKEN_DUNG, null);
        assertNull(chuoi.getRequest(), "Filter phai chan truoc khi toi controller");
    }

    @Test
    void chanKhiSaiToken() throws Exception {
        MockFilterChain chuoi = goiAdmin(TOKEN_DUNG, "token-sai");
        assertNull(chuoi.getRequest(), "Token sai van di qua duoc la hong");
    }

    @Test
    void choQuaKhiDungToken() throws Exception {
        MockFilterChain chuoi = goiAdmin(TOKEN_DUNG, TOKEN_DUNG);
        assertNotNull(chuoi.getRequest(), "Token dung ma bi chan la hong");

    }

    @Test
    void chanKhiMayChuChuaCauHinhToken() throws Exception {
        // Quan trong nhat: khoa mac dinh. Chua cau hinh thi chan, ke ca khi
        // nguoi goi gui header -- vi luc do khong co gi de doi chieu.
        MockFilterChain chuoi = goiAdmin("", "bat-ky-thu-gi");
        assertNull(chuoi.getRequest(), "Chua cau hinh token thi phai chan, khong duoc mo");
    }

    @Test
    void khongDungToiDuongDan_khac() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/market/summary");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chuoi = new MockFilterChain();

        new AdminTokenFilter(TOKEN_DUNG).doFilter(req, res, chuoi);

        assertNotNull(chuoi.getRequest(), "Filter chi duoc dung toi /api/v1/admin");
        assertEquals(200, res.getStatus());
    }

    private MockFilterChain goiAdmin(String tokenMayChu, String tokenGui) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/admin/ingest");
        if (tokenGui != null) {
            req.addHeader("X-Admin-Token", tokenGui);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chuoi = new MockFilterChain();

        new AdminTokenFilter(tokenMayChu).doFilter(req, res, chuoi);


        if (chuoi.getRequest() == null) {
            assertEquals(401, res.getStatus(), "Bi chan thi phai tra 401");
        }
        return chuoi;
    }
}
