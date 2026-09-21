package vn.goldcast.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI goldcastOpenApi() {
        return new OpenAPI().info(new Info()
                .title("goldcast API")
                .version("0.1.0")
                .description("""
                        Theo dõi giá vàng thế giới (XAU/USD), tỷ giá USD/VND và giá vàng miếng \
                        trong nước, kèm dự báo thống kê có backtest.

                        Mọi con số dự báo đều là ngoại suy thống kê từ dữ liệu lịch sử, \
                        không phải lời khuyên đầu tư. Khoảng tin cậy được ước lượng từ sai số \
                        out-of-sample thực đo, không phải từ phần dư in-sample.""")
                .license(new License().name("MIT")));
    }
}
