import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileSystemView;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Smart File Organizer application.
 *
 * Compile: javac SmartFileOrganizer.java
 * Run:     java SmartFileOrganizer
 */
public class SmartFileOrganizer {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            OrganizerGUI gui = new OrganizerGUI();
            gui.setVisible(true);
        });
    }
}

/**
 * A generic rule for deciding the destination category of a file.
 */
interface Rule {
    /**
     * Returns the destination folder name (category) if this rule matches,
     * otherwise returns empty.
     */
    Optional<String> resolveCategory(Path file);
}

/**
 * Rule implementation based on file extension.
 */
class ExtensionRule implements Rule {
    private final Map<String, String> extensionToCategory;

    public ExtensionRule(Map<String, String> extensionToCategory) {
        this.extensionToCategory = new HashMap<>();
        for (Map.Entry<String, String> entry : extensionToCategory.entrySet()) {
            this.extensionToCategory.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
    }

    @Override
    public Optional<String> resolveCategory(Path file) {
        String fileName = file.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return Optional.empty();
        }
        String extension = fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
        return Optional.ofNullable(extensionToCategory.get(extension));
    }
}

/**
 * Optional advanced rule: resolve category by keyword in file name.
 */
class KeywordRule implements Rule {
    private final Map<String, String> keywordToCategory;

    public KeywordRule(Map<String, String> keywordToCategory) {
        this.keywordToCategory = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : keywordToCategory.entrySet()) {
            this.keywordToCategory.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
    }

    @Override
    public Optional<String> resolveCategory(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : keywordToCategory.entrySet()) {
            if (fileName.contains(entry.getKey())) {
                return Optional.of(entry.getValue());
            }
        }
        return Optional.empty();
    }
}

/**
 * Stores information required for undo operation.
 */
class MoveRecord {
    private final Path from;
    private final Path to;

    public MoveRecord(Path from, Path to) {
        this.from = from;
        this.to = to;
    }

    public Path getFrom() {
        return from;
    }

    public Path getTo() {
        return to;
    }
}

/**
 * Listener used by FileOrganizer to report progress and logs to the UI.
 */
interface OrganizerListener {
    void onLog(String message);

    void onProgress(int current, int total);

    default void onStatus(String status) {
        // optional
    }
}

/**
 * Core file organizing logic.
 */
class FileOrganizer {
    private final List<Rule> rules = new ArrayList<>();
    private final Deque<List<MoveRecord>> history = new ArrayDeque<>();

    public FileOrganizer() {
    }

    public void addRule(Rule rule) {
        rules.add(rule);
    }

    public void clearRules() {
        rules.clear();
    }

    /**
     * Organizes files in the given directory based on active rules.
     */
    public void organize(Path directory, OrganizerListener listener) throws IOException {
        validateDirectory(directory);

        List<Path> files = listTopLevelFiles(directory);
        listener.onStatus("Processing...");
        listener.onLog("Found " + files.size() + " file(s) in " + directory);

        List<MoveRecord> moveRecords = new ArrayList<>();

        int processed = 0;
        for (Path file : files) {
            processed++;
            try {
                Optional<String> category = resolveCategory(file);
                if (category.isEmpty()) {
                    listener.onLog("Skipped: " + file.getFileName() + " (no matching rule)");
                    listener.onProgress(processed, files.size());
                    continue;
                }

                Path categoryFolder = directory.resolve(category.get());
                Files.createDirectories(categoryFolder);

                Path target = buildUniqueTarget(categoryFolder, file.getFileName().toString());
                Files.move(file, target, StandardCopyOption.ATOMIC_MOVE);

                moveRecords.add(new MoveRecord(file, target));
                listener.onLog("Moved: " + file.getFileName() + " -> " + directory.relativize(target));
            } catch (AtomicMoveNotSupportedException ex) {
                // Fallback when atomic move is unavailable
                Optional<String> category = resolveCategory(file);
                if (category.isPresent()) {
                    Path categoryFolder = directory.resolve(category.get());
                    Files.createDirectories(categoryFolder);

                    Path target = buildUniqueTarget(categoryFolder, file.getFileName().toString());
                    Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                    moveRecords.add(new MoveRecord(file, target));
                    listener.onLog("Moved (non-atomic): " + file.getFileName() + " -> " + directory.relativize(target));
                }
            } catch (AccessDeniedException ex) {
                listener.onLog("Permission denied: " + file.getFileName() + " (" + ex.getMessage() + ")");
            } catch (IOException ex) {
                listener.onLog("Failed to move: " + file.getFileName() + " (" + ex.getMessage() + ")");
            }

            listener.onProgress(processed, files.size());
        }

        history.push(moveRecords);
        listener.onStatus("Completed");
        listener.onLog("Organizing complete. Moved " + moveRecords.size() + " file(s).");
    }

