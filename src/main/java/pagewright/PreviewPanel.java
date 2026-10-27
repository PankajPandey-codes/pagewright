package pagewright;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

/** Shows one page with its annotations; lets the user select, move and resize annotations. */
final class PreviewPanel extends JPanel {

    interface Listener {
        /** About to modify annotations; the key groups changes into one undo step. */
        void beforeChange(Object key);

        void changed();

        void selectionChanged(Annotation selected);

        /** Mouse gestures that did not touch an annotation (used for picking page text). */
        void backgroundPressed(Point p);

        void backgroundDragged(Point p);

        void backgroundReleased(MouseEvent e);
    }

    private enum Mode { NONE, MOVE, RESIZE, BACKGROUND }

    private static final int HANDLE = 7;
    private static final float MIN_SIZE = 4f;
    private static final float DRAG_THRESHOLD_PX = 3f;

    private final Listener listener;
    private BufferedImage image;
    private float scale = 1f;
    private int page;
    private List<Annotation> annotations = Collections.emptyList();
    private Annotation selected;
    private Rectangle highlight;
    private boolean interactive = true;

    private Mode mode = Mode.NONE;
    private int handle;
    private boolean moved;
    private Object gestureKey;
    private float pressX;
    private float pressY;
    private float origX;
    private float origY;
    private float origW;
    private float origH;

