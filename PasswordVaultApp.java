import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableRowSorter;

// ╔══════════════════════════════════════════════════════════════════╗
// ║  PasswordEntry — OOP model for one saved credential             ║
// ╚══════════════════════════════════════════════════════════════════╝
class PasswordEntry {

    private String site;
    private String username;
    private String encodedPassword;   // stored as Base64

    public PasswordEntry(String site, String username, String encodedPassword) {
        this.site            = site.trim();
        this.username        = username.trim();
        this.encodedPassword = encodedPassword.trim();
    }

    // ── Getters ──────────────────────────────────────────────────
    public String getSite()     { return site; }
    public String getUsername() { return username; }

    /** Returns the raw Base64-encoded value (used for file storage). */
    public String getEncodedPassword() { return encodedPassword; }

    /** Decodes the password for display / clipboard. */
    public String getPlainPassword() {
        try {
            return new String(Base64.getDecoder().decode(encodedPassword));
        } catch (IllegalArgumentException ex) {
            return encodedPassword;   // legacy plain-text fallback
        }
    }

    // ── Setters ──────────────────────────────────────────────────
    public void setSite(String site)                        { this.site = site.trim(); }
    public void setUsername(String username)                { this.username = username.trim(); }
    public void setEncodedPassword(String encodedPassword)  { this.encodedPassword = encodedPassword.trim(); }

    // ── CSV serialisation ─────────────────────────────────────────
    /** Returns a CSV-safe line: site,username,encodedPassword */
    public String toCsvLine() {
        return csvEscape(site) + "," + csvEscape(username) + "," + csvEscape(encodedPassword);
    }

    private static String csvEscape(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    @Override
    public String toString() {
        return "PasswordEntry{site='" + site + "', username='" + username + "'}";
    }
}

// ╔══════════════════════════════════════════════════════════════════╗
// ║  VaultManager — business logic + file I/O                       ║
// ╚══════════════════════════════════════════════════════════════════╝
class VaultManager {

    private static final String DATA_FILE   = "passwords.csv";
    private static final String CSV_HEADER  = "site,username,password";

    private final List<PasswordEntry> entries = new ArrayList<>();

    public VaultManager() {
        loadFromFile();
    }

    // ── CRUD ──────────────────────────────────────────────────────

    /** Encodes plainPassword in Base64, saves entry, auto-saves file. */
    public void addEntry(String site, String username, String plainPassword) {
        String encoded = Base64.getEncoder().encodeToString(plainPassword.getBytes());
        entries.add(new PasswordEntry(site, username, encoded));
        saveToFile();
    }

    /** Removes entry at logical index; returns true on success. */
    public boolean deleteEntry(int index) {
        if (index < 0 || index >= entries.size()) return false;
        entries.remove(index);
        saveToFile();
        return true;
    }

    /** Removes a specific entry object (useful when searching). */
    public boolean deleteEntry(PasswordEntry target) {
        boolean removed = entries.remove(target);
        if (removed) saveToFile();
        return removed;
    }

    public List<PasswordEntry> getAll() {
        return Collections.unmodifiableList(entries);
    }

    public int getTotalCount() {
        return entries.size();
    }

    /** Case-insensitive search across site name and username. */
    public List<PasswordEntry> search(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(e -> e.getSite().toLowerCase(Locale.ROOT).contains(q)
                          || e.getUsername().toLowerCase(Locale.ROOT).contains(q))
                .collect(Collectors.toList());
    }

    /** Sorts all entries alphabetically by site name. */
    public void sortBySite() {
        entries.sort(Comparator.comparing(e -> e.getSite().toLowerCase(Locale.ROOT)));
        saveToFile();
    }

    // ── File I/O ──────────────────────────────────────────────────

    public void saveToFile() {
        try (PrintWriter pw = new PrintWriter(new FileWriter(DATA_FILE))) {
            pw.println(CSV_HEADER);
            for (PasswordEntry e : entries) {
                pw.println(e.toCsvLine());
            }
        } catch (IOException ex) {
            System.err.println("[VaultManager] Save error: " + ex.getMessage());
        }
    }

