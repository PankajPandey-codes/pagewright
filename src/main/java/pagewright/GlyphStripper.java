package pagewright;

import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Deletes individual glyphs from a page's content streams. A glyph is addressed by the number of the text-showing
 * operator (Tj or TJ, counted in the order the page is interpreted, forms included) and its position within it,
 * which is exactly what {@link TextEditor#lines} reports for every character. Addressing by position instead of by
 * text means identical words elsewhere on the page are never touched.
 */
final class GlyphStripper {

    private GlyphStripper() {
    }

    static long key(int operator, int glyph) {
        return ((long) operator << 24) | glyph;
    }

    /** Removes the glyphs, kerning so that later glyphs of the same operator stay where they were. */
    static void strip(PDDocument doc, PDPage page, Set<Long> targets) throws IOException {
        if (targets.isEmpty()) {
            return;
        }
        PDStream rewritten = stripIn(doc, page, page.getResources(), targets, new int[] {-1});
        if (rewritten != null) {
            page.setContents(rewritten);
        }
    }

    private static PDStream stripIn(PDDocument doc, PDContentStream content, PDResources res, Set<Long> targets,
            int[] counter) throws IOException {
        if (res == null) {
            return null;
        }
        List<Object> tokens = new PDFStreamParser(content).parse();
        List<PDFont> savedFonts = new ArrayList<>();
        PDFont font = null;
        boolean changed = false;
        for (int i = 0; i < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof Operator)) {
                continue;
            }
            String op = ((Operator) tokens.get(i)).getName();
            if ("q".equals(op)) {
                savedFonts.add(font);
            } else if ("Q".equals(op)) {
                if (!savedFonts.isEmpty()) {
                    font = savedFonts.remove(savedFonts.size() - 1);
                }
            } else if ("Tf".equals(op) && i >= 2 && tokens.get(i - 2) instanceof COSName) {
                font = res.getFont((COSName) tokens.get(i - 2));
            } else if ("Do".equals(op) && i >= 1 && tokens.get(i - 1) instanceof COSName) {
                PDXObject xo = res.getXObject((COSName) tokens.get(i - 1));
                if (xo instanceof PDFormXObject) {
                    PDFormXObject form = (PDFormXObject) xo;
                    PDStream rewritten = stripIn(doc, form, form.getResources() != null ? form.getResources() : res,
                            targets, counter);
                    if (rewritten != null) {
                        try (OutputStream os = form.getContentStream().createOutputStream(COSName.FLATE_DECODE);
                                InputStream is = rewritten.createInputStream()) {
                            is.transferTo(os);
                        }
                    }
                }
            } else if ("Tj".equals(op) || "TJ".equals(op) || "'".equals(op) || "\"".equals(op)) {
                counter[0]++;
                if (font == null || i < 1 || "'".equals(op) || "\"".equals(op) || !hasTarget(targets, counter[0])) {
                    continue;
                }
                Object arg = tokens.get(i - 1);
                COSArray in = null;
                if (arg instanceof COSString) {
                    in = new COSArray();
                    in.add((COSString) arg);
                } else if (arg instanceof COSArray) {
                    in = (COSArray) arg;
                }
                try {
                    COSArray out = in == null ? null : rewrite(in, font, counter[0], targets);
                    if (out != null) {
                        tokens.set(i - 1, out);
                        tokens.set(i, Operator.getOperator("TJ"));
                        changed = true;
                    }
                } catch (IOException | RuntimeException e) {
                    // Undecodable string: leave it; the cover painted over it still hides it.
                }
            }
        }
        if (!changed) {
            return null;
        }
        PDStream stream = new PDStream(doc);
        try (OutputStream os = stream.createOutputStream(COSName.FLATE_DECODE)) {
            new ContentStreamWriter(os).writeTokens(tokens);
        }
        return stream;
    }

    private static boolean hasTarget(Set<Long> targets, int operator) {
        long low = key(operator, 0);
        long high = key(operator + 1, 0);
        for (long t : targets) {
            if (t >= low && t < high) {
                return true;
            }
        }
        return false;
    }

    private static COSArray rewrite(COSArray array, PDFont font, int operator, Set<Long> targets)
            throws IOException {
        COSArray out = new COSArray();
        ByteArrayOutputStream keep = new ByteArrayOutputStream();
        float gap = 0;
        int glyph = 0;
        boolean any = false;
        for (COSBase b : array) {
            if (b instanceof COSNumber) {
                flush(out, keep);
                if (gap != 0) {
                    out.add(new COSFloat(-gap));
                    gap = 0;
                }
                out.add(b);
            } else if (b instanceof COSString) {
                byte[] all = ((COSString) b).getBytes();
                ByteArrayInputStream in = new ByteArrayInputStream(all);
                while (in.available() > 0) {
                    int start = all.length - in.available();
                    int code = font.readCode(in);
                    int end = all.length - in.available();
                    if (targets.contains(key(operator, glyph++))) {
                        gap += font.getWidth(code);
                        any = true;
                        continue;
                    }
                    if (gap != 0) {
                        flush(out, keep);
                        out.add(new COSFloat(-gap));
                        gap = 0;
                    }
                    keep.write(all, start, end - start);
                }
            }
        }
        flush(out, keep);
        if (gap != 0) {
            out.add(new COSFloat(-gap));
        }
        return any ? out : null;
    }

    private static void flush(COSArray out, ByteArrayOutputStream keep) {
        if (keep.size() > 0) {
            out.add(new COSString(keep.toByteArray()));
            keep.reset();
        }
    }
}
