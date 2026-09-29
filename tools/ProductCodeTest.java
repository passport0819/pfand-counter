package app.pfandcounter;

import com.google.zxing.BarcodeFormat;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * ProductCode on the computer: single cases, then every barcode of the shipped list.
 *
 *   javac -cp libs/core-3.5.3.jar -d $TMPDIR/pc src/app/pfandcounter/ProductCode.java tools/ProductCodeTest.java
 *   java -cp libs/core-3.5.3.jar:$TMPDIR/pc app.pfandcounter.ProductCodeTest res/raw/deposits.tsv
 */
public class ProductCodeTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        BarcodeFormat ean13 = BarcodeFormat.EAN_13, qr = BarcodeFormat.QR_CODE;

        same(ProductCode.of(ean13, "4000000000006"), "4000000000006", "EAN-13");
        same(ProductCode.of(BarcodeFormat.EAN_8, "96385074"), "96385074", "EAN-8");
        same(ProductCode.of(BarcodeFormat.UPC_A, "036000291452"), "036000291452", "UPC-A");
        same(ProductCode.of(BarcodeFormat.UPC_E, "01234565"), "01234565", "UPC-E as ZXing hands it over");
        same(ProductCode.of(ean13, "4000000000007"), null, "EAN-13 with a wrong check digit");
        same(ProductCode.of(ean13, "40000000000"), null, "too short");
        same(ProductCode.of(BarcodeFormat.CODE_128, "4000000000006"), null, "right digits, wrong kind of code");
        same(ProductCode.of(BarcodeFormat.ITF, "04000000000006"), null, "ITF-14 on an outer case");

        same(ProductCode.of(qr, "https://www.example-brauerei.de/nachhaltigkeit?id=6847025525"), null, "web-address QR");
        same(ProductCode.of(qr, "http://mehrweg.org/p/7965266242"), null, "web-address QR with digits");
        same(ProductCode.of(qr, "https://qr.gs1.de/01/08614872409"), null, "'/01/' with 11 digits");
        same(ProductCode.of(qr, "WIFI:S:home;T:WPA;P:secret;;"), null, "some other QR");
        same(ProductCode.of(qr, "4000000000006"), null, "bare digits inside a QR are not GS1");
        same(ProductCode.of(qr, "https://id.gs1.org/01/04000000000006"), "4000000000006", "GS1 Digital Link");
        same(ProductCode.of(qr, "https://brand.example/01/04000000000006/10/L123?17=270101"),
                "4000000000006", "Digital Link on a brand domain, with batch and date");
        same(ProductCode.of(qr, "https://id.gs1.org/01/04000000000007"), null, "Digital Link, wrong check digit");
        same(ProductCode.of(qr, "https://id.gs1.org/01/14000000000003"), null, "Digital Link of an outer case");
        same(ProductCode.of(qr, "https://id.gs1.org/01/00000096385074"), "96385074", "Digital Link of an EAN-8");
        same(ProductCode.of(BarcodeFormat.DATA_MATRIX, "]d20104000000000006\u001d10L123"), "4000000000006",
                "GS1 Data Matrix element string");
        same(ProductCode.of(BarcodeFormat.DATA_MATRIX, "(01)04000000000006(17)270101"), "4000000000006",
                "element string in brackets");

        same(ProductCode.ofText("4000000000006"), "4000000000006", "test intent, digits");
        same(ProductCode.ofText("https://example.com/"), null, "test intent, web address");
        same(ProductCode.ofText("https://id.gs1.org/01/04000000000006"), "4000000000006", "test intent, Digital Link");
        same(ProductCode.ofText("4299999999983"), "4299999999983", "test barcode used on the phone before");

        if (args.length > 0) list(args[0]);

        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " TEST(S) FAILED");
        if (failures > 0) System.exit(1);
    }

    /** Every list barcode with a valid check digit must pass as the kind of code it is printed as. */
    private static void list(String path) throws Exception {
        int total = 0, valid = 0, accepted = 0, viaLink = 0;
        BufferedReader in = new BufferedReader(new FileReader(path));
        for (String line; (line = in.readLine()) != null; ) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String code = line.split("\t")[0];
            total++;
            if (!ProductCode.isGtin(code)) continue;
            valid++;
            BarcodeFormat format = code.length() == 8 ? BarcodeFormat.EAN_8 : BarcodeFormat.EAN_13;
            if (code.equals(ProductCode.of(format, code))) accepted++;
            String gtin14 = "00000000000000".substring(code.length()) + code;
            if (code.equals(ProductCode.of(BarcodeFormat.QR_CODE, "https://id.gs1.org/01/" + gtin14))) viaLink++;
        }
        in.close();
        System.out.println("Liste: " + total + " Barcodes, " + valid + " mit gültiger Prüfziffer, davon angenommen "
                + accepted + ", als Digital Link " + viaLink + "; mit falscher Prüfziffer " + (total - valid));
        if (accepted != valid || viaLink != valid) failures++;
    }

    private static void same(String actual, String expected, String what) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) failures++;
        System.out.println((ok ? "OK   " : "FAIL ") + what + " -> " + actual);
    }
}
