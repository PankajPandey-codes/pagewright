import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/** Generates the 1024px app icon: java packaging/IconGen.java src/main/resources/icon.png */
public class IconGen {
    public static void main(String[] args) throws Exception {
        int n = 1024;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g.setPaint(new GradientPaint(0, 0, new Color(0xE5484D), 0, n, new Color(0xB42328)));
        g.fill(new RoundRectangle2D.Float(64, 64, n - 128, n - 128, 220, 220));

        float x = 270, y = 190, w = 484, h = 644, fold = 130;
        Path2D page = new Path2D.Float();
        page.moveTo(x, y);
        page.lineTo(x + w - fold, y);
        page.lineTo(x + w, y + fold);
        page.lineTo(x + w, y + h);
        page.lineTo(x, y + h);
        page.closePath();
        g.setColor(Color.WHITE);
        g.fill(page);
        Path2D corner = new Path2D.Float();
        corner.moveTo(x + w - fold, y);
        corner.lineTo(x + w - fold, y + fold);
        corner.lineTo(x + w, y + fold);
        corner.closePath();
        g.setColor(new Color(0xF0C4C6));
        g.fill(corner);

        g.setColor(new Color(0xD0D0D0));
        g.setStroke(new BasicStroke(26, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 3; i++) {
            float ly = y + 250 + i * 70;
            g.drawLine((int) x + 60, (int) ly, (int) (x + w - 60 - (i == 2 ? 120 : 0)), (int) ly);
        }

        g.setColor(new Color(0xB42328));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 150));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("PDF", x + (w - fm.stringWidth("PDF")) / 2f, y + h - 70);
        g.dispose();
        ImageIO.write(img, "png", new File(args[0]));
    }
}
