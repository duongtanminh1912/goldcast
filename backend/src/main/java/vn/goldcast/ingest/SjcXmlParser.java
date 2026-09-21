package vn.goldcast.ingest;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses SJC's XML rate feed into quotes, independently of how the XML was fetched.
 *
 * <p>The parser is deliberately structure-tolerant: it walks the whole document looking
 * for any element carrying buy and sell attributes, tracking the nearest enclosing city,
 * rather than following a fixed path. Feeds like this get reorganised without notice, and
 * a rigid XPath fails silently — it returns nothing and everything downstream looks merely
 * quiet rather than broken.
 */
public final class SjcXmlParser {

    /**
     * Plausibility window for a price per lượng, in đồng.
     *
     * <p>The lower bound is deliberately high. A per-<em>chỉ</em> figure — a tenth of a
     * lượng, which some feeds publish — lands around 8 million; setting the floor above
     * that means such a value is rescaled out of range and rejected rather than silently
     * accepted as a lượng price that is ten times too low.
     */
    static final BigDecimal MIN_PLAUSIBLE_VND_PER_TAEL = new BigDecimal("10000000");
    static final BigDecimal MAX_PLAUSIBLE_VND_PER_TAEL = new BigDecimal("1000000000");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private SjcXmlParser() {}

    /**
     * @param xml          the feed body
     * @param fallbackDate used when the document carries no usable date
     * @param wantedCodes  instrument codes to extract, e.g. {@code SJC_HCM}
     * @return one quote per code that could be matched; codes with no match are omitted
     */
    public static List<ProviderQuote> parse(String xml, LocalDate fallbackDate, List<String> wantedCodes) {
        List<ProviderQuote> out = new ArrayList<>();
        if (xml == null || xml.isBlank()) {
            return out;
        }

        Document document;
        try {
            document = secureBuilder().parse(
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            return out;
        }

        Element root = document.getDocumentElement();
        if (root == null) {
            return out;
        }

        LocalDate date = findDate(root, fallbackDate);
        List<RawQuote> raw = new ArrayList<>();
        walk(root, null, raw);

        for (String code : wantedCodes) {
            RawQuote match = bestMatch(code, raw);
            if (match == null) {
                continue;
            }
            BigDecimal buy = normaliseToVndPerTael(match.buy());
            BigDecimal sell = normaliseToVndPerTael(match.sell());
            if (buy == null || sell == null) {
                continue;
            }
            out.add(ProviderQuote.twoWay(code, date, buy, sell));
        }
        return out;
    }

    /** A parser hardened against XXE and entity-expansion attacks on third-party XML. */
    private static DocumentBuilder secureBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

        DocumentBuilder builder = factory.newDocumentBuilder();
        // The default handler prints parse failures straight to stderr, bypassing logging.
        // A feed returning an HTML error page is routine; it is handled, not shouted about.
        builder.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) {
                // ignored
            }