    /**
     * Undo last organize operation.
     */
    public void undoLast(OrganizerListener listener) {
        if (history.isEmpty()) {
            listener.onLog("Nothing to undo.");
            return;
        }

        List<MoveRecord> lastMoves = history.pop();
        Collections.reverse(lastMoves);
        int total = lastMoves.size();
        int current = 0;

        listener.onStatus("Undoing...");
        for (MoveRecord record : lastMoves) {
            current++;
            try {
                Path parent = record.getFrom().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }

                Path source = record.getTo();
                if (!Files.exists(source)) {
                    listener.onLog("Undo skipped (missing): " + source.getFileName());
                    listener.onProgress(current, total);
                    continue;
                }

                Path target = buildUniqueTarget(record.getFrom().getParent(), record.getFrom().getFileName().toString());
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                listener.onLog("Undo: " + source.getFileName() + " -> " + target.getFileName());
            } catch (IOException ex) {
                listener.onLog("Undo failed: " + record.getTo().getFileName() + " (" + ex.getMessage() + ")");
            }
            listener.onProgress(current, total);
        }
        listener.onStatus("Completed");
        listener.onLog("Undo complete.");
    }

    /**
     * Optional advanced feature: watch selected directory for newly created files.
     */
    public WatchService startWatchService(Path directory, OrganizerListener listener) throws IOException {
        validateDirectory(directory);
        WatchService watchService = FileSystems.getDefault().newWatchService();
        directory.register(watchService, StandardWatchEventKinds.ENTRY_CREATE);

        Thread watcherThread = new Thread(() -> {
            listener.onLog("WatchService started for: " + directory);
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    Path relative = (Path) event.context();
                    Path createdPath = directory.resolve(relative);

                    // Delay slightly to reduce issues with files still being written.
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    if (Files.isRegularFile(createdPath)) {
                        try {
                            Optional<String> category = resolveCategory(createdPath);
                            if (category.isPresent()) {
                                Path categoryFolder = directory.resolve(category.get());
                                Files.createDirectories(categoryFolder);
                                Path target = buildUniqueTarget(categoryFolder, createdPath.getFileName().toString());
                                Files.move(createdPath, target, StandardCopyOption.REPLACE_EXISTING);
                                listener.onLog("Watch moved: " + createdPath.getFileName() + " -> " + directory.relativize(target));
                            } else {
                                listener.onLog("Watch skipped: " + createdPath.getFileName() + " (no rule)");
                            }
                        } catch (IOException ex) {
                            listener.onLog("Watch failed: " + createdPath.getFileName() + " (" + ex.getMessage() + ")");
                        }
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    break;
                }
            }
            listener.onLog("WatchService stopped.");
        }, "file-organizer-watch-thread");

        watcherThread.setDaemon(true);
        watcherThread.start();
        return watchService;
    }

    private void validateDirectory(Path directory) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("Directory cannot be null");
        }
        if (!Files.exists(directory)) {
            throw new NoSuchFileException("Path does not exist: " + directory);
        }
        if (!Files.isDirectory(directory)) {
            throw new NotDirectoryException("Not a directory: " + directory);
        }
    }

    private List<Path> listTopLevelFiles(Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    files.add(path);
                }
            }
        }
        return files;
    }

    private Optional<String> resolveCategory(Path file) {
        for (Rule rule : rules) {
            Optional<String> category = rule.resolveCategory(file);
            if (category.isPresent()) {
                return category;
            }
        }
        return Optional.empty();
    }

    /**
     * Creates a non-conflicting file path if file already exists.
     */
    private Path buildUniqueTarget(Path destinationFolder, String fileName) {
        Path candidate = destinationFolder.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }

        String name = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            name = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }

        int counter = 1;
        while (true) {
            Path next = destinationFolder.resolve(name + " (" + counter + ")" + extension);
            if (!Files.exists(next)) {
                return next;
            }
            counter++;
        }
    }
}