    PreviewPanel(Listener listener) {
        this.listener = listener;
        setFocusable(true);
        setBackground(new Color(0x808080));
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                onPress(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                onDrag(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                onRelease(e);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    void setContent(BufferedImage image, float scale, int page, List<Annotation> annotations) {
        this.image = image;
        this.scale = scale;
        this.page = page;
        this.annotations = annotations;
        this.highlight = null;
        if (selected != null && !annotations.contains(selected)) {
            selected = null;
        }
        setPreferredSize(image == null ? new Dimension(0, 0) : new Dimension(image.getWidth(), image.getHeight()));
        revalidate();
        repaint();
    }

    void setInteractive(boolean interactive) {
        this.interactive = interactive;
    }

    Rectangle getHighlight() {
        return highlight;
    }

    void setHighlight(Rectangle r) {
        this.highlight = r;
        repaint();
    }

    Annotation getSelected() {
        return selected;
    }

    void select(Annotation a) {
        if (selected != a) {
            selected = a;
            listener.selectionChanged(a);
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (image == null) {
            return;
        }
        g.drawImage(image, 0, 0, null);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        if (highlight != null) {
            g2.setColor(new Color(255, 220, 0, 110));
            g2.fillRect(highlight.x, highlight.y, highlight.width, highlight.height);
        }
        Graphics2D pts = (Graphics2D) g2.create();
        pts.scale(scale, scale);
        for (Annotation a : annotations) {
            if (a.page == page) {
                paintAnnotation(pts, a);
            }
        }
        pts.dispose();
        if (selected != null && selected.page == page) {
            paintSelection(g2, selected);
        }
        g2.dispose();
    }

    private void paintAnnotation(Graphics2D g, Annotation a) {
        g.setColor(a.color);
        switch (a.type) {
            case TEXT:
                g.setFont(UnicodeFonts.face(a.text, a.bold).sized(a.fontSize));
                String[] lines = a.textLines();
                for (int i = 0; i < lines.length; i++) {
                    g.drawString(lines[i], a.x, a.y + a.fontSize * (Annotation.BASELINE + i * Annotation.LINE_HEIGHT));
                }
                break;
            case IMAGE:
                g.drawImage(a.image(), Math.round(a.x), Math.round(a.y), Math.round(a.w), Math.round(a.h), null);
                break;
            case RECT:
                Rectangle2D.Float b = a.bounds();
                if (a.filled) {
                    g.fill(b);
                } else {
                    g.setStroke(new BasicStroke(a.strokeWidth));
                    g.draw(b);
                }
                break;
            case LINE:
                g.setStroke(new BasicStroke(a.strokeWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                g.draw(new Line2D.Float(a.x, a.y, a.x + a.w, a.y + a.h));
                break;
            default:
                break;
        }
    }

    private void paintSelection(Graphics2D g, Annotation a) {
        Rectangle2D.Float b = a.bounds();
        Rectangle box = new Rectangle(Math.round(b.x * scale) - 2, Math.round(b.y * scale) - 2,
                Math.round(b.width * scale) + 4, Math.round(b.height * scale) + 4);
        g.setColor(new Color(0x2F7DF6));
        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {4f, 3f}, 0f));
        g.draw(box);
        if (a.resizable()) {
            g.setStroke(new BasicStroke(1f));
            for (Point p : handlePoints(a)) {
                g.setColor(Color.WHITE);
                g.fillRect(p.x - HANDLE / 2, p.y - HANDLE / 2, HANDLE, HANDLE);
                g.setColor(new Color(0x2F7DF6));
                g.drawRect(p.x - HANDLE / 2, p.y - HANDLE / 2, HANDLE, HANDLE);
            }
        }
    }

    /** Pixel positions of the resize handles: corners for boxes, end points for lines. */
    private Point[] handlePoints(Annotation a) {
        if (a.type == Annotation.Type.LINE) {
            return new Point[] {px(a.x, a.y), px(a.x + a.w, a.y + a.h)};
        }
        Rectangle2D.Float b = a.bounds();
        return new Point[] {px(b.x, b.y), px(b.x + b.width, b.y), px(b.x + b.width, b.y + b.height), px(b.x, b.y + b.height)};
    }

    private Point px(float x, float y) {
        return new Point(Math.round(x * scale), Math.round(y * scale));
    }

    private int handleAt(Annotation a, Point p) {
        Point[] hs = handlePoints(a);
        for (int i = 0; i < hs.length; i++) {
            if (Math.abs(hs[i].x - p.x) <= HANDLE && Math.abs(hs[i].y - p.y) <= HANDLE) {
                return i;
            }
        }
        return -1;
    }

    private Annotation topAt(float x, float y) {
        for (int i = annotations.size() - 1; i >= 0; i--) {
            Annotation a = annotations.get(i);
            if (a.page == page && a.contains(x, y)) {
                return a;
            }
        }
        return null;
    }

    private void onPress(MouseEvent e) {
        requestFocusInWindow();
        if (!interactive || image == null) {
            return;
        }
        pressX = e.getX() / scale;
        pressY = e.getY() / scale;
        moved = false;
        gestureKey = new Object();
        if (selected != null && selected.page == page && selected.resizable()) {
            int h = handleAt(selected, e.getPoint());
            if (h >= 0) {
                handle = h;
                mode = Mode.RESIZE;
                remember(selected);
                return;
            }
        }
        Annotation hit = topAt(pressX, pressY);
        if (hit != null) {
            select(hit);
            mode = Mode.MOVE;
            remember(hit);
            return;
        }
        select(null);
        mode = Mode.BACKGROUND;
        listener.backgroundPressed(e.getPoint());
    }

    private void remember(Annotation a) {
        origX = a.x;
        origY = a.y;
        origW = a.w;
        origH = a.h;
    }

    private void onDrag(MouseEvent e) {
        if (mode == Mode.NONE) {
            return;
        }
        if (mode == Mode.BACKGROUND) {
            listener.backgroundDragged(e.getPoint());
            return;
        }
        float mx = e.getX() / scale;
        float my = e.getY() / scale;
        if (!moved) {
            if (Math.hypot((mx - pressX) * scale, (my - pressY) * scale) < DRAG_THRESHOLD_PX) {
                return;
            }
            moved = true;
            listener.beforeChange(gestureKey);
        }
        if (mode == Mode.MOVE) {
            selected.x = origX + (mx - pressX);
            selected.y = origY + (my - pressY);
        } else {
            resize(selected, mx, my);
        }
        listener.changed();
        repaint();
    }

    private void resize(Annotation a, float mx, float my) {
        if (a.type == Annotation.Type.LINE) {
            if (handle == 0) {
                a.x = mx;
                a.y = my;
                a.w = origX + origW - mx;
                a.h = origY + origH - my;
            } else {
                a.w = mx - origX;
                a.h = my - origY;
            }
            return;
        }
        float fixedX = (handle == 0 || handle == 3) ? origX + origW : origX;
        float fixedY = (handle == 0 || handle == 1) ? origY + origH : origY;
        float width = Math.max(MIN_SIZE, Math.abs(mx - fixedX));
        float height = Math.max(MIN_SIZE, Math.abs(my - fixedY));
        if (a.type == Annotation.Type.IMAGE) {
            height = Math.max(MIN_SIZE, width * origH / origW);
        }
        a.w = width;
        a.h = height;
        a.x = mx < fixedX ? fixedX - width : fixedX;
        a.y = my < fixedY ? fixedY - height : fixedY;
    }

    private void onRelease(MouseEvent e) {
        Mode finished = mode;
        mode = Mode.NONE;
        if (finished == Mode.BACKGROUND) {
            listener.backgroundReleased(e);
        }
    }
}
