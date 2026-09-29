package app.pfandcounter;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Barcode reading, kept free of Android types on purpose: the same class runs in the app and in
 * tools/DecodeTest.java on the computer, so the reading chain can be proven without a camera.
 *
 * Two readers, tried in order: the product barcodes (EAN, UPC) first, in both orientations, and
 * only then the square codes (QR, Data Matrix). A QR code reads at any angle, so trying it first
 * would win against the product barcode right next to it. The square codes are read at all so the
 * app can say "that is not the product barcode" instead of staying silent — and because the new
 * GS1 QR codes carry the product number too (ProductCode decides).
 *
 * The readers are called with decodeWithState: plain decode(image) throws the format list away
 * and reads every kind of code, which is how web-address QR codes once reached Open Food Facts.
 */
class Decoder {

    private final MultiFormatReader products = reader(Arrays.asList(
            BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E));
    private final MultiFormatReader squares = reader(Arrays.asList(
            BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX));

    private static MultiFormatReader reader(List<BarcodeFormat> formats) {
        Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        MultiFormatReader reader = new MultiFormatReader();
        reader.setHints(hints);
        return reader;
    }

    /**
     * One camera frame as the sensor delivers it. {@code rotated} must hold width*height bytes.
     * Returns null when the frame holds no readable code, which is the normal outcome.
     */
    Result readFrame(byte[] plane, int rowStride, int width, int height, byte[] rotated) {
        Result found = read(products, plane, rowStride, height, width, height);
        if (found != null) return found;
        // A bottle held upright in a portrait phone lands sideways in the sensor buffer, and
        // one-dimensional codes are only read along the buffer's own rows.
        rotate90(plane, rowStride, width, height, rotated);
        found = read(products, rotated, height, width, height, width);
        if (found != null) return found;
        return read(squares, plane, rowStride, height, width, height);
    }

    /**
     * One attempt on a grey-scale plane, in one orientation: the product number, or null — both
     * for "nothing readable" and for a code that is not a product number.
     */
    String decode(byte[] data, int dataWidth, int dataHeight, int cropWidth, int cropHeight) {
        Result found = read(products, data, dataWidth, dataHeight, cropWidth, cropHeight);
        if (found == null) found = read(squares, data, dataWidth, dataHeight, cropWidth, cropHeight);
        return found == null ? null : ProductCode.of(found.getBarcodeFormat(), found.getText());
    }

    private static Result read(MultiFormatReader reader, byte[] data, int dataWidth, int dataHeight,
                               int cropWidth, int cropHeight) {
        try {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    data, dataWidth, dataHeight, 0, 0, cropWidth, cropHeight, false);
            Result result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
            String text = result.getText();
            return text == null || text.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        } finally {
            reader.reset();
        }
    }

    /**
     * Turns a grey-scale plane a quarter turn clockwise into {@code out}, which must hold
     * width*height bytes and is then read as (height x width).
     *
     * Needed because a bottle held upright in a portrait phone lands sideways in the sensor
     * buffer, and one-dimensional codes are only read along the buffer's own rows.
     */
    static void rotate90(byte[] src, int srcStride, int width, int height, byte[] out) {
        for (int y = 0; y < height; y++) {
            int srcRow = y * srcStride;
            int dstCol = height - 1 - y;
            for (int x = 0; x < width; x++) {
                out[x * height + dstCol] = src[srcRow + x];
            }
        }
    }
}