/**
 * Swing GUI for Smart File Organizer.
 */
class OrganizerGUI extends JFrame implements OrganizerListener {
    private final JTextArea logArea = new JTextArea();
    private final JButton selectFolderButton = new JButton("Select Folder");
    private final JButton startButton = new JButton("Start Organizing");
    private final JButton undoButton = new JButton("Undo Last");
    private final JButton watchButton = new JButton("Start Watch");
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel selectedPathLabel = new JLabel("No folder selected");
    private final JLabel statusLabel = new JLabel("Idle");

    private final FileOrganizer organizer = new FileOrganizer();
    private Path selectedDirectory;
    private WatchService watchService;

    public OrganizerGUI() {
        setTitle("Smart File Organizer");
        setSize(900, 600);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        applyTheme();
        configureRules();
        initComponents();
        registerActions();
    }

    private void applyTheme() {
        UIManager.put("Button.font", new Font("SansSerif", Font.PLAIN, 14));
        UIManager.put("Label.font", new Font("SansSerif", Font.PLAIN, 14));
        UIManager.put("TextArea.font", new Font("Monospaced", Font.PLAIN, 13));
        UIManager.put("ProgressBar.font", new Font("SansSerif", Font.BOLD, 12));
    }

    private void configureRules() {
        Map<String, String> extensionMap = new HashMap<>();

        // Images
        extensionMap.put("jpg", "Images");
        extensionMap.put("jpeg", "Images");
        extensionMap.put("png", "Images");
        extensionMap.put("gif", "Images");
        extensionMap.put("bmp", "Images");
        extensionMap.put("webp", "Images");
        extensionMap.put("svg", "Images");

        // Documents
        extensionMap.put("pdf", "Documents");
        extensionMap.put("doc", "Documents");
        extensionMap.put("docx", "Documents");
        extensionMap.put("txt", "Documents");
        extensionMap.put("ppt", "Documents");
        extensionMap.put("pptx", "Documents");
        extensionMap.put("xls", "Documents");
        extensionMap.put("xlsx", "Documents");
        extensionMap.put("csv", "Documents");
        extensionMap.put("md", "Documents");

        // Videos
        extensionMap.put("mp4", "Videos");
        extensionMap.put("mkv", "Videos");
        extensionMap.put("avi", "Videos");
        extensionMap.put("mov", "Videos");
        extensionMap.put("wmv", "Videos");

        // Music
        extensionMap.put("mp3", "Music");
        extensionMap.put("wav", "Music");
        extensionMap.put("flac", "Music");
        extensionMap.put("aac", "Music");

        // Archives
        extensionMap.put("zip", "Archives");
        extensionMap.put("rar", "Archives");
        extensionMap.put("7z", "Archives");
        extensionMap.put("tar", "Archives");
        extensionMap.put("gz", "Archives");

        // Code
        extensionMap.put("java", "Code");
        extensionMap.put("py", "Code");
        extensionMap.put("js", "Code");
        extensionMap.put("ts", "Code");
        extensionMap.put("html", "Code");
        extensionMap.put("css", "Code");
        extensionMap.put("json", "Code");
        extensionMap.put("xml", "Code");

        organizer.addRule(new ExtensionRule(extensionMap));

        // Optional custom keyword-based rules.
        Map<String, String> keywordMap = new LinkedHashMap<>();
        keywordMap.put("invoice", "Finance");
        keywordMap.put("receipt", "Finance");
        keywordMap.put("resume", "Career");
        keywordMap.put("cv", "Career");
        organizer.addRule(new KeywordRule(keywordMap));
    }

