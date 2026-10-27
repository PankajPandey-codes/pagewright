package pagewright;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.TransferHandler;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.undo.UndoManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.KeyboardFocusManager;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class EditorWindow extends JFrame implements PreviewPanel.Listener {

    private static final float DEFAULT_SCALE = 1.4f;
    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 4f;
    private static final float ZOOM_STEP = 1.25f;

    private interface Op {
        void run(File in, File out) throws IOException;
    }

    private interface Work<T> {
        T call() throws Exception;
    }

    private final Session session = new Session();
    private PDDocument doc;
    private int page;
    private float scale = DEFAULT_SCALE;
    private BufferedImage pageImage;
    private List<TextEditor.Line> pageLines;
    private boolean busy;
    private boolean syncing;

    private final PreviewPanel preview = new PreviewPanel(this);
    private final JScrollPane previewScroll = new JScrollPane(preview);
    private final JLabel pageLabel = new JLabel("-");
    private final JLabel status = new JLabel("Open a PDF to start (or drop one on this window)");

    private final JTextField findField = new JTextField(16);
    private final JTextField replaceField = new JTextField(16);
    private final JCheckBox allPages = new JCheckBox("All pages");
    private final UndoManager findUndo = new UndoManager();
    private final UndoManager replaceUndo = new UndoManager();

    private final JLabel itemHint = new JLabel("Nothing selected");
    private final JTextArea itemText = new JTextArea(3, 16);
    private final JSpinner sizeSpin = new JSpinner(new SpinnerNumberModel(14, 4, 300, 1));
    private final JCheckBox boldBox = new JCheckBox("Bold");
    private final JButton colorButton = new JButton("Color...");
    private final JLabel colorSwatch = new JLabel("      ");
    private final JSpinner strokeSpin = new JSpinner(new SpinnerNumberModel(2, 1, 60, 1));
    private final JCheckBox filledBox = new JCheckBox("Filled");
    private final JButton deleteItemButton = new JButton("Delete item");

    private Point pressPoint;
    private TextEditor.Hit pressHit;
    private boolean dragging;

    public EditorWindow() {
        super(App.NAME);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!busy && confirmDiscard()) {
                    dispose();
                    System.exit(0);
                }
            }
        });
        setLayout(new BorderLayout());
        setJMenuBar(buildMenuBar());
        add(buildToolbar(), BorderLayout.NORTH);
        add(previewScroll, BorderLayout.CENTER);
        JScrollPane side = new JScrollPane(buildSidePanel());
        side.setBorder(null);
        side.setPreferredSize(new Dimension(340, 0));
        add(side, BorderLayout.EAST);
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(status, BorderLayout.SOUTH);

        java.net.URL iconUrl = EditorWindow.class.getResource("/icon.png");
        if (iconUrl != null) {
            setIconImage(new ImageIcon(iconUrl).getImage());
        }
        installDropHandler();
        installCanvasKeys();
        wireItemControls();
        syncProps();
        setSize(1240, 880);
        setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------------ UI construction

    private JMenuBar buildMenuBar() {
        int cmd = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        int shift = InputEvent.SHIFT_DOWN_MASK;
        int alt = InputEvent.ALT_DOWN_MASK;
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.add(menuItem("Open...", KeyEvent.VK_O, cmd, this::openPdf));
        file.add(menuItem("Save", KeyEvent.VK_S, cmd, this::save));
        file.add(menuItem("Save As...", KeyEvent.VK_S, cmd | shift, this::saveAs));
        if (!System.getProperty("os.name").toLowerCase().contains("mac")) {
            file.add(menuItem("Quit", KeyEvent.VK_Q, cmd, () -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING))));
        }

        JMenu edit = new JMenu("Edit");
        edit.add(menuItem("Undo", KeyEvent.VK_Z, cmd, this::undo));
        edit.add(menuItem("Redo", KeyEvent.VK_Z, cmd | shift, this::redo));
        edit.addSeparator();
        edit.add(menuItem("Delete Selected Item", 0, 0, this::deleteSelected));
        edit.add(menuItem("Find and Replace Text", KeyEvent.VK_F, cmd, () -> {
            findField.requestFocusInWindow();
            findField.selectAll();
        }));

        JMenu insert = new JMenu("Insert");
        insert.add(menuItem("Text", KeyEvent.VK_T, cmd, this::insertText));
        insert.add(menuItem("Image...", KeyEvent.VK_I, cmd | shift, this::insertImage));
        insert.add(menuItem("Line", KeyEvent.VK_L, cmd | shift, this::insertLine));
        insert.add(menuItem("Rectangle", KeyEvent.VK_R, cmd | shift, this::insertRect));

        JMenu pageMenu = new JMenu("Page");
        pageMenu.add(menuItem("Rotate Left", KeyEvent.VK_L, cmd, () -> rotate(270)));
        pageMenu.add(menuItem("Rotate Right", KeyEvent.VK_R, cmd, () -> rotate(90)));
        pageMenu.add(menuItem("Delete Page", 0, 0, this::deleteCurrentPage));
        pageMenu.addSeparator();
        pageMenu.add(menuItem("Previous Page", KeyEvent.VK_LEFT, cmd | alt, () -> goToPage(page - 1)));
        pageMenu.add(menuItem("Next Page", KeyEvent.VK_RIGHT, cmd | alt, () -> goToPage(page + 1)));

        JMenu doc = new JMenu("Document");
        doc.add(menuItem("Merge Another PDF...", 0, 0, this::mergeAnother));
        doc.add(menuItem("Split Into Pages...", 0, 0, this::splitPages));
        doc.add(menuItem("Extract Text", 0, 0, this::extractText));

        JMenu view = new JMenu("View");
        view.add(menuItem("Zoom In", KeyEvent.VK_EQUALS, cmd, () -> zoom(ZOOM_STEP)));
        view.add(menuItem("Zoom Out", KeyEvent.VK_MINUS, cmd, () -> zoom(1 / ZOOM_STEP)));
        view.add(menuItem("Actual Size", KeyEvent.VK_0, cmd, () -> zoom(DEFAULT_SCALE / scale)));

        for (JMenu m : new JMenu[] {file, edit, insert, pageMenu, doc, view}) {
            bar.add(m);
        }
        return bar;
    }

    private static JMenuItem menuItem(String label, int key, int modifiers, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        if (key != 0) {
            item.setAccelerator(KeyStroke.getKeyStroke(key, modifiers));
        }
        item.addActionListener(e -> action.run());
        return item;
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(button("Open...", this::openPdf));
        bar.add(button("Save", this::save));
        bar.add(button("Undo", this::undo));
        bar.add(button("Redo", this::redo));
        bar.add(button("-", () -> zoom(1 / ZOOM_STEP)));
        bar.add(button("+", () -> zoom(ZOOM_STEP)));
        bar.add(button("<", () -> goToPage(page - 1)));
        bar.add(pageLabel);
        bar.add(button(">", () -> goToPage(page + 1)));
        return bar;
    }

    private JPanel buildSidePanel() {
        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        side.add(section("Edit existing text",
                row(new JLabel("<html><small>Click = word, double-click = sentence,<br>"
                        + "drag = any part of a line, shift+click = whole line</small></html>")),
                row(new JLabel("Find   "), findField),
                row(new JLabel("Replace"), replaceField),
                row(allPages, button("Replace text", this::replaceText))));

        side.add(section("Insert (then drag it into place)",
                row(button("Text", this::insertText), button("Image...", this::insertImage)),
                row(button("Line", this::insertLine), button("Rectangle", this::insertRect))));

        itemText.setLineWrap(false);
        colorSwatch.setOpaque(true);
        colorSwatch.setBorder(BorderFactory.createLineBorder(Color.GRAY));
        side.add(section("Selected item",
                row(itemHint),
                row(new JScrollPane(itemText)),
                row(new JLabel("Size"), sizeSpin, boldBox),
                row(colorButton, colorSwatch),
                row(new JLabel("Line width"), strokeSpin, filledBox),
                row(deleteItemButton)));

        side.add(section("Page",
                row(button("Rotate left", () -> rotate(270)), button("Rotate right", () -> rotate(90))),
                row(button("Delete page", this::deleteCurrentPage))));
        side.add(section("Whole document",
                row(button("Merge another PDF...", this::mergeAnother)),
                row(button("Split into pages...", this::splitPages)),
                row(button("Extract text", this::extractText))));
        return side;
    }

    private static JPanel section(String title, JComponent... rows) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createTitledBorder(title));
        for (JComponent r : rows) {
            r.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(r);
        }
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel row(Component... cs) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        for (Component c : cs) {
            p.add(c);
        }
        return p;
    }

    private JButton button(String label, Runnable action) {
        JButton b = new JButton(label);
        b.addActionListener(e -> action.run());
        return b;
    }

    private void installDropHandler() {
        setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                try {
                    List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    for (File f : files) {
                        if (f.getName().toLowerCase().endsWith(".pdf")) {
                            openFile(f);
                            return true;
                        }
                    }
                    status.setText("Drop a .pdf file");
                } catch (Exception e) {
                    error(e);
                }
                return false;
            }
        });
    }

    /** Keys that only apply while the page itself has focus, so they never fight with typing in a text box. */
    private void installCanvasKeys() {
        bindKey(KeyEvent.VK_DELETE, 0, this::deleteSelected);
        bindKey(KeyEvent.VK_BACK_SPACE, 0, this::deleteSelected);
        bindKey(KeyEvent.VK_ESCAPE, 0, () -> preview.select(null));
        bindKey(KeyEvent.VK_PAGE_UP, 0, () -> goToPage(page - 1));
        bindKey(KeyEvent.VK_PAGE_DOWN, 0, () -> goToPage(page + 1));
        int shift = InputEvent.SHIFT_DOWN_MASK;
        for (int step : new int[] {1, 10}) {
            int mods = step == 1 ? 0 : shift;
            bindKey(KeyEvent.VK_LEFT, mods, () -> nudge(-step, 0));
            bindKey(KeyEvent.VK_RIGHT, mods, () -> nudge(step, 0));
            bindKey(KeyEvent.VK_UP, mods, () -> nudge(0, -step));
            bindKey(KeyEvent.VK_DOWN, mods, () -> nudge(0, step));
        }
    }

    private void bindKey(int key, int modifiers, Runnable action) {
        String name = "canvas-" + key + "-" + modifiers;
        preview.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, modifiers), name);
        preview.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private void wireItemControls() {
        findField.getDocument().addUndoableEditListener(findUndo);
        replaceField.getDocument().addUndoableEditListener(replaceUndo);
        itemText.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                textEdited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                textEdited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                textEdited();
            }
        });
        sizeSpin.addChangeListener(e -> editSelected("size", a -> {
            if (a.type == Annotation.Type.TEXT) {
                a.fontSize = ((Number) sizeSpin.getValue()).floatValue();
                a.refreshTextBounds();
            }
        }));
        boldBox.addActionListener(e -> editSelected(null, a -> {
            a.bold = boldBox.isSelected();
            a.refreshTextBounds();
        }));
        strokeSpin.addChangeListener(e -> editSelected("stroke", a -> a.strokeWidth = ((Number) strokeSpin.getValue()).floatValue()));
        filledBox.addActionListener(e -> editSelected(null, a -> a.filled = filledBox.isSelected()));
        colorButton.addActionListener(e -> {
            Annotation a = preview.getSelected();
            Color chosen = a == null ? null : JColorChooser.showDialog(this, "Choose color", a.color);
            if (chosen != null) {
                editSelected(null, item -> item.color = chosen);
            }
        });
        deleteItemButton.addActionListener(e -> deleteSelected());
    }

    // ------------------------------------------------------------------ annotations

    @Override
    public void beforeChange(Object key) {
        session.beginChange(key);
    }

    @Override
    public void changed() {
        syncProps();
        updateTitle();
    }

    @Override
    public void selectionChanged(Annotation selected) {
        syncProps();
    }

    private void editSelected(String key, Consumer<Annotation> change) {
        Annotation a = preview.getSelected();
        if (syncing || a == null || busy) {
            return;
        }
        session.beginChange(key == null ? new Object() : Arrays.asList(a, key));
        change.accept(a);
        syncProps();
        updateTitle();
        preview.repaint();
    }

    private void textEdited() {
        Annotation a = preview.getSelected();
        if (syncing || a == null || a.type != Annotation.Type.TEXT) {
            return;
        }
        editSelected("text", item -> {
            item.text = itemText.getText();
            item.refreshTextBounds();
        });
        String bad = a.unsupportedCharacters();
        if (!bad.isEmpty()) {
            status.setText("These characters can't be drawn into the PDF (Latin text only): " + bad);
        }
    }

    private void syncProps() {
        Annotation a = preview.getSelected();
        boolean text = a != null && a.type == Annotation.Type.TEXT;
        boolean shape = a != null && (a.type == Annotation.Type.LINE || a.type == Annotation.Type.RECT);
        syncing = true;
        try {
            itemHint.setText(a == null ? "Nothing selected - click an item on the page" : "Selected: " + a.type.name().toLowerCase());
            itemText.setEnabled(text && !busy);
            if (!text || !itemText.getText().equals(a.text)) {
                itemText.setText(text ? a.text : "");
            }
            sizeSpin.setEnabled(text && !busy);
            if (text) {
                sizeSpin.setValue(Math.round(a.fontSize));
            }
            boldBox.setEnabled(text && !busy);
            boldBox.setSelected(text && a.bold);
            colorButton.setEnabled(a != null && a.type != Annotation.Type.IMAGE && !busy);
            colorSwatch.setBackground(a == null || a.type == Annotation.Type.IMAGE ? getBackground() : a.color);
            strokeSpin.setEnabled(shape && !busy);
            if (shape) {
                strokeSpin.setValue(Math.round(a.strokeWidth));
            }
            filledBox.setEnabled(a != null && a.type == Annotation.Type.RECT && !busy);
            filledBox.setSelected(a != null && a.filled);
            deleteItemButton.setEnabled(a != null && !busy);
        } finally {
            syncing = false;
        }
    }

    private float[] spotInView() {
        Rectangle view = previewScroll.getViewport().getViewRect();
        return new float[] {Math.max(20f, view.x / scale + 40f), Math.max(20f, view.y / scale + 60f)};
    }

    private boolean ready() {
        if (!session.isOpen()) {
            status.setText("Open a PDF first");
            return false;
        }
        if (busy) {
            status.setText("Still working - please wait");
            return false;
        }
        return true;
    }

    private void insert(Annotation a) {
        if (doc.getPage(page).getRotation() != 0) {
            status.setText("Items can't be added to a rotated page yet - rotate it back first");
            return;
        }
        session.beginChange(null);
        session.annotations.add(a);
        refreshView();
        preview.select(a);
        if (a.type == Annotation.Type.TEXT) {
            itemText.requestFocusInWindow();
            itemText.selectAll();
        } else {
            preview.requestFocusInWindow();
        }
        status.setText("Added - drag it into place. Delete key removes it.");
    }

    private void insertText() {
        if (ready()) {
            float[] s = spotInView();
            insert(Annotation.text(page, s[0], s[1], "Text"));
        }
    }

    private void insertImage() {
        if (!ready()) {
            return;
        }
        JFileChooser fc = chooser();
        fc.setFileFilter(new FileNameExtensionFilter("Images", "png", "jpg", "jpeg", "gif", "bmp"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                float[] s = spotInView();
                insert(Annotation.image(page, s[0], s[1], fc.getSelectedFile()));
            } catch (IOException e) {
                error(e);
            }
        }
    }

    private void insertLine() {
        if (ready()) {
            float[] s = spotInView();
            insert(Annotation.line(page, s[0], s[1], 160, 0));
        }
    }

    private void insertRect() {
        if (ready()) {
            float[] s = spotInView();
            insert(Annotation.rect(page, s[0], s[1], 160, 90));
        }
    }

    private void deleteSelected() {
        Annotation a = preview.getSelected();
        if (a == null || busy) {
            status.setText("Select an item on the page first");
            return;
        }
        session.beginChange(null);
        session.annotations.remove(a);
        preview.select(null);
        refreshView();
        status.setText("Item deleted (Undo brings it back)");
    }

    private void nudge(int dx, int dy) {
        Annotation a = preview.getSelected();
        if (a == null || busy) {
            return;
        }
        session.beginChange(Arrays.asList(a, "nudge"));
        a.x += dx;
        a.y += dy;
        updateTitle();
        preview.repaint();
    }

    // ------------------------------------------------------------------ viewing

    private BufferedImage render(PDDocument d, int pageIndex) throws IOException {
        return new PDFRenderer(d).renderImage(pageIndex, scale);
    }

    private void load(File f, int showPage) throws IOException {
        PDDocument next = Loader.loadPDF(f);
        int target = Math.max(0, Math.min(showPage, next.getNumberOfPages() - 1));
        BufferedImage image;
        try {
            image = render(next, target);
        } catch (IOException | RuntimeException e) {
            next.close();
            throw e;
        }
        if (doc != null) {
            doc.close();
        }
        doc = next;
        page = target;
        pageImage = image;
        pageLines = null;
    }

    private void refreshView() {
        preview.setContent(pageImage, scale, page, session.annotations);
        pageLabel.setText(doc == null ? "-" : "Page " + (page + 1) + " / " + doc.getNumberOfPages());
        syncProps();
        updateTitle();
    }

    private void updateTitle() {
        String name = session.originalFile == null ? App.NAME : App.NAME + " - " + session.originalFile.getName();
        setTitle(name);
        getRootPane().putClientProperty("Window.documentModified", session.isDirty());
    }

    private void goToPage(int target) {
        if (doc == null || busy || target < 0 || target >= doc.getNumberOfPages() || target == page) {
            return;
        }
        try {
            pageImage = render(doc, target);
            page = target;
            pageLines = null;
            preview.select(null);
            refreshView();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            error(e);
        }
    }

    private void zoom(float factor) {
        if (doc == null || busy) {
            return;
        }
        float next = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale * factor));
        if (Math.abs(next - scale) < 0.001f) {
            return;
        }
        try {
            BufferedImage image = new PDFRenderer(doc).renderImage(page, next);
            scale = next;
            pageImage = image;
            refreshView();
            status.setText("Zoom " + Math.round(scale / DEFAULT_SCALE * 100) + "%");
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            error(e);
        }
    }

    // ------------------------------------------------------------------ open / save / undo

    private static JFileChooser chooser() {
        File desktop = new File(System.getProperty("user.home"), "Desktop");
        return new JFileChooser(desktop.isDirectory() ? desktop : new File(System.getProperty("user.home")));
    }

    private void openPdf() {
        JFileChooser fc = chooser();
        fc.setFileFilter(new FileNameExtensionFilter("PDF files", "pdf"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openFile(fc.getSelectedFile());
        }
    }

    public void openFile(File f) {
        if (busy) {
            status.setText("Still working - open the file again when it finishes");
            return;
        }
        if (!confirmDiscard()) {
            return;
        }
        File working = null;
        try {
            // Work on a private copy so the user's file is only ever written by Save.
            working = File.createTempFile("pdfedit-", ".pdf");
            working.deleteOnExit();
            Files.copy(f.toPath(), working.toPath(), StandardCopyOption.REPLACE_EXISTING);
            load(working, 0);
            session.reset(working, f);
            preview.select(null);
            refreshView();
            status.setText("Opened " + f.getName());
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            if (working != null) {
                working.delete();
            }
            error(e);
        }
    }

    /** True if there is nothing unsaved, or the user agrees to throw it away. */
    public boolean confirmDiscard() {
        return !session.isDirty() || JOptionPane.showConfirmDialog(this, "You have unsaved edits. Discard them?",
                "Unsaved changes", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /** Cmd+Z / Shift+Cmd+Z while typing in Find or Replace undoes that text, not the document. */
    private boolean textFieldUndo(boolean redo) {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        UndoManager manager = focus == findField ? findUndo : focus == replaceField ? replaceUndo : null;
        if (manager == null) {
            return false;
        }
        if (redo ? manager.canRedo() : manager.canUndo()) {
            if (redo) {
                manager.redo();
            } else {
                manager.undo();
            }
        }
        return true;
    }

    private void undo() {
        if (textFieldUndo(false) || busy) {
            return;
        }
        Session.State target = session.peekUndo();
        if (target == null) {
            status.setText("Nothing to undo");
            return;
        }
        travel(target, session::undo, "Undone");
    }

    private void redo() {
        if (textFieldUndo(true) || busy) {
            return;
        }
        Session.State target = session.peekRedo();
        if (target == null) {
            status.setText("Nothing to redo");
            return;
        }
        travel(target, session::redo, "Redone");
    }

    private void travel(Session.State target, Runnable commit, String message) {
        try {
            if (!target.file.equals(session.file)) {
                load(target.file, page);
            }
            commit.run();
            preview.select(null);
            refreshView();
            status.setText(message);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            error(e);
        }
    }

    private void save() {
        if (!ready()) {
            return;
        }
        if (session.lastSavedTo != null) {
            writeTo(session.lastSavedTo);
        } else {
            saveAs();
        }
    }

    private void saveAs() {
        if (!ready()) {
            return;
        }
        JFileChooser fc = chooser();
        if (session.originalFile != null && session.originalFile.getParentFile() != null) {
            fc.setCurrentDirectory(session.originalFile.getParentFile());
        }
        fc.setSelectedFile(new File(fc.getCurrentDirectory(), baseName(session.originalFile) + "-edited.pdf"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = fc.getSelectedFile();
        if (!target.getName().toLowerCase().endsWith(".pdf")) {
            target = new File(target.getParentFile(), target.getName() + ".pdf");
        }
        if (target.exists()) {
            String warning = target.equals(session.originalFile) ? "This overwrites your ORIGINAL file. Continue?"
                    : "Overwrite " + target.getName() + "?";
            if (JOptionPane.showConfirmDialog(this, warning, "Confirm", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
                return;
            }
        }
        writeTo(target);
    }

    private void writeTo(File target) {
        File source = session.file;
        List<Annotation> pending = Session.copyOf(session.annotations);
        long savedId = session.currentId();
        background("Saving", () -> {
            writeAtomically(source, pending, target.toPath());
            return target;
        }, saved -> {
            session.lastSavedTo = saved;
            session.markClean(savedId);
            updateTitle();
            status.setText("Saved " + saved);
        });
    }

    /** Writes next to the target and renames into place, so a failed write never destroys an existing file. */
    private static void writeAtomically(File source, List<Annotation> pending, Path target) throws IOException {
        Path temp = Files.createTempFile(target.toAbsolutePath().getParent(), ".pagewright-", ".tmp");
        try {
            if (pending.isEmpty()) {
                Files.copy(source.toPath(), temp, StandardCopyOption.REPLACE_EXISTING);
            } else {
                AnnotationBaker.bake(source, temp.toFile(), pending);
            }
            keepPermissions(temp, target);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** createTempFile makes owner-only files; give the result the target's mode, or 644 for a new file. */
    private static void keepPermissions(Path temp, Path target) throws IOException {
        try {
            Set<PosixFilePermission> mode = Files.exists(target)
                    ? Files.getPosixFilePermissions(target) : PosixFilePermissions.fromString("rw-r--r--");
            Files.setPosixFilePermissions(temp, mode);
        } catch (UnsupportedOperationException e) {
            // Not a POSIX file system (Windows): there are no mode bits to preserve.
        }
    }

    private static String baseName(File f) {
        if (f == null) {
            return "document";
        }
        String n = f.getName();
        return n.toLowerCase().endsWith(".pdf") ? n.substring(0, n.length() - 4) : n;
    }

    // ------------------------------------------------------------------ background work

    private void setBusy(boolean value) {
        busy = value;
        syncProps();
        preview.setInteractive(!value);
        setCursor(Cursor.getPredefinedCursor(value ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private <T> void background(String what, Work<T> work, Consumer<T> onSuccess) {
        setBusy(true);
        status.setText(what + "...");
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                setBusy(false);
                T result;
                try {
                    result = get();
                } catch (ExecutionException e) {
                    error(e.getCause());
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                onSuccess.accept(result);
            }
        }.execute();
    }

    private void apply(String what, Op op) {
        applyIf(what, op, () -> true, () -> what + " - done (not saved yet: Save or Save As)");
    }

    /**
     * Rewrites the PDF with op. Annotations still floating over the page are drawn into it first,
     * and the result becomes a new undo step only if changed says something happened.
     */
    private void applyIf(String what, Op op, BooleanSupplier changed, Supplier<String> summary) {
        if (!ready()) {
            return;
        }
        List<Annotation> pending = Session.copyOf(session.annotations);
        File in = session.file;
        background(what, () -> {
            File baked = null;
            File out = null;
            try {
                File src = in;
                if (!pending.isEmpty()) {
                    baked = File.createTempFile("pdfedit-", ".pdf");
                    AnnotationBaker.bake(in, baked, pending);
                    src = baked;
                }
                out = File.createTempFile("pdfedit-", ".pdf");
                out.deleteOnExit();
                op.run(src, out);
                return out;
            } catch (Exception | OutOfMemoryError e) {
                if (out != null) {
                    out.delete();
                }
                throw e;
            } finally {
                if (baked != null) {
                    baked.delete();
                }
            }
        }, out -> {
            if (!changed.getAsBoolean()) {
                out.delete();
                status.setText(what + ": nothing matched, document unchanged");
                return;
            }
            try {
                load(out, page);
                session.replaceFile(out, new ArrayList<>());
                preview.select(null);
                refreshView();
                status.setText(summary.get());
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                out.delete();
                error(e);
            }
        });
    }

    // ------------------------------------------------------------------ page and document operations

    private void rotate(int degrees) {
        int pg = page;
        apply("Rotated page", (in, out) -> PdfOperations.rotatePage(in, out, pg, degrees));
    }

    private void deleteCurrentPage() {
        if (doc != null && doc.getNumberOfPages() == 1) {
            status.setText("Cannot delete the only page");
            return;
        }
        int pg = page;
        apply("Deleted page", (in, out) -> PdfOperations.deletePages(in, out, pg));
    }

    private void mergeAnother() {
        if (!ready()) {
            return;
        }
        JFileChooser fc = chooser();
        fc.setFileFilter(new FileNameExtensionFilter("PDF files", "pdf"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File other = fc.getSelectedFile();
            apply("Merged " + other.getName(), (in, out) -> PdfOperations.merge(Arrays.asList(in, other), out));
        }
    }

    private void splitPages() {
        if (!ready()) {
            return;
        }
        JFileChooser fc = chooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Choose folder for the page files");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File dir = fc.getSelectedFile();
        String prefix = baseName(session.originalFile);
        int pages = doc.getNumberOfPages();
        List<String> clashes = new ArrayList<>();
        for (int i = 1; i <= pages; i++) {
            File f = new File(dir, prefix + "-" + i + ".pdf");
            if (f.exists()) {
                clashes.add(f.getName());
            }
        }
        if (!clashes.isEmpty() && JOptionPane.showConfirmDialog(this,
                clashes.size() + " existing file(s) would be overwritten, e.g. " + clashes.get(0) + ". Continue?",
                "Confirm", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
            return;
        }
        File source = session.file;
        List<Annotation> pending = Session.copyOf(session.annotations);
        background("Splitting", () -> {
            File src = source;
            File baked = null;
            try {
                if (!pending.isEmpty()) {
                    baked = File.createTempFile("pdfedit-", ".pdf");
                    AnnotationBaker.bake(source, baked, pending);
                    src = baked;
                }
                PdfOperations.split(src, dir, prefix);
                return dir;
            } finally {
                if (baked != null) {
                    baked.delete();
                }
            }
        }, d -> status.setText("Split into " + pages + " files (" + prefix + "-N.pdf) in " + d));
    }

    private void extractText() {
        if (!ready()) {
            return;
        }
        try {
            JTextArea area = new JTextArea(PdfOperations.extractText(session.file), 25, 70);
            area.setEditable(false);
            JOptionPane.showMessageDialog(this, new JScrollPane(area), "Extracted text", JOptionPane.PLAIN_MESSAGE);
        } catch (IOException e) {
            error(e);
        }
    }

    // ------------------------------------------------------------------ editing existing page text

    private List<TextEditor.Line> pageLines() throws IOException {
        if (pageLines == null) {
            pageLines = TextEditor.lines(doc, page);
        }
        return pageLines;
    }

    @Override
    public void backgroundPressed(Point p) {
        dragging = false;
        pressPoint = p;
        pressHit = null;
        if (doc == null) {
            return;
        }
        try {
            pressHit = TextEditor.hit(pageLines(), p.x / scale, p.y / scale);
        } catch (IOException | RuntimeException e) {
            status.setText("Could not read the text on this page: " + e.getMessage());
        }
    }

    @Override
    public void backgroundDragged(Point p) {
        if (pressHit == null || (!dragging && pressPoint.distance(p) < 4)) {
            return;
        }
        dragging = true;
        int[] range = dragRange(p);
        showSelection(pressHit.line, range[0], range[1], false);
    }

    @Override
    public void backgroundReleased(MouseEvent e) {
        if (dragging && pressHit != null) {
            int[] range = dragRange(e.getPoint());
            showSelection(pressHit.line, range[0], range[1], true);
            dragging = false;
            return;
        }
        pickText(e.getPoint(), e.getClickCount(), e.isShiftDown());
    }

    private int[] dragRange(Point p) {
        int end = pressHit.line.charAt(p.x / scale);
        return new int[] {Math.min(pressHit.charIndex, end), Math.max(pressHit.charIndex, end) + 1};
    }

    private void pickText(Point p, int clickCount, boolean wholeLine) {
        if (doc == null) {
            return;
        }
        try {
            TextEditor.Hit hit = TextEditor.hit(pageLines(), p.x / scale, p.y / scale);
            if (hit == null) {
                preview.setHighlight(null);
                return;
            }
            int[] range = wholeLine || clickCount >= 3 ? hit.lineRange()
                    : clickCount == 2 ? hit.sentenceRange() : hit.wordRange();
            showSelection(hit.line, range[0], range[1], true);
        } catch (IOException | RuntimeException e) {
            status.setText("Could not read the text on this page: " + e.getMessage());
        }
    }

    private void showSelection(TextEditor.Line line, int start, int end, boolean fillFields) {
        Rectangle2D.Float b = line.box(start, end);
        preview.setHighlight(new Rectangle(Math.round(b.x * scale), Math.round(b.y * scale),
                Math.round(b.width * scale), Math.round(b.height * scale)));
        if (fillFields) {
            findField.setText(line.text.substring(start, end));
            replaceField.setText(findField.getText());
            replaceField.requestFocusInWindow();
            replaceField.selectAll();
            status.setText("Selected: " + findField.getText());
        }
    }

    private void replaceText() {
        String find = findField.getText();
        String repl = replaceField.getText();
        if (find.isEmpty()) {
            status.setText("Click a word on the page (or type text) in Find first");
            return;
        }
        if (!ready()) {
            return;
        }
        List<Integer> pages = new ArrayList<>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            if (allPages.isSelected() || i == page) {
                pages.add(i);
            }
        }
        TextEditor.Result[] result = new TextEditor.Result[1];
        applyIf("Replace text",
                (in, out) -> result[0] = TextEditor.replace(in, out, pages, find, repl),
                () -> result[0].inPlace + result[0].coveredAndOverwritten > 0,
                () -> result[0].describe() + "  (not saved yet: Save or Save As)");
    }

    private void error(Throwable t) {
        String message = t instanceof OutOfMemoryError ? "Not enough memory for this document." : t.toString();
        status.setText("Error: " + message);
        JOptionPane.showMessageDialog(this, message, "Error", JOptionPane.ERROR_MESSAGE);
    }
}
