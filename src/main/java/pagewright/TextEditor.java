package pagewright;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds and replaces existing text in a PDF. */
public class TextEditor {

    /** A gap wider than this (in em) separates words; wider than COLUMN_GAP_EM starts a new "line" (column). */
    private static final float SPACE_GAP_EM = 0.25f;
    private static final float COLUMN_GAP_EM = 2.5f;
    /** TJ adjustments in thousandths of an em: beyond WORD_GAP is a visual space, beyond LAYOUT_GAP is positioning. */
    private static final float WORD_GAP_KERN = 250f;
    private static final float LAYOUT_GAP_KERN = 1500f;

    public static class Result {
        public int inPlace;
        public int coveredAndOverwritten;
        public int skipped;

        public String describe() {
            return "Replaced " + inPlace + " in place, " + coveredAndOverwritten
                    + " by covering the old text (original text still exists hidden underneath), "
                    + skipped + " not replaced";
        }
    }

    /** One visual line of text on a page; box coordinates are points measured from the top-left. */
    public static class Line {
        public final String text;
        private final List<TextPosition> tps;
        private final List<Integer> tpOfChar;

        Line(String text, List<TextPosition> tps, List<Integer> tpOfChar) {
            this.text = text;
            this.tps = tps;
            this.tpOfChar = tpOfChar;
        }

        /** Index of the character whose centre is nearest to x (points from the left). */
        public int charAt(float x) {
            int best = 0;
            float bestDist = Float.MAX_VALUE;
            for (int i = 0; i < text.length(); i++) {
                TextPosition tp = tps.get(tpOfChar.get(i));
                float dist = Math.abs(tp.getXDirAdj() + tp.getWidthDirAdj() / 2f - x);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = i;
                }
            }
            return best;
        }

