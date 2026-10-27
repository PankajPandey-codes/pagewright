package pagewright;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Something the user placed on a page (text, image, line or rectangle). It stays editable until the
 * document is saved or a page-changing operation flattens it into the PDF.
 * Coordinates are points measured from the page's top-left corner.
 */
public final class Annotation {

    public enum Type { TEXT, IMAGE, LINE, RECT }

    /** Distance from the top of a text box to its first baseline, in em. */
    public static final float BASELINE = 0.9f;
    public static final float LINE_HEIGHT = 1.2f;

    private static final float HIT_SLACK = 4f;
    private static final float MAX_INSERTED_IMAGE_WIDTH = 200f;

    public final Type type;
    public int page;
    /** For LINE the segment runs from (x, y) to (x + w, y + h) and w/h may be negative; otherwise w, h > 0. */
    public float x;
    public float y;
    public float w;
    public float h;
    public String text = "";
    public float fontSize = 14;
    public boolean bold;
    public Color color = Color.BLACK;
    public File imageFile;
    public float strokeWidth = 2;
    public boolean filled;
    private BufferedImage image;

    private Annotation(Type type, int page) {
        this.type = type;
        this.page = page;
    }

    public static Annotation text(int page, float x, float y, String text) {
        Annotation a = new Annotation(Type.TEXT, page);
        a.x = x;
        a.y = y;
        a.text = text;
        a.refreshTextBounds();
        return a;
    }

    public static Annotation image(int page, float x, float y, File file) throws IOException {
        BufferedImage img = ImageIO.read(file);
        if (img == null) {
            throw new IOException("Not a supported image: " + file.getName());
        }
        Annotation a = new Annotation(Type.IMAGE, page);
        float shrink = Math.min(1f, MAX_INSERTED_IMAGE_WIDTH / img.getWidth());
        a.x = x;
        a.y = y;
        a.w = img.getWidth() * shrink;
        a.h = img.getHeight() * shrink;
        a.imageFile = file;
        a.image = img;
        return a;
    }

    public static Annotation line(int page, float x, float y, float dx, float dy) {
        Annotation a = new Annotation(Type.LINE, page);
        a.x = x;
        a.y = y;
        a.w = dx;
        a.h = dy;
        return a;
    }

    public static Annotation rect(int page, float x, float y, float w, float h) {
        Annotation a = new Annotation(Type.RECT, page);
        a.x = x;
        a.y = y;
        a.w = w;
        a.h = h;
        return a;
    }

    public Annotation copy() {
        Annotation c = new Annotation(type, page);
        c.x = x;
        c.y = y;
        c.w = w;
        c.h = h;
        c.text = text;
        c.fontSize = fontSize;
        c.bold = bold;
        c.color = color;
        c.imageFile = imageFile;
        c.image = image;
        c.strokeWidth = strokeWidth;
        c.filled = filled;
        return c;
    }

    public BufferedImage image() {
        return image;
    }

    public boolean resizable() {
        return type != Type.TEXT;
    }

    public String[] textLines() {
        return text.split("\n", -1);
    }

    /** Recomputes the box of a TEXT item from its content, font size and weight. */
    public void refreshTextBounds() {
        Font font = UnicodeFonts.face(text, bold).sized(fontSize);
        FontRenderContext frc = new FontRenderContext(null, true, true);
        float widest = 0;
        for (String line : textLines()) {
            widest = Math.max(widest, (float) font.getStringBounds(line, frc).getWidth());
        }
        w = Math.max(widest, fontSize);
        h = textLines().length * fontSize * LINE_HEIGHT;
    }

    /** Characters of a TEXT item that no available font can draw (empty if all can be drawn). */
    public String unsupportedCharacters() {
        return UnicodeFonts.face(text, bold).missing(text);
    }

    public Rectangle2D.Float bounds() {
        float left = Math.min(x, x + w);
        float top = Math.min(y, y + h);
        return new Rectangle2D.Float(left, top, Math.abs(w), Math.abs(h));
    }

    public boolean contains(float px, float py) {
        if (type == Type.LINE) {
            return Line2D.ptSegDist(x, y, x + w, y + h, px, py) <= Math.max(HIT_SLACK, strokeWidth);
        }
        Rectangle2D.Float b = bounds();
        boolean inside = px >= b.x - 2 && px <= b.x + b.width + 2 && py >= b.y - 2 && py <= b.y + b.height + 2;
        if (type == Type.RECT && !filled) {
            boolean deep = px > b.x + HIT_SLACK && px < b.x + b.width - HIT_SLACK
                    && py > b.y + HIT_SLACK && py < b.y + b.height - HIT_SLACK;
            return inside && !deep;
        }
        return inside;
    }
}
