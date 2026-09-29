package app.pfandcounter;

import com.google.zxing.BarcodeFormat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether what the camera read is a product number worth looking up — or something else
 * printed near it: a QR code with a web address, a batch code, a recycling link.
 *
 * Only a product number (GTIN) ever reaches the deposit list or Open Food Facts. Everything else
 * is dropped here, so a stray QR code costs nothing but a short hint.
 *
 * Kept free of Android types: tools/ProductCodeTest.java runs it on the computer.
 */
final class ProductCode {

    private ProductCode() {
    }

    /** GS1 Digital Link, the new QR code on packs: ".../01/04012345678901/...". */
    private static final Pattern DIGITAL_LINK = Pattern.compile("/01/(\\d{8,14})(?:[/?#]|$)");

    /** A GS1 element string as a Data Matrix or QR code carries it: "01" + 14 digits first. */
    private static final Pattern ELEMENT_STRING = Pattern.compile("^(?:\\]d2|\\]Q3|\\]C1)?\\(?01\\)?(\\d{14})");

    /**
     * The product number in a read, or null when the read holds none. Product numbers come back
     * the way the list stores them: 13 or 8 digits, 12 for UPC-A (the list lookup pads those).
     */
    static String of(BarcodeFormat format, String text) {
        if (format == null || text == null) return null;
        text = text.trim();
        switch (format) {
            case EAN_13:
            case EAN_8:
            case UPC_A:
                return isGtin(text) ? text : null;
            case UPC_E:
                // Its check digit belongs to the expanded 12-digit form, which ZXing has already
                // verified; the 8 digits on their own do not add up the EAN-8 way.
                return text.length() == 8 && allDigits(text) ? text : null;
            case QR_CODE:
            case DATA_MATRIX:
                return fromGs1(text);
            default:
                return null;
        }
    }

    /** For the test intent, which hands over bare text: digits as a barcode, anything else as a QR. */
    static String ofText(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (allDigits(trimmed)) return trimmed.length() != 14 && isGtin(trimmed) ? trimmed : null;
        return fromGs1(trimmed);
    }

    /** 8, 12, 13 or 14 digits whose last digit is the GS1 check digit of the others. */
    static boolean isGtin(String code) {
        int n = code.length();
        if (n != 8 && n != 12 && n != 13 && n != 14) return false;
        if (!allDigits(code)) return false;
        int sum = 0;
        for (int i = 0; i < n - 1; i++) {
            int digit = code.charAt(i) - '0';
            sum += (n - 1 - i) % 2 == 1 ? 3 * digit : digit;
        }
        return (10 - sum % 10) % 10 == code.charAt(n - 1) - '0';
    }

    /**
     * The product number inside a GS1 QR or Data Matrix code. A 14-digit GTIN of a single item
     * starts with 0 and is the EAN-13 behind it; one that starts with 1-9 names an outer case,
     * which has no bottle deposit of its own.
     */
    static String fromGs1(String text) {
        String gtin = null;
        Matcher link = DIGITAL_LINK.matcher(text);
        if (text.startsWith("http") && link.find()) {
            gtin = link.group(1);
        } else {
            Matcher element = ELEMENT_STRING.matcher(text);
            if (element.find()) gtin = element.group(1);
        }
        if (gtin == null || !isGtin(gtin)) return null;
        if (gtin.length() < 14) return gtin.length() == 12 ? "0" + gtin : gtin;
        if (gtin.startsWith("000000")) return gtin.substring(6);   // an EAN-8
        if (gtin.startsWith("0")) return gtin.substring(1);        // an EAN-13
        return null;
    }

    private static boolean allDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }
}
