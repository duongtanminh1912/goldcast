package vn.goldcast.api.dto;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;


/**
 * Cong hop dong API: doi chieu DTO Java voi khai bao TypeScript cua frontend.
 *
 * Gioi han da biet, co y de nguyen:
 *   1. Khong hieu @JsonProperty, thu doi ten truong trong JSON.
 *   2. Chi so TEN, khong so KIEU.
 *   3. Khong hieu @JsonValue tren enum; chi so name() cua hang.
 *   4. Doc TypeScript bang regex, khong phai trinh phan tich that.
 *   5. Tang DTO da quy enum ve String, nen enum phai khai tay trong ENUM_QUA_BIEN.
 *   6. NGOAI_LE khoa theo ten don; neu sau nay co hai lop trung ten don thi phai doi cach khoa.
 */
class ApiContractTest {

    private static final String GOI_DTO = "vn.goldcast.api.dto";
    private static final Path DTO_DIR = Path.of("src/main/java/vn/goldcast/api/dto");
    private static final Path TYPES_TS = Path.of("../frontend/src/lib/types.ts");

    /** Kieu da den tan cung, khong di tiep. */
    private static final Set<Class<?>> LA = Set.of(
            String.class, Boolean.class, Integer.class, Long.class,
            Double.class, Float.class, BigDecimal.class,
            LocalDate.class, OffsetDateTime.class, Map.class,
            boolean.class, int.class, long.class, double.class, float.class);

    /** Cho hai phia dat ten khac nhau. Moi dong o day la mot quyet dinh cua con nguoi. */
    private static final Map<String, String> NGOAI_LE = Map.of(
            "Step", "ForecastStep",
            "ModelResult", "BacktestModelResult",
            "Summary", "IndicatorSummary");

    /** Enum di qua bien API duoi dang chuoi. Phai khai tay, xem gioi han so 5. */

    private static final Map<String, String> ENUM_QUA_BIEN = Map.of(
            "vn.goldcast.forecast.ForecastModel", "ForecastModelId",
            "vn.goldcast.domain.InstrumentKind", "InstrumentKind");

    private static final Pattern KHOI_INTERFACE =
            Pattern.compile("^export interface (\\w+)[^{]*\\{([^}]*)\\}", Pattern.MULTILINE);
    private static final Pattern TEN_TRUONG =
            Pattern.compile("^\\s*(\\w+)\\??\\s*:", Pattern.MULTILINE);
    private static final Pattern KHOI_UNION =
            Pattern.compile("^export type (\\w+)\\s*=([^;]*);", Pattern.MULTILINE);
    private static final Pattern GIA_TRI_UNION = Pattern.compile("\"(\\w+)\"");

    // ---------- Chang A: ten cap cao ----------

    @Test
    void moiDtoCapCaoDeuCoInterfaceTypeScript() throws IOException {
        Set<String> dto = dtoCapCao().stream()
                .map(this::tenTypeScript)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> ts = docInterface().keySet();

        Set<String> thieu = new TreeSet<>(dto);
        thieu.removeAll(ts);

        assertTrue(thieu.isEmpty(),
                "DTO khong co interface TypeScript tuong ung: " + thieu
                        + "\nInterface dang co: " + ts);
    }

    // ---------- Chang B: di do thi, so ten truong ----------

    @Test

    void moiRecordCoDungTruongTrongTypeScript() throws IOException {
        Map<String, Set<String>> ts = docInterface();
        List<String> loi = new ArrayList<>();

        Deque<Class<?>> hangDoi = new ArrayDeque<>(dtoCapCao());
        Set<Class<?>> daTham = new HashSet<>();

        while (!hangDoi.isEmpty()) {
            Class<?> lop = hangDoi.poll();
            if (!daTham.add(lop)) continue;

            soSanhTruong(lop, ts, loi);

            for (RecordComponent tp : lop.getRecordComponents()) {
                Class<?> kieu = kieuThucSu(tp);
                if (laRecordDto(kieu)) {
                    hangDoi.add(kieu);
                } else if (!LA.contains(kieu)) {
                    loi.add(lop.getSimpleName() + "." + tp.getName()
                            + ": kieu '" + kieu.getSimpleName() + "' chua duoc cong ho tro");
                }
            }
        }

        assertTrue(loi.isEmpty(), "Hop dong API lech:\n  " + String.join("\n  ", loi));
    }

    // ---------- Chang C: enum qua bien ----------

    @Test
    void moiEnumQuaBienCoDungGiaTriTrongTypeScript() throws IOException {
        Map<String, Set<String>> union = docUnion();

        List<String> loi = new ArrayList<>();

        ENUM_QUA_BIEN.forEach((tenLop, tenTs) -> {
            Object[] hang = napLop(tenLop).getEnumConstants();
            if (hang == null) {
                loi.add(tenLop + " khong phai enum");
                return;
            }
            Set<String> hangJava = Arrays.stream(hang)
                    .map(e -> ((Enum<?>) e).name())
                    .collect(Collectors.toCollection(TreeSet::new));

            Set<String> giaTriTs = union.get(tenTs);
            if (giaTriTs == null) {
                loi.add("Khong co union TypeScript ten '" + tenTs + "' cho enum " + tenLop);
            } else if (!hangJava.equals(giaTriTs)) {
                loi.add(tenTs + ": Java=" + hangJava + " con TypeScript=" + giaTriTs);
            }
        });

        assertTrue(loi.isEmpty(), "Enum lech union TypeScript:\n  " + String.join("\n  ", loi));
    }

