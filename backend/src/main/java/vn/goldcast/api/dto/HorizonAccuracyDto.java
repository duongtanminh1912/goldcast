package vn.goldcast.api.dto;

/**
 * Do chinh xac tai mot so buoc du bao nhat dinh; sai so tang theo khoang cach.
 *
 * <p>Dung chung boi {@link ForecastDto} va {@link BacktestDto}. Truoc day moi DTO
 * giu mot ban sao rieng -- {@code ForecastDto.HorizonAccuracy} va
 * {@code BacktestDto.HorizonRow} -- giong het nhau tung truong. Phia TypeScript
 * da dung chung mot interface tu dau, nen chinh phia Java moi la phia du thua.
 *
 * <p>Cong hop dong API (G1) tim ra cho trung lap nay ma khong ai di tim.
 */
public record HorizonAccuracyDto(int step, Double mae, Double rmse, Double mape, int sampleSize) {}
