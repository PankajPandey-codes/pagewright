package pagewright;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.multipdf.Splitter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

public class PdfOperations {

    /** Writes text onto a page (0-based) at x,y in PDF points, origin bottom-left. */
    public static void addText(File in, File out, int pageIndex, String text, float x, float y)
            throws IOException {
        try (PDDocument doc = Loader.loadPDF(in)) {
            PDPage page = doc.getPage(pageIndex);
            try (PDPageContentStream cs = new PDPageContentStream(
                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 14);
                cs.newLineAtOffset(x, y);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
        }
    }

    /** Draws an image (e.g. a signature or logo) onto a page. */
    public static void addImage(File in, File out, int pageIndex, File image,
                                float x, float y, float width, float height) throws IOException {
        try (PDDocument doc = Loader.loadPDF(in)) {
            PDPage page = doc.getPage(pageIndex);
            PDImageXObject img = PDImageXObject.createFromFileByContent(image, doc);
            try (PDPageContentStream cs = new PDPageContentStream(
                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs.drawImage(img, x, y, width, height);
            }
            doc.save(out);
        }
    }

    /** Rotates a page by a multiple of 90 degrees. */
    public static void rotatePage(File in, File out, int pageIndex, int degrees) throws IOException {
        try (PDDocument doc = Loader.loadPDF(in)) {
            PDPage page = doc.getPage(pageIndex);
            page.setRotation((page.getRotation() + degrees) % 360);
            doc.save(out);
        }
    }

    /** Removes pages by 0-based index. */
    public static void deletePages(File in, File out, int... pageIndexes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(in)) {
            int[] sorted = pageIndexes.clone();
            Arrays.sort(sorted);
            for (int i = sorted.length - 1; i >= 0; i--) { // delete from the end so indexes stay valid
                doc.removePage(sorted[i]);
            }
            doc.save(out);
        }
    }

    public static void merge(List<File> inputs, File out) throws IOException {
        PDFMergerUtility merger = new PDFMergerUtility();
        for (File f : inputs) {
            merger.addSource(f);
        }
        merger.setDestinationFileName(out.getPath());
        merger.mergeDocuments(IOUtils.createTempFileOnlyStreamCache());
    }

    /** Splits into one PDF per page: outDir/prefix-1.pdf, prefix-2.pdf, ... */
    public static void split(File in, File outDir, String prefix) throws IOException {
        try (PDDocument doc = Loader.loadPDF(in)) {
            List<PDDocument> parts = new Splitter().split(doc);
            int n = 1;
            for (PDDocument part : parts) {
                try (part) {
                    part.save(new File(outDir, prefix + "-" + n++ + ".pdf"));
                }
            }
        }
    }

    public static String extractText(File in) throws IOException {
        try (PDDocument doc = Loader.loadPDF(in)) {
            return new PDFTextStripper().getText(doc);
        }
    }


}