    // ---------- Phia Java ----------

    private List<Class<?>> dtoCapCao() throws IOException {
        if (!Files.isDirectory(DTO_DIR)) {
            fail("Khong thay thu muc DTO: " + DTO_DIR.toAbsolutePath());
        }
        try (Stream<Path> files = Files.list(DTO_DIR)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".java"))

                    .map(n -> n.substring(0, n.length() - ".java".length()))
                    .<Class<?>>map(n -> napLop(GOI_DTO + "." + n))
                    .toList();
        }
    }

    private Class<?> napLop(String ten) {
        try {
            return Class.forName(ten);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Khong nap duoc lop " + ten, e);
        }
    }

    private Class<?> kieuThucSu(RecordComponent tp) {
        Type t = tp.getGenericType();
        if (t instanceof ParameterizedType pt
                && pt.getRawType() == List.class
                && pt.getActualTypeArguments()[0] instanceof Class<?> trong) {
            return trong;
        }
        return tp.getType();
    }

    private boolean laRecordDto(Class<?> lop) {
        return lop.isRecord() && lop.getName().startsWith(GOI_DTO);
    }

    private String tenTypeScript(Class<?> lop) {
        String don = lop.getSimpleName();
        if (NGOAI_LE.containsKey(don)) return NGOAI_LE.get(don);
        return don.endsWith("Dto") ? don.substring(0, don.length() - 3) : don;

    }

    // ---------- Phia TypeScript ----------

    private Map<String, Set<String>> docInterface() throws IOException {
        Map<String, Set<String>> ket = new TreeMap<>();
        Matcher m = KHOI_INTERFACE.matcher(docTypesTs());
        while (m.find()) {
            Set<String> truong = new TreeSet<>();
            Matcher t = TEN_TRUONG.matcher(m.group(2));
            while (t.find()) truong.add(t.group(1));
            ket.put(m.group(1), truong);
        }
        return ket;
    }

    private Map<String, Set<String>> docUnion() throws IOException {
        Map<String, Set<String>> ket = new TreeMap<>();
        Matcher m = KHOI_UNION.matcher(docTypesTs());
        while (m.find()) {
            Set<String> gt = new TreeSet<>();
            Matcher v = GIA_TRI_UNION.matcher(m.group(2));
            while (v.find()) gt.add(v.group(1));
            ket.put(m.group(1), gt);
        }
        return ket;
    }

    private String docTypesTs() throws IOException {
        if (!Files.isRegularFile(TYPES_TS)) {
            fail("Khong thay types.ts: " + TYPES_TS.toAbsolutePath());
        }

        return Files.readString(TYPES_TS);
    }

    // ---------- So sanh ----------

    private void soSanhTruong(Class<?> lop, Map<String, Set<String>> ts, List<String> loi) {
        String ten = tenTypeScript(lop);
        Set<String> truongTs = ts.get(ten);
        if (truongTs == null) {
            loi.add("Khong co interface TypeScript ten '" + ten + "' cho " + lop.getName());
            return;
        }
        Set<String> truongJava = Arrays.stream(lop.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> tsThieu = new TreeSet<>(truongJava);
        tsThieu.removeAll(truongTs);
        Set<String> tsThua = new TreeSet<>(truongTs);
        tsThua.removeAll(truongJava);

        if (!tsThieu.isEmpty()) loi.add(ten + ": TypeScript THIEU " + tsThieu);
        if (!tsThua.isEmpty())  loi.add(ten + ": TypeScript THUA " + tsThua);
    }

    // ---------- Chong xanh rong ----------

    /**
     * Neu khong con gi de kiem thi ba test tren deu xanh mot cach vo nghia.
     * Test nay chot san: so luong phai bang hoac hon muc da biet ngay 03/10/2026.
     * Khi co thay doi hop le lam giam so luong, phai sua con so o day bang tay --
     * nghia la mot con nguoi phai xac nhan viec giam do la co y.
     */
    @Test
    void congPhaiThucSuQuetDuocDoiTuong() throws IOException {
        int soDto = dtoCapCao().size();
        int soInterface = docInterface().size();
        int soUnion = docUnion().size();

        assertTrue(soDto >= 8, "Chi quet duoc " + soDto + " DTO, truoc day la 8");
        assertTrue(soInterface >= 19, "Chi doc duoc " + soInterface + " interface, truoc day la 19");
        assertTrue(soUnion >= 2, "Chi doc duoc " + soUnion + " union, truoc day la 2");
    }
}
