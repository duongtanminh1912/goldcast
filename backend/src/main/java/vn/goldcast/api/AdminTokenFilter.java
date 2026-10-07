package vn.goldcast.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Chan moi request vao /api/v1/admin khong mang dung token.
 *
 * <p>Nhom endpoint do goi ra API ben thu ba va ghi vao database, nen khi he thong
 * co dia chi cong khai thi no phai duoc dong lai -- neu khong, bat ky ai cung co
 * the dot sach han muc API mien phi cua du an.
 *
 * <p><b>Khoa mac dinh.</b> Chua cau hinh token thi endpoint bi chan, khong phai mo.
 * He thong bi lo ra ngoai thuong vi quen dat bien moi truong, hiem khi vi co y
 * tat bao mat -- nen truong hop "quen" phai la truong hop an toan.
 *
 * <p>Day la lop bao ve thu nhat, o tang ung dung. Tu tuan 9 se co lop thu hai o
 * tang proxy: Caddy khong proxy /api/v1/admin ra ngoai. Hai lop doc lap nhau.
 */
@Component
public class AdminTokenFilter extends OncePerRequestFilter {

    private static final String DUONG_DAN_ADMIN = "/api/v1/admin";
    private static final String TEN_HEADER = "X-Admin-Token";

    private final String tokenCauHinh;

    public AdminTokenFilter(@Value("${app.admin.token:}") String tokenCauHinh) {
        this.tokenCauHinh = tokenCauHinh;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(DUONG_DAN_ADMIN);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (tokenCauHinh == null || tokenCauHinh.isBlank()) {
            logger.warn("Chan " + request.getRequestURI() + ": app.admin.token chua duoc cau hinh");
            tuChoi(response, "Endpoint admin dang bi khoa vi chua cau hinh token tren may chu");
            return;
        }

        String tokenGui = request.getHeader(TEN_HEADER);
        if (tokenGui == null || !bangNhauAnToan(tokenGui, tokenCauHinh)) {
            logger.warn("Chan " + request.getRequestURI() + ": thieu hoac sai " + TEN_HEADER);
            tuChoi(response, "Thieu hoac sai header " + TEN_HEADER);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * So sanh trong thoi gian khong doi.
     *
     * <p>equals() cua String dung lai ngay o byte dau tien khac nhau, nen thoi gian
     * tra loi he lo so ky tu dau da dung -- doan duoc token tung ky tu mot.
     * MessageDigest.isEqual luon duyet het, khong he lo gi.
     */
    private static boolean bangNhauAnToan(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private static void tuChoi(HttpServletResponse response, String thongDiep) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":401,\"detail\":\"" + thongDiep + "\"}");
    }
}