    private void initComponents() {
        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(content);

        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        topPanel.setBackground(new Color(245, 248, 255));
        topPanel.setBorder(new EmptyBorder(10, 10, 10, 10));

        JLabel title = new JLabel("Smart File Organizer");
        title.setFont(new Font("SansSerif", Font.BOLD, 24));
        title.setForeground(new Color(41, 78, 165));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttonPanel.setOpaque(false);

        stylePrimaryButton(selectFolderButton);
        stylePrimaryButton(startButton);
        styleSecondaryButton(undoButton);
        styleSecondaryButton(watchButton);

        startButton.setEnabled(false);
        undoButton.setEnabled(true);

        buttonPanel.add(selectFolderButton);
        buttonPanel.add(startButton);
        buttonPanel.add(undoButton);
        buttonPanel.add(watchButton);

        topPanel.add(title, BorderLayout.NORTH);
        topPanel.add(buttonPanel, BorderLayout.CENTER);
        topPanel.add(selectedPathLabel, BorderLayout.SOUTH);

        selectedPathLabel.setForeground(new Color(70, 70, 70));

        logArea.setEditable(false);
        logArea.setBackground(new Color(18, 18, 18));
        logArea.setForeground(new Color(216, 255, 216));
        logArea.setBorder(new EmptyBorder(8, 8, 8, 8));

        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Organizer Log"));

        JPanel bottomPanel = new JPanel(new BorderLayout(8, 8));
        bottomPanel.setBorder(new EmptyBorder(4, 0, 0, 0));

        progressBar.setStringPainted(true);
        progressBar.setValue(0);
        progressBar.setForeground(new Color(53, 132, 228));
        progressBar.setString("0%");

        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        statusLabel.setForeground(new Color(80, 80, 80));

        bottomPanel.add(progressBar, BorderLayout.CENTER);
        bottomPanel.add(statusLabel, BorderLayout.EAST);

        content.add(topPanel, BorderLayout.NORTH);
        content.add(scrollPane, BorderLayout.CENTER);
        content.add(bottomPanel, BorderLayout.SOUTH);

        log("Ready. Please select a folder to begin.");
    }

    private void registerActions() {
        selectFolderButton.addActionListener(this::onSelectFolder);
        startButton.addActionListener(this::onStartOrganizing);
        undoButton.addActionListener(this::onUndoLast);
        watchButton.addActionListener(this::onToggleWatch);
    }

    private void onSelectFolder(ActionEvent event) {
        JFileChooser chooser = new JFileChooser(FileSystemView.getFileSystemView().getHomeDirectory());
        chooser.setDialogTitle("Select Folder to Organize");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);