        public Rectangle2D.Float box(int start, int end) {
            TextPosition first = tps.get(tpOfChar.get(start));
            TextPosition last = tps.get(tpOfChar.get(end - 1));
            float size = first.getFontSizeInPt();
            float x0 = first.getXDirAdj();
            float x1 = last.getXDirAdj() + last.getWidthDirAdj();
            return new Rectangle2D.Float(x0, first.getYDirAdj() - size * 0.85f, x1 - x0, size * 1.05f);
        }
    }

    public static class Hit {
        public final Line line;
        public final int charIndex;

        Hit(Line line, int charIndex) {
            this.line = line;
            this.charIndex = charIndex;
        }

        public int[] wordRange() {
            int s = charIndex;
            int e = charIndex + 1;
            while (s > 0 && !Character.isWhitespace(line.text.charAt(s - 1))) {
                s--;
            }
            while (e < line.text.length() && !Character.isWhitespace(line.text.charAt(e))) {
                e++;
            }
            return new int[] {s, e};
        }

        /** The sentence around the hit: bounded by . ! ? or the ends of the line. */
        public int[] sentenceRange() {
            String t = line.text;
            int s = charIndex;
            while (s > 0 && ".!?".indexOf(t.charAt(s - 1)) < 0) {
                s--;
            }
            while (s < t.length() && Character.isWhitespace(t.charAt(s))) {
                s++;
            }
            int e = charIndex;
            while (e < t.length() && ".!?".indexOf(t.charAt(e)) < 0) {
                e++;
            }
            if (e < t.length()) {
                e++;
            }
            return s < e ? new int[] {s, e} : lineRange();
        }

        public int[] lineRange() {
            return new int[] {0, line.text.length()};
        }
    }

    public static List<Line> lines(PDDocument doc, int pageIndex) throws IOException {
        List<List<TextPosition>> rows = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processTextPosition(TextPosition tp) {
                for (List<TextPosition> row : rows) {
                    if (Math.abs(row.get(0).getYDirAdj() - tp.getYDirAdj()) <= 0.4f * tp.getFontSizeInPt()) {
                        row.add(tp);
                        return;
                    }
                }
                List<TextPosition> row = new ArrayList<>();
                row.add(tp);
                rows.add(row);
            }
        };
        stripper.setStartPage(pageIndex + 1);
        stripper.setEndPage(pageIndex + 1);
        stripper.getText(doc);

        List<Line> result = new ArrayList<>();
        for (List<TextPosition> row : rows) {
            row.sort(Comparator.comparing(TextPosition::getXDirAdj));
            int from = 0;
            for (int k = 1; k <= row.size(); k++) {
                boolean columnBreak = k == row.size()
                        || horizontalGap(row.get(k - 1), row.get(k)) > COLUMN_GAP_EM * row.get(k).getFontSizeInPt();
                if (columnBreak) {
                    Line line = buildLine(row.subList(from, k));
                    if (line != null) {
                        result.add(line);
                    }
                    from = k;
                }
            }
        }
        return result;
    }

    private static float horizontalGap(TextPosition prev, TextPosition tp) {
        return tp.getXDirAdj() - (prev.getXDirAdj() + prev.getWidthDirAdj());
    }

    private static Line buildLine(List<TextPosition> segment) {
        List<TextPosition> row = new ArrayList<>(segment);
        StringBuilder sb = new StringBuilder();
        List<Integer> map = new ArrayList<>();
        TextPosition prev = null;
        for (int k = 0; k < row.size(); k++) {
            TextPosition tp = row.get(k);
            String u = tp.getUnicode() == null ? "" : tp.getUnicode();
            if (prev != null && !" ".equals(prev.getUnicode()) && !" ".equals(u)
                    && horizontalGap(prev, tp) > SPACE_GAP_EM * tp.getFontSizeInPt()) {
                sb.append(' ');
                map.add(k - 1);
            }
            for (int c = 0; c < u.length(); c++) {
                sb.append(u.charAt(c));
                map.add(k);
            }
            prev = tp;
        }
        return sb.length() > 0 ? new Line(sb.toString(), row, map) : null;
    }

    /** Finds the character under a point given in points from the page's top-left. */
    public static Hit hit(PDDocument doc, int pageIndex, float x, float y) throws IOException {
        return hit(lines(doc, pageIndex), x, y);
    }

    public static Hit hit(List<Line> lines, float x, float y) {
        for (Line line : lines) {
            for (int i = 0; i < line.text.length(); i++) {
                TextPosition tp = line.tps.get(line.tpOfChar.get(i));
                float size = tp.getFontSizeInPt();
                boolean inX = x >= tp.getXDirAdj() && x <= tp.getXDirAdj() + tp.getWidthDirAdj();
                boolean inY = y >= tp.getYDirAdj() - size && y <= tp.getYDirAdj() + size * 0.3f;
                if (inX && inY) {
                    return new Hit(line, i);
                }
            }
        }
        return null;
    }

    public static Result replace(File in, File out, List<Integer> pages, String find, String repl)
            throws IOException {
        if (find.isEmpty()) {
            throw new IllegalArgumentException("Find text is empty");
        }
        Result result = new Result();
        try (PDDocument doc = Loader.loadPDF(in)) {
            for (int p : pages) {
                PDPage page = doc.getPage(p);
                int replaced = replaceInStream(doc, page, find, repl);
                result.inPlace += replaced;
                // When the replacement contains the search text, matches left after an in-place edit would be
                // the new text itself, so only cover if nothing was replaced in place.
                if (replaced == 0 || !repl.contains(find)) {
                    coverRemaining(doc, p, find, repl, result);
                }
            }
            doc.save(out);
        }
        return result;
    }

    private static int replaceInStream(PDDocument doc, PDPage page, String find, String repl)
            throws IOException {
        PDResources res = page.getResources();
        if (res == null) {
            return 0;
        }
        List<Object> tokens = new PDFStreamParser(page).parse();
        List<PDFont> savedFonts = new ArrayList<>();
        PDFont font = null;
        int count = 0;
        for (int i = 0; i < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof Operator)) {
                continue;
            }
            String op = ((Operator) tokens.get(i)).getName();
            if ("q".equals(op)) {
                savedFonts.add(font);
                continue;
            }
            if ("Q".equals(op)) {
                if (!savedFonts.isEmpty()) {
                    font = savedFonts.remove(savedFonts.size() - 1);
                }
                continue;
            }
            if ("Tf".equals(op) && i >= 2 && tokens.get(i - 2) instanceof COSName) {
                font = res.getFont((COSName) tokens.get(i - 2));
                continue;
            }
            if (font == null || i < 1) {
                continue;
            }
            Object arg = tokens.get(i - 1);
            try {
                if (("Tj".equals(op) || "'".equals(op) || "\"".equals(op)) && arg instanceof COSString) {
                    String s = decode(font, ((COSString) arg).getBytes());
                    int n = occurrences(s, find);
                    if (n > 0) {
                        tokens.set(i - 1, new COSString(font.encode(s.replace(find, repl))));
                        count += n;
                    }
                } else if ("TJ".equals(op) && arg instanceof COSArray) {
                    String s = joinTj((COSArray) arg, font);
                    int n = s == null ? 0 : occurrences(s, find);
                    if (n > 0) {
                        COSArray replaced = new COSArray();
                        replaced.add(new COSString(font.encode(s.replace(find, repl))));
                        tokens.set(i - 1, replaced);
                        count += n;
                    }
                }
            } catch (IOException | RuntimeException e) {
                // The font cannot decode this string or has no glyph for the new text (typical of subset
                // fonts); coverRemaining() handles the leftover match and the result reports it.
            }
        }
        if (count > 0) {
            PDStream stream = new PDStream(doc);
            try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
                new ContentStreamWriter(os).writeTokens(tokens);
            }
            page.setContents(stream);
        }
        return count;
    }

    /** Decodes a TJ array to text, turning word-sized kerning gaps into spaces; null if it uses layout gaps. */
    private static String joinTj(COSArray array, PDFont font) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (COSBase b : array) {
            if (b instanceof COSString) {
                sb.append(decode(font, ((COSString) b).getBytes()));
            } else if (b instanceof COSNumber) {
                float kern = ((COSNumber) b).floatValue();
                if (kern < -LAYOUT_GAP_KERN) {
                    return null;
                }
                if (kern < -WORD_GAP_KERN && sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
            }
        }
        return sb.toString();
    }

    private static void coverRemaining(PDDocument doc, int pageIndex, String find, String repl, Result result)
            throws IOException {
        PDPage page = doc.getPage(pageIndex);
        List<float[]> targets = new ArrayList<>();
        List<Boolean> bold = new ArrayList<>();
        List<Rectangle2D.Float> boxes = new ArrayList<>();
        List<Float> sizes = new ArrayList<>();
        for (Line line : lines(doc, pageIndex)) {
            for (int at = line.text.indexOf(find); at >= 0; at = line.text.indexOf(find, at + find.length())) {
                if (page.getRotation() != 0) {
                    result.skipped++;
                    continue;
                }
                TextPosition first = line.tps.get(line.tpOfChar.get(at));
                boxes.add(line.box(at, at + find.length()));
                sizes.add(first.getFontSizeInPt());
                bold.add(first.getFont().getName() != null && first.getFont().getName().toLowerCase().contains("bold"));
                targets.add(new float[] {first.getXDirAdj(), first.getYDirAdj()});
            }
        }
        if (boxes.isEmpty()) {
            return;
        }
        boolean latin = true;
        try {
            new PDType1Font(Standard14Fonts.FontName.HELVETICA).getStringWidth(repl);
        } catch (IllegalArgumentException e) {
            latin = false;
        }
        if (!latin && !UnicodeFonts.face(repl, false).missing(repl).isEmpty()) {
            result.skipped += boxes.size();
            return;
        }
        final boolean useHelvetica = latin;
        PDRectangle crop = page.getCropBox();
        try (PDPageContentStream cs = new PDPageContentStream(
                doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            for (int i = 0; i < boxes.size(); i++) {
                Rectangle2D.Float b = boxes.get(i);
                float pdfBottom = crop.getUpperRightY() - (b.y + b.height);
                cs.setNonStrokingColor(Color.WHITE);
                cs.addRect(crop.getLowerLeftX() + b.x - 1, pdfBottom, b.width + 2, b.height);
                cs.fill();
                cs.setNonStrokingColor(Color.BLACK);
                PDFont overlayFont = useHelvetica
                        ? new PDType1Font(bold.get(i)
                                ? Standard14Fonts.FontName.HELVETICA_BOLD : Standard14Fonts.FontName.HELVETICA)
                        : UnicodeFonts.pdf(doc, UnicodeFonts.face(repl, bold.get(i)));
                float naturalWidth = overlayFont.getStringWidth(repl) / 1000f * sizes.get(i);
                // Shrink (never grow) so the new text does not run into the neighbouring text.
                float fitted = naturalWidth > b.width ? sizes.get(i) * b.width / naturalWidth : sizes.get(i);
                cs.beginText();
                cs.setFont(overlayFont, fitted);
                cs.newLineAtOffset(crop.getLowerLeftX() + targets.get(i)[0], crop.getUpperRightY() - targets.get(i)[1]);
                cs.showText(repl);
                cs.endText();
                result.coveredAndOverwritten++;
            }
        }
    }

    private static String decode(PDFont font, byte[] bytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        while (in.available() > 0) {
            String u = font.toUnicode(font.readCode(in));
            sb.append(u == null ? "�" : u);
        }
        return sb.toString();
    }

    private static int occurrences(String s, String find) {
        int n = 0;
        for (int at = s.indexOf(find); at >= 0; at = s.indexOf(find, at + find.length())) {
            n++;
        }
        return n;
    }
}