            @Override
            public void error(SAXParseException exception) {
                // ignored; parse() reports an empty result instead
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }
        });
        return builder;
    }

    private static void walk(Element element, String city, List<RawQuote> sink) {
        String currentCity = city;
        String nodeName = element.getNodeName().toLowerCase(Locale.ROOT);
        if (nodeName.contains("city") && element.hasAttribute("name")) {
            currentCity = element.getAttribute("name");
        }

        BigDecimal buy = attributeAsDecimal(element, "buy", "buy_value", "muavao", "mua");
        BigDecimal sell = attributeAsDecimal(element, "sell", "sell_value", "banra", "ban");
        if (buy != null && sell != null) {
            String type = firstNonBlank(
                    element.getAttribute("type"),
                    element.getAttribute("name"),
                    element.getAttribute("title"));
            sink.add(new RawQuote(currentCity, type, buy, sell));
        }

        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                walk(childElement, currentCity, sink);
            }
        }
    }

    private static LocalDate findDate(Element root, LocalDate fallback) {
        for (String attribute : new String[] {"date", "updated", "ngay"}) {
            String value = deepAttribute(root, attribute);
            if (value == null) {
                continue;
            }
            LocalDate parsed = DateParsing.parseFlexible(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return fallback;
    }

    private static String deepAttribute(Element element, String attribute) {
        if (element.hasAttribute(attribute) && !element.getAttribute(attribute).isBlank()) {
            return element.getAttribute(attribute);
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                String found = deepAttribute(child, attribute);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * Picks the row that best matches an instrument code.
     *
     * <p>Matching runs on ASCII-folded text, so "Hồ Chí Minh" and "Ho Chi Minh" behave
     * identically, and covers the several spellings the feed has used over time.
     */
    static RawQuote bestMatch(String code, List<RawQuote> raw) {
        String upper = code.toUpperCase(Locale.ROOT);
        boolean wantRing = upper.contains("RING");
        String[] cityTerms = switch (upper) {
            case "SJC_HN" -> new String[] {"ha noi", "hanoi"};
            case "SJC_HCM", "SJC_RING" -> new String[] {"ho chi minh", "hcm", "tphcm", "sai gon"};
            default -> new String[] {};
        };

        RawQuote sameCityFallback = null;
        for (RawQuote quote : raw) {
            String city = fold(quote.city());
            String type = fold(quote.type());

            // A row with no city attached is not filtered out: some feeds list ring prices
            // outside any city block, and dropping them would lose the series entirely.
            boolean cityKnown = !city.isEmpty();
            if (cityTerms.length > 0 && cityKnown && !matchesAny(city, cityTerms)) {
                continue;
            }

            boolean isRing = type.contains("nhan") || type.contains("ring");
            if (wantRing != isRing) {
                if (sameCityFallback == null) {
                    sameCityFallback = quote;
                }
                continue;
            }
            if (wantRing || type.contains("sjc") || type.contains("1l") || type.contains("10l")
                    || type.contains("1kg") || type.isEmpty()) {
                return quote;
            }
            if (sameCityFallback == null) {
                sameCityFallback = quote;
            }
        }
        // Settle for another product from the same city only when nothing better exists,
        // and never for rings — a ring price is a different product, not an approximation.
        return wantRing ? null : sameCityFallback;
    }

    private static boolean matchesAny(String haystack, String[] needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Brings a raw figure onto VND per lượng.
     *
     * <p>Feeds of this kind have quoted in đồng and in thousands of đồng at different
     * times. Rather than hard-coding one assumption that breaks silently, the value is
     * scaled by 1000 until it lands in a plausible range — and rejected if it never does.
     * A rejected row is better than a row that is wrong by three orders of magnitude.
     */
    public static BigDecimal normaliseToVndPerTael(BigDecimal raw) {
        if (raw == null || raw.signum() <= 0) {
            return null;
        }
        BigDecimal value = raw;
        for (int i = 0; i < 4 && value.compareTo(MIN_PLAUSIBLE_VND_PER_TAEL) < 0; i++) {
            value = value.multiply(THOUSAND);
        }
        if (value.compareTo(MIN_PLAUSIBLE_VND_PER_TAEL) < 0
                || value.compareTo(MAX_PLAUSIBLE_VND_PER_TAEL) > 0) {
            return null;
        }
        return value;
    }

    private static BigDecimal attributeAsDecimal(Element element, String... attributes) {
        for (String attribute : attributes) {
            if (!element.hasAttribute(attribute)) {
                continue;
            }
            BigDecimal parsed = DateParsing.parseVietnameseNumber(element.getAttribute(attribute));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /** Lowercases and strips Vietnamese diacritics so text matching is spelling-agnostic. */
    public static String fold(String value) {
        if (value == null) {
            return "";
        }
        String normalised = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd')
                .replace('Đ', 'D');
        return normalised.toLowerCase(Locale.ROOT).trim();
    }

    record RawQuote(String city, String type, BigDecimal buy, BigDecimal sell) {}
}
