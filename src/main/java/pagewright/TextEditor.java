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
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.Color;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
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

    /** How replacement text is drawn when the original font cannot be reused; null fields mean "match the original". */
    public static class Style {
        public static final Style ORIGINAL = new Style();
        public float size;
        public Boolean bold;
        public Color color;
        /** "auto", "left" or "right": which edge of the old text stays put when the new text is a different width. */
        public String align = "auto";
        /** Draw the replacement as a movable Arial item instead of in the page's own font. */
        public boolean movable;
    }

    public static class Result {
        public int inPlace;
        public int coveredAndOverwritten;
        public int skipped;
        /** Replacement text left as movable items for the caller to place on the document. */
        public final List<Annotation> items = new ArrayList<>();

        public String describe() {
            return "Replaced " + inPlace + " in place, " + coveredAndOverwritten
                    + " as movable text, "
                    + skipped + " not replaced";
        }
    }

    /** One visual line of text on a page; box coordinates are points measured from the top-left. */
    public static class Line {
        public final String text;
        private final List<TextPosition> tps;
        private final List<Integer> tpOfChar;
        /** For each text position: the operator it came from and its index within that operator. */
        private final List<int[]> glyphs;

        Line(String text, List<TextPosition> tps, List<Integer> tpOfChar, List<int[]> glyphs) {
            this.text = text;
            this.tps = tps;
            this.tpOfChar = tpOfChar;
            this.glyphs = glyphs;
        }

        /** Adds the stream addresses of the glyphs (spaces drawn as glyphs included) of text[start, end) to out. */
        void collectGlyphs(int start, int end, java.util.Set<Long> out) {
            for (int i = start; i < end; i++) {
                TextPosition tp = tps.get(tpOfChar.get(i));
                boolean synthetic = Character.isWhitespace(text.charAt(i))
                        && (tp.getUnicode() == null || !tp.getUnicode().isBlank());
                if (!synthetic) {
                    int[] g = glyphs.get(tpOfChar.get(i));
                    out.add(GlyphStripper.key(g[0], g[1]));
                }
            }
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
        java.util.Map<TextPosition, int[]> origin = new java.util.IdentityHashMap<>();
        int[] counters = {-1, 0};
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
                if ("Tj".equals(operator.getName()) || "TJ".equals(operator.getName())) {
                    counters[0]++;
                    counters[1] = 0;
                }
                super.processOperator(operator, operands);
            }

            @Override
            protected void processTextPosition(TextPosition tp) {
                origin.put(tp, new int[] {counters[0], counters[1]++});
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
                    Line line = buildLine(row.subList(from, k), origin);
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

    private static Line buildLine(List<TextPosition> segment, java.util.Map<TextPosition, int[]> origin) {
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
        List<int[]> glyphs = new ArrayList<>();
        for (TextPosition tp : row) {
            glyphs.add(origin.get(tp));
        }
        return sb.length() > 0 ? new Line(sb.toString(), row, map, glyphs) : null;
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
        return replace(in, out, pages, find, repl, Style.ORIGINAL);
    }

    public static Result replace(File in, File out, List<Integer> pages, String find, String repl, Style style)
            throws IOException {
        if (find.isEmpty()) {
            throw new IllegalArgumentException("Find text is empty");
        }
        Result result = new Result();
        try (PDDocument doc = Loader.loadPDF(in)) {
            for (int p : pages) {
                coverRemaining(doc, p, find, repl, style, result);
            }
            doc.save(out);
        }
        return result;
    }

    /** One piece of text to take off the page and, if {@code text} is non-empty, put back as a movable item. */
    private static final class Spot {
        Rectangle2D.Float box;
        float size;
        boolean bold;
        float x;
        float baseline;
        String text;
        boolean right;
        boolean fixedStyle;
        PDFont font;
        Spot tail;
        Color[] colours;
        Line line;
        int from;
        int to;
    }

    private static void coverRemaining(PDDocument doc, int pageIndex, String find, String repl, Style style,
            Result result) throws IOException {
        PDPage page = doc.getPage(pageIndex);
        float pageWidth = page.getCropBox().getWidth();
        List<Line> pageLines = lines(doc, pageIndex);
        if (page.getRotation() != 0) {
            for (Line line : pageLines) {
                for (int at = line.text.indexOf(find); at >= 0; at = line.text.indexOf(find, at + find.length())) {
                    result.skipped++;
                }
            }
            return;
        }
        if (!repl.isEmpty() && !UnicodeFonts.face(repl, false).missing(repl).isEmpty()) {
            for (Line line : pageLines) {
                for (int at = line.text.indexOf(find); at >= 0; at = line.text.indexOf(find, at + find.length())) {
                    result.skipped++;
                }
            }
            return;
        }
        List<Spot> spots = new ArrayList<>();
        List<Spot> trailing = new ArrayList<>();
        for (Line line : pageLines) {
            for (int at = line.text.indexOf(find); at >= 0; at = line.text.indexOf(find, at + find.length())) {
                TextPosition first = line.tps.get(line.tpOfChar.get(at));
                Spot sp = new Spot();
                sp.box = line.box(at, at + find.length());
                sp.size = first.getFontSizeInPt();
                String fontName = first.getFont().getName() == null ? "" : first.getFont().getName().toLowerCase();
                sp.bold = fontName.contains("bold") || fontName.contains("black");
                sp.x = first.getXDirAdj();
                sp.baseline = first.getYDirAdj();
                sp.text = repl;
                sp.font = first.getFont();
                sp.line = line;
                sp.from = at;
                sp.to = at + find.length();
                boolean startsLine = at == 0 && !hasNeighbourOnLeft(pageLines, line, sp.box);
                Rectangle2D.Float lineBox = line.box(0, line.text.length());
                boolean rightColumn = startsLine && lineBox.x + lineBox.width > pageWidth * 0.55f;
                boolean endsLine = at + find.length() >= line.text.trim().length();
                sp.right = endsLine && rightColumn;
                spots.add(sp);
                float newWidth = widthOf(sp, repl, style.size > 0 ? style.size : sp.size, style, false);
                int tailStart = at + find.length();
                while (tailStart < line.text.length() && Character.isWhitespace(line.text.charAt(tailStart))) {
                    tailStart++;
                }
                float shift = newWidth - sp.box.width;
                if (!repl.isEmpty() && Math.abs(shift) > 0.5f && !sp.right && tailStart < line.text.length()) {
                    TextPosition head = line.tps.get(line.tpOfChar.get(tailStart));
                    Spot tail = new Spot();
                    tail.text = line.text.substring(tailStart);
                    tail.box = line.box(tailStart, line.text.length());
                    tail.size = head.getFontSizeInPt();
                    String tailFont = head.getFont().getName() == null ? "" : head.getFont().getName().toLowerCase();
                    tail.bold = tailFont.contains("bold") || tailFont.contains("black");
                    tail.x = head.getXDirAdj() + (rightColumn ? 0 : shift);
                    if (rightColumn) {
                        sp.x -= shift;
                    }
                    tail.baseline = head.getYDirAdj();
                    tail.fixedStyle = true;
                    sp.tail = tail;
                    tail.font = head.getFont();
                    tail.line = line;
                    tail.from = at + find.length();
                    tail.to = line.text.length();
                    trailing.add(tail);
                }
            }
        }
        List<Spot> moved = trailing;
        if (spots.isEmpty()) {
            return;
        }
        BufferedImage shot = new PDFRenderer(doc).renderImageWithDPI(pageIndex, PageEraser.SAMPLE_DPI);
        List<Spot> all = new ArrayList<>(spots);
        all.addAll(moved);
        List<Color[]> colours = new ArrayList<>();
        for (Spot sp : all) {
            sp.colours = PageEraser.sampleColours(shot, sp.box);
            colours.add(sp.colours);
        }
        java.util.Set<Long> targets = new java.util.HashSet<>();
        for (Spot sp : all) {
            sp.line.collectGlyphs(sp.from, sp.to, targets);
        }
        GlyphStripper.strip(doc, page, targets);
        PDRectangle crop = page.getCropBox();
        int matches = spots.size();
        List<Annotation> tailItems = new ArrayList<>();
        try (PDPageContentStream cs = new PDPageContentStream(
                doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            for (int i = 0; i < all.size(); i++) {
                Rectangle2D.Float b = all.get(i).box;
                cs.setNonStrokingColor(colours.get(i)[0]);
                cs.addRect(crop.getLowerLeftX() + b.x - 1, crop.getUpperRightY() - (b.y + b.height), b.width + 2,
                        b.height);
                cs.fill();
            }
            for (int i = 0; i < matches; i++) {
                Spot sp = all.get(i);
                result.coveredAndOverwritten++;
                // A match and its pushed-along tail share one text object, so later matching can follow the
                // visual line across them but never across unrelated text.
                boolean open = false;
                open = place(sp, cs, crop, style, pageIndex, result.items, tailItems, open);
                if (sp.tail != null && sp.tail.text != null && moved.contains(sp.tail)) {
                    open = place(sp.tail, cs, crop, style, pageIndex, result.items, tailItems, open);
                }
                if (open) {
                    cs.endText();
                }
            }
        }
        if (!tailItems.isEmpty()) {
            AnnotationBaker.drawOnPage(doc, page, tailItems, new java.util.HashMap<>());
        }
    }

    /** Draws one spot in its original font inside the open text object, or queues it as an Arial item. */
    private static boolean place(Spot sp, PDPageContentStream cs, PDRectangle crop, Style style, int pageIndex,
            List<Annotation> movable, List<Annotation> tails, boolean open) throws IOException {
        if (sp.text.isEmpty()) {
            return open;
        }
        float size = !sp.fixedStyle && style.size > 0 ? style.size : sp.size;
        Color colour = !sp.fixedStyle && style.color != null ? style.color : sp.colours[1];
        boolean right = !sp.fixedStyle && ("right".equals(style.align) || ("auto".equals(style.align) && sp.right));
        boolean exact = canUseOriginalFont(sp, !sp.fixedStyle && style.movable, style);
        float width = widthOf(sp, sp.text, size, style, exact);
        float x = right ? sp.box.x + sp.box.width - width : sp.x;
        if (exact) {
            if (!open) {
                cs.beginText();
            }
            cs.setNonStrokingColor(colour);
            cs.setFont(sp.font, size);
            cs.setTextMatrix(org.apache.pdfbox.util.Matrix.getTranslateInstance(
                    crop.getLowerLeftX() + x, crop.getUpperRightY() - sp.baseline));
            cs.showText(sp.text);
            return true;
        }
        Annotation item = Annotation.text(pageIndex, x, 0, sp.text);
        item.fontSize = size;
        item.bold = !sp.fixedStyle && style.bold != null ? style.bold : sp.bold;
        item.color = colour;
        item.refreshTextBounds();
        item.x = right ? sp.box.x + sp.box.width - item.w : sp.x;
        item.y = sp.baseline - Annotation.BASELINE * item.fontSize;
        (sp.fixedStyle ? tails : movable).add(item);
        return open;
    }

    /** True when the text can be drawn in the font it replaces: the font has every glyph and nothing overrides it. */
    private static boolean canUseOriginalFont(Spot sp, boolean movable, Style style) {
        if (movable || sp.font == null || (!sp.fixedStyle && style.bold != null && style.bold != sp.bold)) {
            return false;
        }
        try {
            sp.font.encode(sp.text);
            sp.font.getStringWidth(sp.text);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static float widthOf(Spot sp, String text, float size, Style style, boolean knownExact) {
        if (text.isEmpty()) {
            return 0;
        }
        Spot probe = new Spot();
        probe.font = sp.font;
        probe.text = text;
        probe.fixedStyle = sp.fixedStyle;
        probe.bold = sp.bold;
        if (knownExact || canUseOriginalFont(probe, !sp.fixedStyle && style.movable, style)) {
            try {
                return sp.font.getStringWidth(text) / 1000f * size;
            } catch (IOException | RuntimeException e) {
                // fall through to the Arial measure
            }
        }
        return textWidth(text, size, !sp.fixedStyle && style.bold != null ? style.bold : sp.bold);
    }

    private static float textWidth(String text, float size, boolean bold) {
        Annotation probe = Annotation.text(0, 0, 0, text);
        probe.fontSize = size;
        probe.bold = bold;
        probe.refreshTextBounds();
        return probe.w;
    }

    /** True if other text (a currency symbol, a label) ends just before the box on the same baseline. */
    private static boolean hasNeighbourOnLeft(List<Line> all, Line own, Rectangle2D.Float box) {
        for (Line other : all) {
            if (other == own) {
                continue;
            }
            Rectangle2D.Float ob = other.box(0, other.text.length());
            float size = box.height;
            boolean sameRow = Math.abs((ob.y + ob.height) - (box.y + box.height)) < size * 0.5f;
            float gap = box.x - (ob.x + ob.width);
            if (sameRow && gap > -1f && gap < size * 1.5f) {
                return true;
            }
        }
        return false;
    }

}
