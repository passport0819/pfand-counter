package app.pfandcounter;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;

import java.util.Random;

/**
 * Proves the reading chain on the computer, without a camera: render a barcode, turn it into a
 * grey-scale plane the way the phone's sensor delivers one (sideways, with a row stride and a bit
 * of noise), and push it through exactly the Decoder the app uses.
 *
 *   javac -cp libs/core-3.5.3.jar -d /tmp/dt src/app/pfandcounter/Decoder.java src/app/pfandcounter/ProductCode.java tools/DecodeTest.java
 *   java -cp libs/core-3.5.3.jar:/tmp/dt app.pfandcounter.DecodeTest
 */
public class DecodeTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Decoder decoder = new Decoder();

        check(decoder, "4000000000006", BarcodeFormat.EAN_13);   // made-up valid EAN-13
        check(decoder, "4000000000013", BarcodeFormat.EAN_13);   // made-up valid EAN-13
        check(decoder, "4104420022492", BarcodeFormat.EAN_13);   // an arbitrary German EAN-13
        check(decoder, "96385074", BarcodeFormat.EAN_8);

        // What the camera actually runs: readFrame on a sideways frame, then ProductCode.
        frame(decoder, "4000000000006", null, "4000000000006", "barcode alone");
        frame(decoder, "4000000000006", "https://www.example-brauerei.de/nachhaltigkeit?id=6847025525",
                "4000000000006", "barcode with a web-address QR beside it");
        frame(decoder, null, "https://www.example-brauerei.de/nachhaltigkeit?id=6847025525",
                "QR_CODE -> null", "web-address QR alone");
        frame(decoder, null, "https://id.gs1.org/01/04000000000006",
                "QR_CODE -> 4000000000006", "GS1 Digital Link QR alone");

        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " TEST(S) FAILED");
        if (failures > 0) System.exit(1);
    }

    private static void check(Decoder decoder, String code, BarcodeFormat format) throws Exception {
        byte[] upright = render(code, format, 720, 480);
        report(code + " upright", code, decoder.decode(upright, 720, 480, 720, 480));

        // The realistic case: barcode sideways in the sensor buffer, stride wider than the image.
        int width = 480, height = 720, stride = 512;
        byte[] sideways = renderWithStride(code, format, width, height, stride);
        String direct = decoder.decode(sideways, stride, height, width, height);
        byte[] rotated = new byte[width * height];
        Decoder.rotate90(sideways, stride, width, height, rotated);
        String viaRotation = decoder.decode(rotated, height, width, height, width);
        report(code + " sideways via rotate90", code, viaRotation);
        System.out.println("   (without the rotation step this frame read as: " + direct + ")");
    }

    /** A portrait frame (sideways in the buffer) with a barcode in the upper and a QR in the lower half. */
    private static void frame(Decoder decoder, String ean, String qr, String expected, String what)
            throws Exception {
        int width = 1280, height = 720, stride = 1280;       // sensor buffer, landscape
        byte[] plane = new byte[stride * height];
        java.util.Arrays.fill(plane, (byte) 235);
        // In the upright picture (720 wide, 1280 high) the barcode sits on top, the QR below it.
        if (ean != null) {
            BitMatrix m = new MultiFormatWriter().encode(ean, BarcodeFormat.EAN_13, 560, 260);
            drawUpright(plane, stride, width, height, m, 80, 200);
        }
        if (qr != null) {
            BitMatrix m = new MultiFormatWriter().encode(qr, BarcodeFormat.QR_CODE, 300, 300);
            drawUpright(plane, stride, width, height, m, 210, 700);
        }
        byte[] rotated = new byte[width * height];
        Result read = decoder.readFrame(plane, stride, width, height, rotated);
        String actual;
        if (read == null) {
            actual = null;
        } else {
            String product = ProductCode.of(read.getBarcodeFormat(), read.getText());
            actual = read.getBarcodeFormat() == BarcodeFormat.EAN_13 ? product
                    : read.getBarcodeFormat() + " -> " + product;
        }
        report("frame: " + what, expected, actual);
    }

    /** Draws into the landscape buffer what appears upright at (ux, uy) on a portrait screen. */
    private static void drawUpright(byte[] plane, int stride, int width, int height, BitMatrix m,
                                    int ux, int uy) {
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                if (!m.get(x, y)) continue;
                int px = uy + y;                   // upright row -> buffer column
                int py = height - 1 - (ux + x);     // upright column -> buffer row, turned
                if (px < 0 || py < 0 || px >= width || py >= height) continue;
                plane[py * stride + px] = (byte) 25;
            }
        }
    }

    private static void report(String what, String expected, String actual) {
        boolean ok = expected.equals(actual);
        if (!ok) failures++;
        System.out.println((ok ? "OK   " : "FAIL ") + what + " -> " + actual);
    }

    /** A barcode drawn upright, mild noise, rowStride == width. */
    private static byte[] render(String code, BarcodeFormat format, int width, int height)
            throws Exception {
        return renderWithStride(code, format, width, height, width);
    }

    /**
     * Draws the code rotated a quarter turn counter-clockwise into a buffer of the given stride,
     * which is what the camera hands over when the phone is held upright.
     */
    private static byte[] renderWithStride(String code, BarcodeFormat format,
                                           int width, int height, int stride) throws Exception {
        boolean sideways = height > width;
        int codeWidth = sideways ? height : width;
        int codeHeight = sideways ? width : height;
        BitMatrix matrix = new MultiFormatWriter().encode(code, format,
                (int) (codeWidth * 0.8), (int) (codeHeight * 0.5));

        byte[] plane = new byte[stride * height];
        java.util.Arrays.fill(plane, (byte) 235);           // paper white
        int offsetX = (codeWidth - matrix.getWidth()) / 2;
        int offsetY = (codeHeight - matrix.getHeight()) / 2;
        Random noise = new Random(7);

        for (int my = 0; my < matrix.getHeight(); my++) {
            for (int mx = 0; mx < matrix.getWidth(); mx++) {
                if (!matrix.get(mx, my)) continue;
                int cx = mx + offsetX, cy = my + offsetY;
                int px, py;
                if (sideways) {
                    px = cy;                       // quarter turn counter-clockwise
                    py = height - 1 - cx;
                } else {
                    px = cx;
                    py = cy;
                }
                if (px < 0 || py < 0 || px >= width || py >= height) continue;
                plane[py * stride + px] = (byte) 25;        // ink
            }
        }
        for (int i = 0; i < plane.length; i++) {
            plane[i] = (byte) Math.max(0, Math.min(255, (plane[i] & 0xFF) + noise.nextInt(11) - 5));
        }
        return plane;
    }
}
