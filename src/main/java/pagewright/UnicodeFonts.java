package pagewright;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Chooses the font used for text the user inserts. Ordinary Latin text uses a common sans font (Arial or
 * similar). Text that font cannot draw (for example the rupee sign) uses a wider-coverage font found on the
 * computer. A font dropped into src/main/resources/fonts/ (UnicodeSans.ttf, UnicodeSans-Bold.ttf) is used
 * as the wide-coverage font and takes priority over system fonts.
 */
final class UnicodeFonts {

    private static final int RUPEE = 0x20B9;

    private static final String[] LATIN_REGULAR = {
        "/System/Library/Fonts/Supplemental/Arial.ttf",
        "C:\\Windows\\Fonts\\arial.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/TTF/DejaVuSans.ttf",
    };
    private static final String[] LATIN_BOLD = {
        "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
        "C:\\Windows\\Fonts\\arialbd.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
    };
    private static final String[] WIDE_REGULAR = {
        "/System/Library/Fonts/Geneva.ttf",
        "C:\\Windows\\Fonts\\arial.ttf",
        "C:\\Windows\\Fonts\\segoeui.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/TTF/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
    };
    private static final String[] WIDE_BOLD = {
        "C:\\Windows\\Fonts\\arialbd.ttf",
        "C:\\Windows\\Fonts\\segoeuib.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf",
    };

    /** A concrete font: the file to embed in PDFs, and the matching AWT font for on-screen drawing. */
    static final class Face {
        final File file;
        final Font awt;
        /** True when no real bold file exists, so bold is imitated (AWT) or by stroking the glyphs (PDF). */
        final boolean syntheticBold;

        Face(File file, Font awt, boolean syntheticBold) {
            this.file = file;
            this.awt = awt;
            this.syntheticBold = syntheticBold;
        }

        Font sized(float size) {
            return syntheticBold ? awt.deriveFont(Font.BOLD, size) : awt.deriveFont(size);
        }

        /** Distinct characters of text that this font cannot draw. */
        String missing(String text) {
            StringBuilder bad = new StringBuilder();
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                String s = new String(Character.toChars(cp));
                if (cp != '\n' && !awt.canDisplay(cp) && bad.indexOf(s) < 0) {
                    bad.append(s);
                }
            }
            return bad.toString();
        }

        Face asSyntheticBold() {
            return new Face(file, awt, true);
        }
    }

    private static boolean located;
    private static Face latinRegular;
    private static Face latinBold;
    private static Face wideRegular;
    private static Face wideBold;
    private static Face logical;

    private UnicodeFonts() {
    }

    /** The best available font for this text; check Face.missing(text) for anything it still cannot draw. */
    static synchronized Face face(String text, boolean bold) {
        locate();
        Face latin = bold ? latinBold : latinRegular;
        if (latin != null && latin.missing(text).isEmpty()) {
            return latin;
        }
        Face wide = bold ? wideBold : wideRegular;
        if (wide != null && wide.missing(text).isEmpty()) {
            return wide;
        }
        if (bold && wideRegular != null && wideRegular.missing(text).isEmpty()) {
            return wideRegular.asSyntheticBold();
        }
        Face any = wideRegular != null ? wideRegular : latinRegular;
        if (any != null) {
            return bold ? any.asSyntheticBold() : any;
        }
        return logical;
    }

    static PDType0Font pdf(PDDocument doc, Face face) throws IOException {
        if (face.file == null) {
            throw new IOException("No usable font file was found on this computer");
        }
        return PDType0Font.load(doc, face.file);
    }

    private static void locate() {
        if (located) {
            return;
        }
        located = true;
        logical = new Face(null, new Font(Font.SANS_SERIF, Font.PLAIN, 1), false);
        latinRegular = first(LATIN_REGULAR, false);
        latinBold = first(LATIN_BOLD, false);
        Face bundledRegular = bundled("/fonts/UnicodeSans.ttf", "unisans");
        Face bundledBold = bundled("/fonts/UnicodeSans-Bold.ttf", "unisans-bold");
        wideRegular = bundledRegular != null ? bundledRegular : first(WIDE_REGULAR, true);
        wideBold = bundledBold != null ? bundledBold : first(WIDE_BOLD, true);
    }

    /** First existing font in the list; if mustHaveRupee, only one that can draw the rupee sign. */
    private static Face first(String[] candidates, boolean mustHaveRupee) {
        for (String path : candidates) {
            File f = new File(path);
            Font font = f.isFile() ? load(f) : null;
            if (font != null && font.canDisplay('A') && (!mustHaveRupee || font.canDisplay(RUPEE))) {
                return new Face(f, font, false);
            }
        }
        return null;
    }

    private static Face bundled(String resource, String prefix) {
        try (InputStream in = UnicodeFonts.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            File tmp = File.createTempFile(prefix, ".ttf");
            tmp.deleteOnExit();
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Font font = load(tmp);
            return font == null ? null : new Face(tmp, font, false);
        } catch (IOException e) {
            return null;
        }
    }

    private static Font load(File file) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, file);
        } catch (FontFormatException | IOException e) {
            return null;
        }
    }
}
