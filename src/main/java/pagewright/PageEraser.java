package pagewright;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Removes whole areas of a page, and samples the colours of the page around text. */
final class PageEraser {

    /** Resolution of the page rendering colours are sampled from. */
    static final float SAMPLE_DPI = 144f;

    private PageEraser() {
    }

    /**
     * Removes everything inside a rectangle (points from the page's top-left): every glyph that lies inside is
     * deleted from the page content, and the area is painted over in the surrounding background colour so shapes
     * and images inside it disappear from view too. Returns how many text lines were deleted.
     */
    static int erase(File in, File out, int pageIndex, Rectangle2D.Float region) throws IOException {
        int deleted = 0;
        try (PDDocument doc = Loader.loadPDF(in)) {
            PDPage page = doc.getPage(pageIndex);
            if (page.getRotation() != 0) {
                throw new IOException("Erasing on a rotated page is not supported yet - rotate it back first");
            }
            Set<Long> targets = new HashSet<>();
            for (TextEditor.Line line : TextEditor.lines(doc, pageIndex)) {
                Rectangle2D.Float b = line.box(0, line.text.length());
                if (region.contains(b.x + 1, b.y + 1) && region.contains(b.x + b.width - 1, b.y + b.height - 1)) {
                    line.collectGlyphs(0, line.text.length(), targets);
                    deleted++;
                }
            }
            BufferedImage shot = new PDFRenderer(doc).renderImageWithDPI(pageIndex, SAMPLE_DPI);
            Color background = sampleColours(shot, region)[0];
            GlyphStripper.strip(doc, page, targets);
            PDRectangle crop = page.getCropBox();
            try (PDPageContentStream cs = new PDPageContentStream(
                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.setNonStrokingColor(background);
                cs.addRect(crop.getLowerLeftX() + region.x, crop.getUpperRightY() - (region.y + region.height),
                        region.width, region.height);
                cs.fill();
            }
            doc.save(out);
        }
        return deleted;
    }

    /** Returns {background, text} colours of the rendered region: the border's commonest pixel and the farthest one inside. */
    static Color[] sampleColours(BufferedImage img, Rectangle2D.Float b) {
        float k = SAMPLE_DPI / 72f;
        int x0 = Math.max(0, (int) ((b.x - 2) * k));
        int y0 = Math.max(0, (int) ((b.y - 1) * k));
        int x1 = Math.min(img.getWidth() - 1, (int) ((b.x + b.width + 2) * k));
        int y1 = Math.min(img.getHeight() - 1, (int) ((b.y + b.height + 1) * k));
        java.util.Map<Integer, Integer> border = new java.util.HashMap<>();
        for (int x = x0; x <= x1; x++) {
            border.merge(img.getRGB(x, y0) & 0xFFFFFF, 1, Integer::sum);
            border.merge(img.getRGB(x, y1) & 0xFFFFFF, 1, Integer::sum);
        }
        for (int y = y0; y <= y1; y++) {
            border.merge(img.getRGB(x0, y) & 0xFFFFFF, 1, Integer::sum);
            border.merge(img.getRGB(x1, y) & 0xFFFFFF, 1, Integer::sum);
        }
        int bg = border.entrySet().stream().max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey).orElse(0xFFFFFF);
        int fg = bg == 0xFFFFFF ? 0x000000 : (bg == 0x000000 ? 0xFFFFFF : 0x000000);
        long best = -1;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int rgb = img.getRGB(x, y) & 0xFFFFFF;
                long d = distance(rgb, bg);
                if (d > best) {
                    best = d;
                    fg = rgb;
                }
            }
        }
        return new Color[] {new Color(bg), new Color(fg)};
    }

    private static long distance(int a, int b) {
        long dr = ((a >> 16) & 255) - ((b >> 16) & 255);
        long dg = ((a >> 8) & 255) - ((b >> 8) & 255);
        long db = (a & 255) - (b & 255);
        return dr * dr + dg * dg + db * db;
    }
}
