package pagewright;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;

import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Draws annotations permanently into a copy of a PDF. */
final class AnnotationBaker {

    private AnnotationBaker() {
    }

    static void bake(File in, File out, List<Annotation> annotations) throws IOException {
        try (PDDocument doc = Loader.loadPDF(in)) {
            Map<File, PDType0Font> fonts = new HashMap<>();
            for (int p = 0; p < doc.getNumberOfPages(); p++) {
                final int pageIndex = p;
                List<Annotation> onPage = annotations.stream().filter(a -> a.page == pageIndex)
                        .collect(java.util.stream.Collectors.toList());
                if (!onPage.isEmpty()) {
                    drawOnPage(doc, doc.getPage(p), onPage, fonts);
                }
            }
            doc.save(out);
        }
    }

    static void drawOnPage(PDDocument doc, PDPage page, List<Annotation> items,
                                   Map<File, PDType0Font> fonts) throws IOException {
        if (page.getRotation() != 0) {
            throw new IOException("Annotations on rotated pages are not supported");
        }
        PDRectangle crop = page.getCropBox();
        float left = crop.getLowerLeftX();
        float top = crop.getUpperRightY();
        try (PDPageContentStream cs = new PDPageContentStream(
                doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            for (Annotation a : items) {
                switch (a.type) {
                    case TEXT:
                        drawText(cs, a, left, top, doc, fonts);
                        break;
                    case IMAGE:
                        PDImageXObject img = PDImageXObject.createFromFileByContent(a.imageFile, doc);
                        cs.drawImage(img, left + a.x, top - a.y - a.h, a.w, a.h);
                        break;
                    case RECT:
                        Rectangle2D.Float b = a.bounds();
                        cs.addRect(left + b.x, top - b.y - b.height, b.width, b.height);
                        if (a.filled) {
                            cs.setNonStrokingColor(a.color);
                            cs.fill();
                        } else {
                            cs.setStrokingColor(a.color);
                            cs.setLineWidth(a.strokeWidth);
                            cs.stroke();
                        }
                        break;
                    case LINE:
                        cs.setStrokingColor(a.color);
                        cs.setLineWidth(a.strokeWidth);
                        cs.moveTo(left + a.x, top - a.y);
                        cs.lineTo(left + a.x + a.w, top - a.y - a.h);
                        cs.stroke();
                        break;
                    default:
                        throw new IllegalStateException("Unknown annotation type " + a.type);
                }
            }
        }
    }

    private static void drawText(PDPageContentStream cs, Annotation a, float left, float top, PDDocument doc,
                                 Map<File, PDType0Font> fonts) throws IOException {
        UnicodeFonts.Face face = UnicodeFonts.face(a.text, a.bold);
        String bad = face.missing(a.text);
        if (!bad.isEmpty()) {
            throw new IllegalArgumentException("These characters cannot be drawn with the available font: " + bad);
        }
        PDType0Font font = fonts.get(face.file);
        if (font == null) {
            font = UnicodeFonts.pdf(doc, face);
            fonts.put(face.file, font);
        }
        String[] lines = a.textLines();
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            if (face.syntheticBold) {
                cs.setLineWidth(a.fontSize * 0.035f);
            }
            cs.beginText();
            cs.setFont(font, a.fontSize);
            cs.setNonStrokingColor(a.color);
            if (face.syntheticBold) {
                cs.setStrokingColor(a.color);
                cs.setRenderingMode(RenderingMode.FILL_STROKE);
            }
            cs.newLineAtOffset(left + a.x, top - a.y - a.fontSize * (Annotation.BASELINE + i * Annotation.LINE_HEIGHT));
            cs.showText(lines[i]);
            if (face.syntheticBold) {
                cs.setRenderingMode(RenderingMode.FILL);
            }
            cs.endText();
        }
    }
}