        int result = chooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            selectedDirectory = chooser.getSelectedFile().toPath();
            selectedPathLabel.setText("Selected: " + selectedDirectory.toAbsolutePath());
            startButton.setEnabled(true);
            log("Folder selected: " + selectedDirectory.toAbsolutePath());
            onProgress(0, 1);
            onStatus("Ready");
        }
    }

    private void onStartOrganizing(ActionEvent event) {
        if (selectedDirectory == null) {
            JOptionPane.showMessageDialog(this, "Please select a folder first.", "No Folder Selected", JOptionPane.WARNING_MESSAGE);
            return;
        }

        setControlsEnabled(false);
        onStatus("Processing...");

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                try {
                    organizer.organize(selectedDirectory, new OrganizerListener() {
                        @Override
                        public void onLog(String message) {
                            publish(message);
                        }

                        @Override
                        public void onProgress(int current, int total) {
                            int safeTotal = Math.max(total, 1);
                            int percent = (int) ((current * 100.0f) / safeTotal);
                            setProgress(percent);
                        }

                        @Override
                        public void onStatus(String status) {
                            publish("__STATUS__" + status);
                        }
                    });
                } catch (Exception ex) {
                    publish("Error: " + ex.getMessage());
                    publish("__STATUS__Error");
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    if (msg.startsWith("__STATUS__")) {
                        String status = msg.replace("__STATUS__", "");
                        statusLabel.setText(status);
                    } else {
                        log(msg);
                    }
                }
            }

            @Override
            protected void done() {
                try {
                    get();
                } catch (InterruptedException | ExecutionException e) {
                    log("Unexpected error: " + e.getMessage());
                    onStatus("Error");
                }
                setControlsEnabled(true);
            }
        };

        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                int progress = (Integer) evt.getNewValue();
                progressBar.setValue(progress);
                progressBar.setString(progress + "%");
            }
        });

        worker.execute();
    }

    private void onUndoLast(ActionEvent event) {
        setControlsEnabled(false);
        SwingWorker<Void, String> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                organizer.undoLast(new OrganizerListener() {
                    @Override
                    public void onLog(String message) {
                        publish(message);
                    }

                    @Override
                    public void onProgress(int current, int total) {
                        int safeTotal = Math.max(total, 1);
                        int percent = (int) ((current * 100.0f) / safeTotal);
                        setProgress(percent);
                    }

                    @Override
                    public void onStatus(String status) {
                        publish("__STATUS__" + status);
                    }
                });
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                for (String msg : chunks) {
                    if (msg.startsWith("__STATUS__")) {
                        statusLabel.setText(msg.replace("__STATUS__", ""));
                    } else {
                        log(msg);
                    }
                }
            }

            @Override
            protected void done() {
                setControlsEnabled(true);
            }
        };

        worker.addPropertyChangeListener(evt -> {
            if ("progress".equals(evt.getPropertyName())) {
                int progress = (Integer) evt.getNewValue();
                progressBar.setValue(progress);
                progressBar.setString(progress + "%");
            }
        });

        worker.execute();
    }

    private void onToggleWatch(ActionEvent event) {
        if (selectedDirectory == null) {
            JOptionPane.showMessageDialog(this, "Select a folder before starting WatchService.", "No Folder Selected", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (watchService == null) {
            try {
                watchService = organizer.startWatchService(selectedDirectory, this);
                watchButton.setText("Stop Watch");
                log("Realtime monitoring enabled.");
            } catch (IOException ex) {
                log("Unable to start watcher: " + ex.getMessage());
            }
        } else {
            try {
                watchService.close();
                watchService = null;
                watchButton.setText("Start Watch");
                log("Realtime monitoring disabled.");
            } catch (IOException ex) {
                log("Unable to stop watcher: " + ex.getMessage());
            }
        }
    }

    private void stylePrimaryButton(JButton button) {
        button.setBackground(new Color(53, 132, 228));
        button.setForeground(Color.WHITE);
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private void styleSecondaryButton(JButton button) {
        button.setBackground(new Color(238, 238, 238));
        button.setForeground(new Color(40, 40, 40));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    private void setControlsEnabled(boolean enabled) {
        selectFolderButton.setEnabled(enabled);
        startButton.setEnabled(enabled && selectedDirectory != null);
        undoButton.setEnabled(enabled);
    }

    private void log(String message) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        logArea.append("[" + timestamp + "] " + message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    @Override
    public void onLog(String message) {
        SwingUtilities.invokeLater(() -> log(message));
    }

    @Override
    public void onProgress(int current, int total) {
        SwingUtilities.invokeLater(() -> {
            int safeTotal = Math.max(total, 1);
            int percent = (int) ((current * 100.0f) / safeTotal);
            progressBar.setValue(percent);
            progressBar.setString(percent + "%");
        });
    }

    @Override
    public void onStatus(String status) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(status));
    }
}