    private void loadFromFile() {
        File f = new File(DATA_FILE);
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            boolean firstLine = true;
            while ((line = br.readLine()) != null) {
                if (firstLine) { firstLine = false; continue; }   // skip header
                String[] parts = parseCsvLine(line);
                if (parts.length >= 3) {
                    entries.add(new PasswordEntry(parts[0], parts[1], parts[2]));
                }
            }
        } catch (IOException ex) {
            System.err.println("[VaultManager] Load error: " + ex.getMessage());
        }
    }

    // ── Google Passwords CSV import ───────────────────────────────

    /**
     * Parses a Google Password Manager export CSV.
     * Expected columns (auto-detected): name/url, username, password.
     *
     * @return number of entries successfully imported
     */
    public int importGoogleCsv(File csvFile) throws IOException {
        int imported = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String line;
            boolean isHeader = true;
            int urlIdx = 0, userIdx = 2, passIdx = 3;

            while ((line = br.readLine()) != null) {
                String[] parts = parseCsvLine(line);
                if (isHeader) {
                    isHeader = false;
                    // Detect column positions from header row
                    for (int i = 0; i < parts.length; i++) {
                        String col = parts[i].toLowerCase(Locale.ROOT);
                        if (col.contains("url") || col.equals("name"))        urlIdx  = i;
                        if (col.contains("username") || col.contains("user")) userIdx = i;
                        if (col.contains("password"))                         passIdx = i;
                    }
                    continue;
                }
                int maxIdx = Math.max(urlIdx, Math.max(userIdx, passIdx));
                if (parts.length <= maxIdx) continue;

                String site  = parts[urlIdx].trim();
                String user  = parts[userIdx].trim();
                String pass  = parts[passIdx].trim();

                if (site.isEmpty() && user.isEmpty()) continue;

                String encoded = Base64.getEncoder().encodeToString(pass.getBytes());
                entries.add(new PasswordEntry(site, user, encoded));
                imported++;
            }
        }
        if (imported > 0) saveToFile();
        return imported;
    }

    // ── CSV parser (handles quoted fields, escaped double-quotes) ─

    static String[] parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                // Escaped quote inside quoted field: ""
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                result.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        result.add(sb.toString());
        return result.toArray(new String[0]);
    }
}

// ╔══════════════════════════════════════════════════════════════════╗
// ║  PasswordVaultApp — main GUI window (JFrame) + event handling   ║
// ╚══════════════════════════════════════════════════════════════════╝
public class PasswordVaultApp extends JFrame {

    // ─── Dark-mode colour palette ─────────────────────────────────
    private static final Color BG_DARK      = new Color(13,  17,  23);
    private static final Color BG_PANEL     = new Color(22,  27,  34);
    private static final Color BG_INPUT     = new Color(30,  37,  48);
    private static final Color BG_TABLE     = new Color(18,  23,  30);
    private static final Color BG_ROW_ALT   = new Color(25,  32,  44);
    private static final Color BG_SEL       = new Color(31,  82, 154);
    private static final Color ACCENT_BLUE  = new Color(56, 139, 253);
    private static final Color ACCENT_GREEN = new Color(63, 185,  80);
    private static final Color ACCENT_RED   = new Color(248, 81,  73);
    private static final Color ACCENT_ORG   = new Color(255, 166,   0);
    private static final Color ACCENT_PRP   = new Color(139,  92, 246);
    private static final Color TEXT_MAIN    = new Color(230, 237, 243);
    private static final Color TEXT_DIM     = new Color(100, 115, 135);
    private static final Color BORDER_CLR   = new Color(48,  54,  61);

    // ─── Fonts ────────────────────────────────────────────────────
    private static final Font F_TITLE  = new Font("Segoe UI",  Font.BOLD,  20);
    private static final Font F_LABEL  = new Font("Segoe UI",  Font.BOLD,  12);
    private static final Font F_INPUT  = new Font("Segoe UI",  Font.PLAIN, 13);
    private static final Font F_BTN    = new Font("Segoe UI",  Font.BOLD,  12);
    private static final Font F_TABLE  = new Font("Consolas",  Font.PLAIN, 12);
    private static final Font F_HDR    = new Font("Segoe UI",  Font.BOLD,  12);
    private static final Font F_SMALL  = new Font("Segoe UI",  Font.PLAIN, 11);

    // ─── Stored master hash (Base64 of "admin123") ────────────────
    private static String MASTER_HASH = "YWRtaW4xMjM=";

    // ─── Business logic ───────────────────────────────────────────
    private final VaultManager vault = new VaultManager();

