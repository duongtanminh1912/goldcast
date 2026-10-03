import Link from "next/link";
import type { Metadata } from "next";
import { Card, SectionHeading } from "@/components/ui";

export const metadata: Metadata = {
  title: "Phương pháp",
  description:
    "Cách goldcast thu thập dữ liệu, quy đổi đơn vị, khớp mô hình dự báo và đo sai số bằng backtest rolling-origin.",
};

export default function MethodologyPage() {
  return (
    <div className="space-y-6">
      <SectionHeading
        title="Phương pháp luận"
        description="Trang này mô tả chính xác những gì hệ thống làm với dữ liệu — và những gì nó không làm."
      />

      <Card title="1. Dữ liệu đến từ đâu">
        <div className="space-y-3 text-sm leading-relaxed text-ink-muted">
          <p>
            <strong className="text-ink">Vàng thế giới (XAU/USD) và tỷ giá USD/VND</strong> lấy
            từ Stooq dưới dạng CSV lịch sử theo ngày. Một lần gọi trả về toàn bộ chuỗi, nên
            việc nạp lại nhiều năm dữ liệu chỉ tốn hai request.
          </p>
          <p>
            <strong className="text-ink">Vàng miếng trong nước</strong> lấy từ feed XML công
            khai của SJC. Feed này chỉ có giá của ngày hiện tại — không có endpoint lịch sử —
            nên chuỗi trong nước dày lên dần kể từ lúc hệ thống bắt đầu chạy. Đây là giới hạn
            thật của nguồn dữ liệu, và hệ quả của nó là dự báo cho vàng trong nước cần thời
            gian tích luỹ trước khi có ý nghĩa.
          </p>
          <p>
            Khi một chuỗi hoàn toàn chưa có dữ liệu và không kết nối được nguồn nào, hệ thống
            sinh dữ liệu mô phỏng có tính xác định để ứng dụng vẫn chạy được. Những dòng đó
            được gắn nhãn <code className="font-mono text-xs">source = "synthetic"</code> và
            giao diện luôn cảnh báo. Dữ liệu mô phỏng không bao giờ bị trộn vào một chuỗi đã
            có dữ liệu thật.
          </p>
        </div>
      </Card>

      <Card title="2. Quy đổi giữa hai thị trường">
        <div className="space-y-3 text-sm leading-relaxed text-ink-muted">
          <p>
            Vàng thế giới niêm yết theo USD mỗi troy ounce; trong nước niêm yết theo đồng mỗi
            lượng. Muốn so sánh phải quy đổi cả khối lượng lẫn tiền tệ:
          </p>
          <pre className="overflow-x-auto rounded-lg border border-border bg-surface p-3 font-mono text-xs">
{`1 troy ounce = 31,1034768 g
1 lượng (cây) = 37,5 g = 10 chỉ

VNĐ/lượng = USD/oz ÷ 31,1034768 × 37,5 × tỷ giá USD/VND`}
          </pre>
          <p>
            Khoảng cách giữa con số đó và giá SJC thực bán là <strong className="text-ink">
            chênh lệch trong nước</strong>. Ở Việt Nam khoản này lớn và biến động theo cung
            cầu, nguồn cung vàng miếng và chính sách trong nước chứ không theo thị trường thế
            giới. Vì vậy nó được hiển thị như một con số riêng, không bị gộp vào giá.
          </p>
          <p>
            Khi so một mức giá trong nước với giá thế giới, hệ thống dùng giá thế giới và tỷ
            giá <em>có hiệu lực đúng ngày đó</em>, không phải giá mới nhất. So giá vàng niêm
            yết Chủ nhật với giá đóng cửa thứ Hai là so một mức giá với thông tin chưa tồn tại
            ở thời điểm đó.
          </p>
        </div>
      </Card>

      <Card title="3. Các mô hình dự báo">
        <div className="space-y-3 text-sm leading-relaxed text-ink-muted">
          <ul className="space-y-2">
            <li>
              <strong className="text-ink">Naive (bước đi ngẫu nhiên)</strong> — giá ngày mai
              bằng giá hôm nay. Với giá kim loại quý theo ngày, đây là baseline rất mạnh, và
              là thước đo mà mọi mô hình khác phải vượt qua.
            </li>
            <li>
              <strong className="text-ink">Drift</strong> — ngoại suy mức thay đổi trung bình
              của cả chuỗi huấn luyện.
            </li>
            <li>
              <strong className="text-ink">SMA</strong> — dự báo phẳng bằng trung bình k phiên
              gần nhất. Ổn định hơn naive trên dữ liệu nhiễu, nhưng phản ứng chậm hơn.
            </li>
            <li>
              <strong className="text-ink">Holt có xu hướng tắt dần</strong> — san mũ với hệ số
              φ &lt; 1. Việc tắt dần là cố ý: một mô hình xu hướng không tắt, khi khớp vào một
              đợt tăng giá vàng, sẽ ngoại suy đợt tăng đó ra vô hạn.
            </li>
            <li>
              <strong className="text-ink">AR(p) trên sai phân</strong> — hồi quy tự tương quan
              bậc p trên sai phân bậc 1, tương đương ARIMA(p,1,0), khớp bằng bình phương tối
              thiểu. Lấy sai phân là điều kiện cần: chuỗi giá có xu hướng nên không dừng, còn
              mức thay đổi ngày-qua-ngày thì xấp xỉ dừng.
            </li>
            <li>
              <strong className="text-ink">Auto</strong> — backtest tất cả mô hình trên chính
              chuỗi đó rồi chọn mô hình có MASE thấp nhất. Khi hoà, mô hình đơn giản hơn thắng.
            </li>
          </ul>
        </div>
      </Card>

      <Card title="4. Đo sai số bằng backtest rolling-origin">
        <div className="space-y-3 text-sm leading-relaxed text-ink-muted">
          <p>
            Tại mỗi điểm gốc trong lịch sử, mô hình được <em>khớp lại từ đầu</em> chỉ với dữ
            liệu có trước thời điểm đó, rồi chấm điểm trên phần dữ liệu phía sau. Đây là cách
            duy nhất trả lời được câu hỏi người dùng thực sự quan tâm — "trước giờ mô hình này
            sai bao nhiêu?" — mà không để thông tin tương lai rò rỉ vào quá trình khớp.
          </p>
          <p>Bốn chỉ số được báo cáo:</p>
          <ul className="space-y-1.5">
            <li>
              <strong className="text-ink">MAE</strong> — sai số tuyệt đối trung bình, theo đúng
              đơn vị của chuỗi.
            </li>
            <li>
              <strong className="text-ink">RMSE</strong> — căn bậc hai sai số bình phương trung
              bình; phạt nặng các lần sai lớn.
            </li>
            <li>
              <strong className="text-ink">MAPE</strong> — sai số phần trăm tuyệt đối trung bình.
            </li>
            <li>
              <strong className="text-ink">MASE</strong> — chỉ số đáng đọc nhất. Nó chia MAE cho
              sai số mà dự báo naive mắc phải trên cùng chuỗi. Dưới 1: mô hình mang thêm thông
              tin. Từ 1 trở lên: không, bất kể MAPE trông đẹp thế nào.
            </li>
          </ul>
          <p>
            Sai số cũng được tách riêng theo từng bước dự báo, vì nó nở ra theo khoảng cách.
            Gộp tất cả vào một con số trung bình là cách người ta bán một dự báo quá mức.
          </p>
        </div>
      </Card>

      <Card title="5. Khoảng tin cậy được tính thế nào">
        <div className="space-y-3 text-sm leading-relaxed text-ink-muted">
          <p>
            Khoảng tin cậy được suy ra từ <strong className="text-ink">RMSE out-of-sample đo
            được ở từng bước</strong>, chứ không phải từ phần dư in-sample.
          </p>
          <p>
            Khác biệt này quan trọng. Phần dư in-sample luôn nhỏ hơn thực tế — mô hình đã nhìn
            thấy chính những điểm đó rồi — và khoảng tin cậy dựng từ chúng là lý do chính khiến
            các dashboard dự báo trông tự tin hơn nhiều so với mức chúng xứng đáng.
          </p>
          <p>
            Khi một bước dự báo chưa đủ số mẫu out-of-sample để tin cậy, độ rộng quay về dùng
            sai số một bước nhân √h — tốc độ nở chuẩn của một bước đi ngẫu nhiên. Cận dưới luôn
            bị chặn tại 0.
          </p>
        </div>
      </Card>

      <Card title="6. Những gì hệ thống này không làm">
        <div className="space-y-3 text-sm leading-relaxed text-ink-muted">
          <p>
            Tất cả mô hình ở đây đều là <strong className="text-ink">ngoại suy thống kê từ
            giá quá khứ</strong>. Không mô hình nào biết đến lãi suất, chính sách tiền tệ, biến
            động địa chính trị, thay đổi quy định về vàng miếng, hay bất kỳ can thiệp nào lên
            thị trường trong nước. Những yếu tố đó mới là thứ thực sự tạo ra các cú sốc giá lớn.
          </p>
          <p>
            Trên phần lớn khung thời gian, giá vàng theo ngày rất gần với bước đi ngẫu nhiên.
            Khi đó MASE sẽ ở quanh hoặc trên 1, và giao diện nói thẳng điều đó thay vì giấu đi.
            Một dự báo không vượt được baseline naive vẫn được hiển thị — kèm cảnh báo rằng nó
            không đáng tin hơn giả định giá đứng yên.
          </p>
          <p className="rounded-lg border border-warn/35 bg-warn/5 p-3 text-xs text-ink-muted">
            Đây không phải lời khuyên đầu tư, và không thay thế giá niêm yết chính thức tại
            quầy. Đừng dùng những con số này làm căn cứ duy nhất cho quyết định tài chính.
          </p>
        </div>
      </Card>

      <div className="flex flex-wrap gap-2">
        <Link href="/" className="btn">
          Về trang tổng quan
        </Link>
        <Link href="/forecast/XAUUSD" className="btn">
          Xem một dự báo cụ thể
        </Link>
      </div>
    </div>
  );
}
