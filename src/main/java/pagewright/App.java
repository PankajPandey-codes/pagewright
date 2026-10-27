package pagewright;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Desktop;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.io.File;

public class App {
    public static final String NAME = "Pagewright";

    public static void main(String[] args) {
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        System.setProperty("apple.awt.application.name", NAME);
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                System.err.println("Using default look and feel: " + e);
            }
            EditorWindow window = new EditorWindow();
            setDockIcon();
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) {
                Desktop.getDesktop().setOpenFileHandler(e -> {
                    if (!e.getFiles().isEmpty()) {
                        SwingUtilities.invokeLater(() -> window.openFile(e.getFiles().get(0)));
                    }
                });
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_HANDLER)) {
                Desktop.getDesktop().setQuitHandler((e, response) -> {
                    if (window.confirmDiscard()) {
                        response.performQuit();
                    } else {
                        response.cancelQuit();
                    }
                });
            }
            window.setVisible(true);
            if (args.length > 0) {
                window.openFile(new File(args[0]));
            }
        });
    }

    private static void setDockIcon() {
        java.net.URL url = App.class.getResource("/icon.png");
        if (url != null && Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
            Taskbar.getTaskbar().setIconImage(Toolkit.getDefaultToolkit().getImage(url));
        }
    }
}