    // ─── UI state ─────────────────────────────────────────────────
    private JTextField     tfSite, tfUsername, tfSearch;
    private JPasswordField pfPassword;
    private JTable         table;
    private DefaultTableModel tableModel;
    private JLabel         lblStatus, lblCount;
    private JButton        btnTogglePwd;
    private boolean        pwdsVisible  = false;
    private boolean        isFiltered   = false;

    /** Tracks which PasswordEntry objects are currently shown in the table. */
    private List<PasswordEntry> displayedEntries = new ArrayList<>();

    // ══════════════════════════════════════════════════════════════
    //  ENTRY POINT
    // ══════════════════════════════════════════════════════════════
    public static void main(String[] args) {
        // Apply system look-and-feel then override with custom colours
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); }
        catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            if (showLoginDialog()) {
                new PasswordVaultApp();
            } else {
                System.exit(0);
            }
        });
    }

    // ─── Master password login ────────────────────────────────────
    private static boolean showLoginDialog() {
        JPasswordField pf = new JPasswordField(20);
        pf.setFont(F_INPUT);
        pf.setBackground(new Color(30, 37, 48));
        pf.setForeground(new Color(230, 237, 243));
        pf.setCaretColor(new Color(56, 139, 253));
        pf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(48, 54, 61)),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));

        JLabel icon = new JLabel("🔐", SwingConstants.CENTER);
        icon.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 52));

        JLabel title = new JLabel("Password Vault", SwingConstants.CENTER);
        title.setFont(F_TITLE);
        title.setForeground(new Color(230, 237, 243));

        JLabel hint = new JLabel("Default password: admin123", SwingConstants.CENTER);
        hint.setFont(F_SMALL);
        hint.setForeground(new Color(100, 115, 135));

        JPanel body = new JPanel(new GridLayout(4, 1, 6, 6));
        body.setBackground(new Color(22, 27, 34));
        body.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));
        body.add(icon);
        body.add(title);
        body.add(pf);
        body.add(hint);

        int result = JOptionPane.showConfirmDialog(
                null, body, "🔒 Vault Login",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) return false;

        String entered = new String(pf.getPassword());
        String hash    = Base64.getEncoder().encodeToString(entered.getBytes());

        if (hash.equals(MASTER_HASH)) return true;

        JOptionPane.showMessageDialog(null,
                "❌  Incorrect master password.\nDefault is: admin123",
                "Access Denied", JOptionPane.ERROR_MESSAGE);
        return false;
    }

    // ══════════════════════════════════════════════════════════════
    //  CONSTRUCTOR — assembles the full GUI
    // ══════════════════════════════════════════════════════════════
    public PasswordVaultApp() {
        super("🔐 Password Vault");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1020, 700);
        setMinimumSize(new Dimension(820, 560));
        setLocationRelativeTo(null);

        getContentPane().setBackground(BG_DARK);
        setLayout(new BorderLayout());

        add(buildTitleBar(),  BorderLayout.NORTH);
        add(buildCenter(),    BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);

        refreshTable(vault.getAll());
        setVisible(true);
    }

    // ──────────────────────────────────────────────────────────────
    //  PANEL BUILDERS
    // ──────────────────────────────────────────────────────────────

    /** Top gradient banner with app name and entry count. */
    private JPanel buildTitleBar() {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g;
                g2.setPaint(new GradientPaint(0, 0, new Color(13,17,23),
                        getWidth(), 0, new Color(22,27,60)));
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_CLR),
                BorderFactory.createEmptyBorder(16, 22, 16, 22)));

        JLabel appName = new JLabel("🔐  Password Vault");
        appName.setFont(F_TITLE);
        appName.setForeground(TEXT_MAIN);

        JLabel tagline = new JLabel("All credentials encrypted with Base64 · Auto-saves on every change");
        tagline.setFont(F_SMALL);
        tagline.setForeground(TEXT_DIM);

        JPanel left = new JPanel(new GridLayout(2, 1, 0, 2));
        left.setOpaque(false);
        left.add(appName);
        left.add(tagline);

        lblCount = new JLabel("0 entries stored   ");
        lblCount.setFont(F_SMALL);
        lblCount.setForeground(ACCENT_BLUE);
        lblCount.setHorizontalAlignment(SwingConstants.RIGHT);

        p.add(left,     BorderLayout.WEST);
        p.add(lblCount, BorderLayout.EAST);
        return p;
    }

    /** Combines input panel, table, and button bar. */
    private JPanel buildCenter() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG_DARK);
        p.add(buildInputPanel(), BorderLayout.NORTH);
        p.add(buildTablePanel(), BorderLayout.CENTER);
        p.add(buildButtonBar(),  BorderLayout.SOUTH);
        return p;
    }

    /** Input row: Website, Username, Password, Show/Hide, Search. */
    private JPanel buildInputPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(BG_PANEL);
        outer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_CLR),
                BorderFactory.createEmptyBorder(14, 20, 14, 20)));

        tfSite     = makePlaceholderField("e.g. github.com");
        tfUsername = makePlaceholderField("e.g. john@example.com");
        pfPassword = makePasswordField();
        tfSearch   = makePlaceholderField("Search by site or username…");

        btnTogglePwd = makeButton("👁  Show", ACCENT_PRP);
        btnTogglePwd.addActionListener(e -> togglePasswordVisibility());

        JButton btnAdd    = makeButton("＋  Add Entry",   ACCENT_GREEN);
        JButton btnSearch = makeButton("🔍  Search",      ACCENT_BLUE);
        JButton btnClear  = makeButton("✕  Clear Filter", TEXT_DIM);

        btnAdd   .addActionListener(e -> handleAddEntry());
        btnSearch.addActionListener(e -> handleSearch());
        btnClear .addActionListener(e -> handleClearSearch());

        // ── Grid layout ──────────────────────────────────────────
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBackground(BG_PANEL);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 5, 3, 5);
        g.fill   = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.WEST;

        // Row 0 — labels
        g.gridy = 0; g.weightx = 0;
        addSmallLabel(grid, "🌐  Website",   g, 0, 2);
        addSmallLabel(grid, "👤  Username",  g, 2, 2);
        addSmallLabel(grid, "🔑  Password",  g, 4, 2);
        addSmallLabel(grid, "🔍  Quick Search", g, 7, 3);

        // Row 1 — fields and buttons
        g.gridy = 1;
        g.gridx = 0; g.gridwidth = 2; g.weightx = 1.0; grid.add(tfSite,       g);
        g.gridx = 2; g.gridwidth = 2; g.weightx = 1.0; grid.add(tfUsername,   g);
        g.gridx = 4; g.gridwidth = 2; g.weightx = 1.0; grid.add(pfPassword,   g);
        g.gridx = 6; g.gridwidth = 1; g.weightx = 0;   grid.add(btnTogglePwd, g);
        g.gridx = 7; g.gridwidth = 3; g.weightx = 1.5; grid.add(tfSearch,     g);
        g.gridx = 10; g.gridwidth = 1; g.weightx = 0;  grid.add(btnSearch,    g);
        g.gridx = 11;                                   grid.add(btnClear,     g);
        g.gridx = 12;                                   grid.add(btnAdd,       g);

        outer.add(grid, BorderLayout.CENTER);
        return outer;
    }

    /** JTable inside a JScrollPane with alternating rows and custom renderer. */
    private JScrollPane buildTablePanel() {
        String[] columnNames = {"#", "🌐  Website", "👤  Username", "🔑  Password", "Action"};
        tableModel = new DefaultTableModel(columnNames, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };

        table = new JTable(tableModel);
        table.setFont(F_TABLE);
        table.setForeground(TEXT_MAIN);
        table.setBackground(BG_TABLE);
        table.setSelectionBackground(BG_SEL);
        table.setSelectionForeground(Color.WHITE);
        table.setGridColor(BORDER_CLR);
        table.setRowHeight(34);
        table.setShowVerticalLines(true);
        table.setShowHorizontalLines(true);
        table.setIntercellSpacing(new Dimension(1, 1));
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // Header styling
        JTableHeader header = table.getTableHeader();
        header.setFont(F_HDR);
        header.setBackground(BG_PANEL);
        header.setForeground(TEXT_MAIN);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER_CLR));
        header.setReorderingAllowed(false);

        // Column widths
        int[] widths = {40, 260, 230, 210, 70};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        // Custom cell renderer
        DefaultTableCellRenderer cellRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable tbl, Object value,
                    boolean isSelected, boolean hasFocus, int row, int col) {
                super.getTableCellRendererComponent(tbl, value, isSelected, hasFocus, row, col);
                Color bg = isSelected ? BG_SEL : (row % 2 == 0 ? BG_TABLE : BG_ROW_ALT);
                setBackground(bg);
                setFont(col == 3 ? new Font("Consolas", Font.PLAIN, 12) : F_TABLE);
                setForeground(col == 3 ? ACCENT_ORG : (col == 4 ? ACCENT_BLUE : TEXT_MAIN));
                setHorizontalAlignment(col == 0 || col == 4 ? CENTER : LEFT);
                setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
                return this;
            }
        };
        table.setDefaultRenderer(Object.class, cellRenderer);

        // Enable sorting via column header clicks
        TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);

        // Double-click → reveal password dialog
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) handleRevealPassword();
            }
        });

        JScrollPane sp = new JScrollPane(table);
        sp.setBackground(BG_TABLE);
        sp.getViewport().setBackground(BG_TABLE);
        sp.setBorder(BorderFactory.createLineBorder(BORDER_CLR));
        return sp;
    }

    /** Bottom row of action buttons. */
    private JPanel buildButtonBar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 12));
        p.setBackground(BG_DARK);
        p.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_CLR));

        JButton btnDelete    = makeButton("🗑  Delete Selected",   ACCENT_RED);
        JButton btnImport    = makeButton("📂  Import Google CSV", ACCENT_BLUE);
        JButton btnSort      = makeButton("🔤  Sort A → Z",        ACCENT_PRP);
        JButton btnCopy      = makeButton("📋  Copy Password",     ACCENT_ORG);
        JButton btnChangePwd = makeButton("🔒  Change Master",     TEXT_DIM);

        btnDelete   .addActionListener(e -> handleDelete());
        btnImport   .addActionListener(e -> handleImportCsv());
        btnSort     .addActionListener(e -> handleSort());
        btnCopy     .addActionListener(e -> handleCopyPassword());
        btnChangePwd.addActionListener(e -> handleChangeMasterPassword());

        p.add(btnDelete);
        p.add(btnImport);
        p.add(btnSort);
        p.add(btnCopy);
        p.add(btnChangePwd);
        return p;
    }

    /** Thin bar at the very bottom with status text and keyboard hints. */
    private JPanel buildStatusBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(BG_PANEL);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, BORDER_CLR),
                BorderFactory.createEmptyBorder(6, 16, 6, 16)));

        lblStatus = new JLabel("Ready.");
        lblStatus.setFont(F_SMALL);
        lblStatus.setForeground(TEXT_DIM);

        JLabel shortcuts = new JLabel("Double-click row → reveal password   ·   Select row → copy / delete");
        shortcuts.setFont(F_SMALL);
        shortcuts.setForeground(TEXT_DIM);

        p.add(lblStatus, BorderLayout.WEST);
        p.add(shortcuts, BorderLayout.EAST);
        return p;
    }

    // ──────────────────────────────────────────────────────────────
    //  EVENT HANDLERS
    // ──────────────────────────────────────────────────────────────

    /** Validates inputs, calls vault.addEntry, refreshes table. */
    private void handleAddEntry() {
        String site  = tfSite.getText().trim();
        String user  = tfUsername.getText().trim();
        String pass  = new String(pfPassword.getPassword()).trim();

        if (site.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "⚠️  All three fields are required:\n  Website  ·  Username  ·  Password",
                    "Incomplete Entry", JOptionPane.WARNING_MESSAGE);
            return;
        }

        vault.addEntry(site, user, pass);
        clearInputFields();
        refreshTable(vault.getAll());
        setStatus("✅  Entry added for: " + site);
    }

    /** Deletes the selected table row after confirmation. */
    private void handleDelete() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            JOptionPane.showMessageDialog(this,
                    "⚠️  Please select a row first.", "Nothing Selected",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }
        int modelRow = table.convertRowIndexToModel(viewRow);
        PasswordEntry target = displayedEntries.get(modelRow);

        int confirm = JOptionPane.showConfirmDialog(this,
                "Delete the entry for \"" + target.getSite() + "\"?",
                "Confirm Deletion", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        vault.deleteEntry(target);
        List<PasswordEntry> next = isFiltered ? vault.search(tfSearch.getText()) : vault.getAll();
        refreshTable(next);
        setStatus("🗑  Deleted entry for: " + target.getSite());
    }

    /** Filters the table by the search query. */
    private void handleSearch() {
        String q = tfSearch.getText().trim();
        if (q.isEmpty()) { handleClearSearch(); return; }
        List<PasswordEntry> results = vault.search(q);
        isFiltered = true;
        refreshTable(results);
        setStatus("🔍  " + results.size() + " result(s) for \"" + q + "\"");
    }

    /** Restores full table and clears search text. */
    private void handleClearSearch() {
        tfSearch.setText("");
        isFiltered = false;
        refreshTable(vault.getAll());
        setStatus("Showing all entries.");
    }

    /** Toggles plain text vs masked display for table passwords. */
    private void togglePasswordVisibility() {
        pwdsVisible = !pwdsVisible;
        btnTogglePwd.setText(pwdsVisible ? "🙈  Hide" : "👁  Show");
        List<PasswordEntry> current = isFiltered ? vault.search(tfSearch.getText()) : vault.getAll();
        refreshTable(current);
    }

    /** Shows a popup dialog with the plain-text password for the selected row. */
    private void handleRevealPassword() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) return;
        int modelRow = table.convertRowIndexToModel(viewRow);
        PasswordEntry e = displayedEntries.get(modelRow);

        JTextField pwField = new JTextField(e.getPlainPassword());
        pwField.setEditable(false);
        pwField.setFont(new Font("Consolas", Font.BOLD, 16));
        pwField.setBackground(BG_INPUT);
        pwField.setForeground(ACCENT_ORG);
        pwField.setCaretColor(ACCENT_ORG);
        pwField.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBackground(BG_PANEL);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        JLabel site = new JLabel("🌐  " + e.getSite());
        site.setFont(F_LABEL); site.setForeground(ACCENT_BLUE);

        JLabel userLabel = new JLabel("👤  " + e.getUsername());
        userLabel.setFont(F_INPUT); userLabel.setForeground(TEXT_MAIN);

        JLabel tip = new JLabel("Select the text above to copy.");
        tip.setFont(F_SMALL); tip.setForeground(TEXT_DIM);

        JPanel info = new JPanel(new GridLayout(2, 1, 2, 2));
        info.setBackground(BG_PANEL);
        info.add(site); info.add(userLabel);

        panel.add(info,    BorderLayout.NORTH);
        panel.add(pwField, BorderLayout.CENTER);
        panel.add(tip,     BorderLayout.SOUTH);

        JOptionPane.showMessageDialog(this, panel,
                "🔑  Password Details", JOptionPane.PLAIN_MESSAGE);
    }

    /** Copies the selected row's plain-text password to the system clipboard. */
    private void handleCopyPassword() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) { setStatus("⚠️  Select a row first."); return; }
        int modelRow = table.convertRowIndexToModel(viewRow);
        PasswordEntry e = displayedEntries.get(modelRow);
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(e.getPlainPassword()), null);
        setStatus("📋  Password for \"" + e.getSite() + "\" copied to clipboard.");
    }

    /** Opens a file chooser and imports a Google Password CSV. */
    private void handleImportCsv() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Google Passwords Export (.csv)");
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files (*.csv)", "csv"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        try {
            int count = vault.importGoogleCsv(chooser.getSelectedFile());
            refreshTable(vault.getAll());
            JOptionPane.showMessageDialog(this,
                    "✅  Imported " + count + " password(s) successfully.",
                    "Import Complete", JOptionPane.INFORMATION_MESSAGE);
            setStatus("📂  Imported " + count + " entries from CSV.");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                    "❌  Failed to read the CSV file:\n" + ex.getMessage(),
                    "Import Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Sorts vault entries alphabetically and refreshes the table. */
    private void handleSort() {
        vault.sortBySite();
        refreshTable(isFiltered ? vault.search(tfSearch.getText()) : vault.getAll());
        setStatus("🔤  Entries sorted A → Z.");
    }

    /** Lets the user change the master password (stored in memory as Base64). */
    private void handleChangeMasterPassword() {
        JPasswordField pf1 = makePasswordField();
        JPasswordField pf2 = makePasswordField();

        JPanel panel = new JPanel(new GridLayout(4, 1, 6, 6));
        panel.setBackground(BG_PANEL);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        panel.add(labelledRow("New Master Password:", pf1));
        panel.add(new JLabel());
        panel.add(labelledRow("Confirm Password:", pf2));

        int res = JOptionPane.showConfirmDialog(this, panel,
                "🔒  Change Master Password",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (res != JOptionPane.OK_OPTION) return;

        String p1 = new String(pf1.getPassword());
        String p2 = new String(pf2.getPassword());

        if (p1.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "Password cannot be empty.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!p1.equals(p2)) {
            JOptionPane.showMessageDialog(this,
                    "Passwords do not match.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        MASTER_HASH = Base64.getEncoder().encodeToString(p1.getBytes());
        JOptionPane.showMessageDialog(this,
                "✅  Master password updated for this session.\n" +
                "(Persist the new hash in source code to make it permanent.)",
                "Success", JOptionPane.INFORMATION_MESSAGE);
        setStatus("🔒  Master password changed for this session.");
    }

    // ──────────────────────────────────────────────────────────────
    //  TABLE REFRESH
    // ──────────────────────────────────────────────────────────────

    private void refreshTable(List<PasswordEntry> list) {
        displayedEntries = new ArrayList<>(list);
        tableModel.setRowCount(0);

        int rowNumber = 1;
        for (PasswordEntry e : list) {
            String pwCell = pwdsVisible ? e.getPlainPassword() : "••••••••";
            tableModel.addRow(new Object[]{rowNumber++, e.getSite(), e.getUsername(), pwCell, "👁 View"});
        }

        int total = vault.getTotalCount();
        lblCount.setText(total + (total == 1 ? " entry" : " entries") + " stored   ");
    }

    // ──────────────────────────────────────────────────────────────
    //  UI FACTORY HELPERS
    // ──────────────────────────────────────────────────────────────

    private JTextField makePlaceholderField(String placeholder) {
        JTextField tf = new JTextField(16) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty() && !isFocusOwner()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                            RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(TEXT_DIM);
                    g2.setFont(F_INPUT.deriveFont(Font.ITALIC));
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(placeholder, 10, (getHeight() + fm.getAscent() - fm.getDescent()) / 2);
                    g2.dispose();
                }
            }
        };
        tf.setFont(F_INPUT);
        tf.setBackground(BG_INPUT);
        tf.setForeground(TEXT_MAIN);
        tf.setCaretColor(ACCENT_BLUE);
        tf.setOpaque(true);
        tf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_CLR),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        return tf;
    }

    private JPasswordField makePasswordField() {
        JPasswordField pf = new JPasswordField(16);
        pf.setFont(F_INPUT);
        pf.setBackground(BG_INPUT);
        pf.setForeground(ACCENT_ORG);
        pf.setCaretColor(ACCENT_BLUE);
        pf.setEchoChar('●');
        pf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_CLR),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)));
        return pf;
    }

    private JButton makeButton(String label, Color accentColor) {
        JButton btn = new JButton(label) {
            private boolean hovered = false;
            {
                addMouseListener(new MouseAdapter() {
                    @Override public void mouseEntered(MouseEvent e) { hovered = true;  repaint(); }
                    @Override public void mouseExited (MouseEvent e) { hovered = false; repaint(); }
                });
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color bg = getModel().isPressed() ? accentColor.darker()
                         : hovered               ? accentColor
                         : BG_INPUT;
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setFont(F_BTN);
        btn.setForeground(TEXT_MAIN);
        btn.setContentAreaFilled(false);
        btn.setOpaque(false);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accentColor.darker(), 1),
                BorderFactory.createEmptyBorder(7, 14, 7, 14)));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private void addSmallLabel(JPanel grid, String text, GridBagConstraints g,
                               int x, int width) {
        g.gridx = x; g.gridwidth = width; g.weightx = 0;
        JLabel lbl = new JLabel(text);
        lbl.setFont(F_SMALL);
        lbl.setForeground(TEXT_DIM);
        grid.add(lbl, g);
    }

    private JPanel labelledRow(String labelText, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.setBackground(BG_PANEL);
        JLabel lbl = new JLabel(labelText);
        lbl.setFont(F_SMALL);
        lbl.setForeground(TEXT_DIM);
        p.add(lbl,   BorderLayout.NORTH);
        p.add(field, BorderLayout.CENTER);
        return p;
    }

    private void clearInputFields() {
        tfSite.setText("");
        tfUsername.setText("");
        pfPassword.setText("");
    }

    private void setStatus(String msg) {
        lblStatus.setText(msg);
    }
}