package de.hft.suboptimizer;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.*;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.awt.event.*;

public class SubOptApp extends JFrame {
    /*
     * Relevant connection details. The database should be pre-populated with the provided schema.sql file described by the README.md
     */
    private static final String HOST = "193.196.143.168";
    private static final String USER = "dk6s_12vafl1bif";
    private static final String PASS = "I<3Database";
    private static final String URL  =
        "jdbc:mysql://" + HOST + ":3306/dk6sp_subopt?serverTimezone=UTC";

    /*
     * needed datastructures
     */
    private Connection conn;
    private final JComboBox<UserItem> userBox = new JComboBox<>();
    private final DefaultTableModel subModel =
        new DefaultTableModel(new String[]{"Sub ID","Service","Tier",
                "Price","Period","Renewal","Trial?"}, 0);
    private final JLabel spendLabel = new JLabel("Monthly spend: -");
    private final JTextArea wasteArea = new JTextArea(6, 40);
    private final DefaultListModel<NotifItem> notifModel = new DefaultListModel<>();
    private final JList<NotifItem> notifList = new JList<>(notifModel);

    public SubOptApp() {
        super("Subscription & Fixed-Cost Optimizer");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(820, 560);
        setLocationRelativeTo(null);
        buildUi();
        connect();
        loadUsers();
        loadNotifications();
    }

    /**
     * Builds the user interface and sets up event handlers. The UI consists of:
     * - A top panel with user selection, add/cancel/waste buttons and settings
     * - A center table showing active subscriptions for the selected user
     * - A bottom panel showing monthly spend, overlapping services ("waste") and notifications
     */
    private void buildUi() {
        JPanel topLeft = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topLeft.add(new JLabel("User:"));
        topLeft.add(userBox);
        JButton add    = new JButton("+");
        JButton waste  = new JButton("Find wasted overlap");
        JButton cancel = new JButton("x");
        topLeft.add(add);
        topLeft.add(cancel);
        topLeft.add(waste);

        JButton settings = new JButton("Settings");
        JPanel topRight = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        topRight.add(settings);

        JPanel top = new JPanel(new BorderLayout());
        top.add(topLeft, BorderLayout.WEST);
        top.add(topRight, BorderLayout.EAST);

        JTable table = new JTable(subModel);

        wasteArea.setEditable(false);
        notifList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                if (value instanceof NotifItem && !((NotifItem) value).isRead)
                    lbl.setFont(lbl.getFont().deriveFont(Font.BOLD));
                return lbl;
            }
        });
        notifList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    NotifItem n = notifList.getSelectedValue();
                    if (n != null && !n.isRead) markNotificationRead(n);
                }
            }
        });


        JScrollPane wasteScroll = new JScrollPane(wasteArea);
        wasteScroll.setBorder(BorderFactory.createTitledBorder("Overlap / waste"));
        JScrollPane notifScroll = new JScrollPane(notifList);
        notifScroll.setBorder(BorderFactory.createTitledBorder("Notifications"));

        JSplitPane bottomSplit = new JSplitPane(
                JSplitPane.HORIZONTAL_SPLIT, wasteScroll, notifScroll);
        bottomSplit.setResizeWeight(0.5);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(spendLabel, BorderLayout.NORTH);
        bottom.add(bottomSplit, BorderLayout.CENTER);

        userBox.addActionListener(e -> { loadSubscriptions(); loadSpend(); wasteArea.setText(""); });
        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);
        add.addActionListener(e -> addSubscription());
        waste.addActionListener(e -> loadWaste());
        cancel.addActionListener(e -> {cancelSelected(table); loadWaste();});
        settings.addActionListener(e -> openSettings());
    }

    private void connect() {
        try {
            conn = DriverManager.getConnection(URL, USER, PASS);
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this,
                "Connection failed: " + ex.getMessage());
        }
    }

    private void addSubscription() {
        UserItem u = (UserItem) userBox.getSelectedItem();
        if (u == null) {
            JOptionPane.showMessageDialog(this, "Please select a user first.");
            return;
        }

        // Load existing tiers
        JComboBox<TierItem> tierBox = new JComboBox<>();
        String tierSql =
                "SELECT pt.tier_id, sv.name, pt.tier_name, pt.price, pt.billing_period " +
                        "FROM price_tier pt " +
                        "JOIN service sv ON pt.service_id = sv.service_id " +
                        "ORDER BY sv.name, pt.tier_name";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(tierSql)) {
            while (rs.next()) {
                tierBox.addItem(new TierItem(rs.getInt(1),
                        rs.getString(2) + " – " + rs.getString(3) +
                                " (" + rs.getBigDecimal(4) + " EUR/" + rs.getString(5) + ")"));
            }
        } catch (SQLException ex) { showError(ex); return; }

        // Load categories for new service creation
        JComboBox<CategoryItem> catBox = new JComboBox<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT category_id, name FROM category ORDER BY name")) {
            while (rs.next())
                catBox.addItem(new CategoryItem(rs.getInt(1), rs.getString(2)));
        } catch (SQLException ex) { showError(ex); return; }

        // mode selection: existing or new subscription
        JRadioButton useExisting = new JRadioButton("Choose existing subscription", true);
        JRadioButton useNew      = new JRadioButton("Create new subscription");
        ButtonGroup grp = new ButtonGroup();
        grp.add(useExisting); grp.add(useNew);

        // fields for new subscription
        JTextField serviceNameField = new JTextField();
        JTextField tierNameField    = new JTextField();
        JTextField priceField       = new JTextField();
        JComboBox<String> billingBox = new JComboBox<>(new String[]{"MONTHLY", "YEARLY"});

        // common fields
        JTextField startField    = new JTextField(LocalDate.now().toString());
        JTextField renewalField  = new JTextField(LocalDate.now().plusMonths(1).toString());
        JCheckBox  trialBox      = new JCheckBox();
        JTextField trialEndField = new JTextField();

        // group panels
        JPanel modePanel = new JPanel(new GridLayout(0, 1));
        modePanel.add(useExisting);
        modePanel.add(useNew);

        JPanel existingPanel = new JPanel(new GridLayout(0, 2, 6, 6));
        existingPanel.add(new JLabel("Tarif:"));        existingPanel.add(tierBox);

        JPanel newServicePanel = new JPanel(new GridLayout(0, 2, 6, 6));
        newServicePanel.setBorder(BorderFactory.createTitledBorder("Neuer Dienst"));
        newServicePanel.add(new JLabel("Service-Name:"));    newServicePanel.add(serviceNameField);
        newServicePanel.add(new JLabel("Category:"));      newServicePanel.add(catBox);
        newServicePanel.add(new JLabel("Tier-Name:")); newServicePanel.add(tierNameField);
        newServicePanel.add(new JLabel("Costs (EUR):"));   newServicePanel.add(priceField);
        newServicePanel.add(new JLabel("Pay-Cycle:"));      newServicePanel.add(billingBox);

        JPanel datesPanel = new JPanel(new GridLayout(0, 2, 6, 6));
        datesPanel.add(new JLabel("Start (YYYY-MM-DD):"));       datesPanel.add(startField);
        datesPanel.add(new JLabel("Renewal (YYYY-MM-DD):")); datesPanel.add(renewalField);
        datesPanel.add(new JLabel("Trial?"));                    datesPanel.add(trialBox);

        JPanel trialEndPanel = new JPanel(new GridLayout(0, 2, 6, 6));
        trialEndPanel.add(new JLabel("Trial-End (YYYY-MM-DD):")); trialEndPanel.add(trialEndField);

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.add(modePanel);
        form.add(existingPanel);
        form.add(newServicePanel);
        form.add(datesPanel);
        form.add(trialEndPanel);

        // own modal dialog for adding subscription
        JDialog dialog = new JDialog(this, "Add subscription for " + u.name, true);
        JButton okBtn     = new JButton("OK");
        JButton cancelBtn = new JButton("Cancel");
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(cancelBtn);
        buttonPanel.add(okBtn);

        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(form, BorderLayout.CENTER);
        dialog.getContentPane().add(buttonPanel, BorderLayout.SOUTH);

        // visibility
        Runnable syncMode = () -> {
            boolean isNew = useNew.isSelected();
            existingPanel.setVisible(!isNew);
            newServicePanel.setVisible(isNew);
            dialog.pack();
        };
        useExisting.addActionListener(e -> syncMode.run());
        useNew.addActionListener(e -> syncMode.run());
        trialBox.addActionListener(e -> {
            trialEndPanel.setVisible(trialBox.isSelected());
            dialog.pack();
        });

        // initial state
        existingPanel.setVisible(true);
        newServicePanel.setVisible(false);
        trialEndPanel.setVisible(false);

        cancelBtn.addActionListener(e -> dialog.dispose());

        // ok: validate input and try to save subscription
        okBtn.addActionListener(e -> {
            if (trySaveSubscription(dialog, u, useNew, tierBox, catBox,
                    serviceNameField, tierNameField, priceField, billingBox,
                    startField, renewalField, trialBox, trialEndField)) {
                dialog.dispose();
                loadSubscriptions();
                loadSpend();
                loadNotifications();
            }
        });

        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private boolean trySaveSubscription(
            Component parent, UserItem u, JRadioButton useNew,
            JComboBox<TierItem> tierBox, JComboBox<CategoryItem> catBox,
            JTextField serviceNameField, JTextField tierNameField,
            JTextField priceField, JComboBox<String> billingBox,
            JTextField startField, JTextField renewalField,
            JCheckBox trialBox, JTextField trialEndField) {

        // validate subscription fields
        boolean isTrial = trialBox.isSelected();
        Date startDate, renewalDate, trialEnd = null;
        try {
            startDate   = Date.valueOf(startField.getText().trim());
            renewalDate = Date.valueOf(renewalField.getText().trim());
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(parent, "Please enter date in format YYYY-MM-DD.");
            return false;
        }
        if (isTrial) {
            String t = trialEndField.getText().trim();
            if (t.isEmpty()) {
                JOptionPane.showMessageDialog(parent, "A trial subscription requires a trial end date.");
                return false;
            }
            try {
                trialEnd = Date.valueOf(t);
            } catch (IllegalArgumentException ex) {
                JOptionPane.showMessageDialog(parent, "Trial-End: Please enter date in format YYYY-MM-DD." );
                return false;
            }
        }

        try {
            conn.setAutoCommit(false);
            int tierId;

            if (useNew.isSelected()) {
                String serviceName = serviceNameField.getText().trim();
                String tierName    = tierNameField.getText().trim();
                String priceText   = priceField.getText().trim();
                CategoryItem cat   = (CategoryItem) catBox.getSelectedItem();

                // all fields have to be filled
                if (serviceName.isEmpty() || tierName.isEmpty()
                        || priceText.isEmpty() || cat == null) {
                    JOptionPane.showMessageDialog(parent,
                            "Please fill all fields for the new service "
                                    + "(Service-Name, Category, Tier-Name, Costs).");
                    conn.rollback(); return false;
                }
                BigDecimal price;
                try {
                    price = new BigDecimal(priceText.replace(',', '.'));
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(parent, "Costs have to be a number.");
                    conn.rollback(); return false;
                }
                if (price.signum() <= 0) {   // CHECK chk_price_pos: price > 0
                    JOptionPane.showMessageDialog(parent, "Costs have to be greater than 0.");
                    conn.rollback(); return false;
                }

                // find service or create it (UNIQUE(name))
                int serviceId = -1;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT service_id FROM service WHERE name = ?")) {
                    ps.setString(1, serviceName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) serviceId = rs.getInt(1);
                    }
                }
                if (serviceId == -1) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO service (name, category_id) VALUES (?, ?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, serviceName);
                        ps.setInt(2, cat.id);
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            keys.next(); serviceId = keys.getInt(1);
                        }
                    }
                }

                // find tier or create it (UNIQUE(service_id, tier_name))
                tierId = -1;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT tier_id FROM price_tier WHERE service_id = ? AND tier_name = ?")) {
                    ps.setInt(1, serviceId);
                    ps.setString(2, tierName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) tierId = rs.getInt(1);
                    }
                }
                if (tierId == -1) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO price_tier (service_id, tier_name, price, billing_period) " +
                                    "VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                        ps.setInt(1, serviceId);
                        ps.setString(2, tierName);
                        ps.setBigDecimal(3, price);
                        ps.setString(4, (String) billingBox.getSelectedItem());
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            keys.next(); tierId = keys.getInt(1);
                        }
                    }
                }
            } else {
                TierItem tier = (TierItem) tierBox.getSelectedItem();
                if (tier == null) {
                    JOptionPane.showMessageDialog(parent, "No tier selected.");
                    conn.rollback(); return false;
                }
                tierId = tier.id;
            }

            // create subscription
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO subscription " +
                            "(user_id, tier_id, start_date, renewal_date, is_trial, trial_end_date, active) " +
                            "VALUES (?, ?, ?, ?, ?, ?, TRUE)")) {
                ps.setInt(1, u.id);
                ps.setInt(2, tierId);
                ps.setDate(3, startDate);
                ps.setDate(4, renewalDate);
                ps.setBoolean(5, isTrial);
                if (trialEnd != null) ps.setDate(6, trialEnd);
                else                  ps.setNull(6, Types.DATE);
                ps.executeUpdate();
            }

            conn.commit();
            return true;
        } catch (SQLException ex) {
            try { conn.rollback(); } catch (SQLException ignore) {}
            JOptionPane.showMessageDialog(parent, "SQL error: " + ex.getMessage());
            return false;
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignore) {}
        }
    }

    private void loadUsers() {
        String sql = "SELECT user_id, display_name FROM app_user ORDER BY display_name";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            userBox.removeAllItems();
            while (rs.next()) {
                userBox.addItem(new UserItem(rs.getInt(1), rs.getString(2)));
            }
        } catch (SQLException ex) {
            showError(ex);
        }
    }

    private void loadSubscriptions() {
        UserItem u = (UserItem) userBox.getSelectedItem();
        if (u == null) return;
        String sql =
            "SELECT s.subscription_id, sv.name, pt.tier_name, pt.price, " +
            "       pt.billing_period, s.renewal_date, s.is_trial " +
            "FROM subscription s " +
            "JOIN price_tier pt ON s.tier_id = pt.tier_id " +
            "JOIN service     sv ON pt.service_id = sv.service_id " +
            "WHERE s.user_id = ? AND s.active = TRUE " +
            "ORDER BY s.renewal_date";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, u.id);
            try (ResultSet rs = ps.executeQuery()) {
                subModel.setRowCount(0);
                while (rs.next()) {
                    subModel.addRow(new Object[]{
                        rs.getInt(1), rs.getString(2), rs.getString(3),
                        rs.getBigDecimal(4), rs.getString(5),
                        rs.getDate(6), rs.getBoolean(7) ? "yes" : "no"});
                }
            }
        } catch (SQLException ex) {
            showError(ex);
        }
    }

    private void loadSpend() {
        UserItem u = (UserItem) userBox.getSelectedItem();
        if (u == null) return;
        try (CallableStatement cs =
                 conn.prepareCall("{CALL sp_monthly_spend(?, ?)}")) {
            cs.setInt(1, u.id);
            cs.registerOutParameter(2, Types.DECIMAL);
            cs.execute();
            spendLabel.setText("Monthly spend: " + cs.getBigDecimal(2) + " EUR");
        } catch (SQLException ex) {
            showError(ex);
        }
    }

    private void loadWaste() {
        UserItem u = (UserItem) userBox.getSelectedItem();
        if (u == null) return;
        String sql =
            "SELECT c.name AS category, sv1.name AS service_a, sv2.name AS service_b, " +
            "       LEAST(pt1.price, pt2.price) AS potential_saving " +
            "FROM subscription s1 " +
            "JOIN price_tier pt1 ON s1.tier_id = pt1.tier_id " +
            "JOIN service     sv1 ON pt1.service_id = sv1.service_id " +
            "JOIN subscription s2 ON s2.user_id = s1.user_id " +
            "JOIN price_tier pt2 ON s2.tier_id = pt2.tier_id " +
            "JOIN service     sv2 ON pt2.service_id = sv2.service_id " +
            "JOIN category    c   ON sv1.category_id = c.category_id " +
            "WHERE s1.user_id = ? AND s1.active AND s2.active " +
            "  AND sv1.category_id = sv2.category_id " +
            "  AND sv1.service_id < sv2.service_id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, u.id);
            try (ResultSet rs = ps.executeQuery()) {
                StringBuilder sb = new StringBuilder();
                while (rs.next()) {
                    sb.append(String.format(
                        "%s: %s overlaps with %s  ->  save up to %s EUR/month%n",
                        rs.getString("category"), rs.getString("service_a"),
                        rs.getString("service_b"),
                        rs.getBigDecimal("potential_saving")));
                }
                wasteArea.setText(sb.length() == 0
                    ? "No overlapping services found." : sb.toString());
            }
        } catch (SQLException ex) {
            showError(ex);
        }
    }

    private void loadNotifications() {
        String sql =
                "SELECT n.notification_id, n.message, n.created_at, n.is_read, " +
                        "       au.display_name, sv.name AS service_name " +
                        "FROM notification n " +
                        "JOIN subscription s  ON n.subscription_id = s.subscription_id " +
                        "JOIN app_user     au ON s.user_id = au.user_id " +
                        "JOIN price_tier   pt ON s.tier_id = pt.tier_id " +
                        "JOIN service      sv ON pt.service_id = sv.service_id " +
                        "ORDER BY n.created_at DESC";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            notifModel.clear();
            while (rs.next()) {
                notifModel.addElement(new NotifItem(
                        rs.getInt(1), rs.getString(2),
                        rs.getTimestamp(3), rs.getBoolean(4),
                        rs.getString(5), rs.getString(6)));
            }
        } catch (SQLException ex) {
            showError(ex);
        }
    }

    private void markNotificationRead(NotifItem n) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE notification SET is_read = TRUE WHERE notification_id = ?")) {
            ps.setInt(1, n.id);
            ps.executeUpdate();
            loadNotifications();
        } catch (SQLException ex) {
            showError(ex);
        }
    }

    private void cancelSelected(JTable table) {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Select a row first.");
            return;
        }
        int subId = (int) subModel.getValueAt(row, 0);
        try (CallableStatement cs =
                 conn.prepareCall("{CALL sp_cancel_subscription(?)}")) {
            cs.setInt(1, subId);
            cs.execute();
            loadSubscriptions();
            loadSpend();
            loadNotifications();
        } catch (SQLException ex) {
            showError(ex);
        }
    }

    private void openSettings() {
        UserTableModel model = new UserTableModel();
        reloadUserTable(model);

        JTable table = new JTable(model);
        table.setRowHeight(24);

        JDialog dialog = new JDialog(this, "Settings – Useradministration", true);

        JButton newBtn    = new JButton("New user");
        JButton deleteBtn = new JButton("Delete user");
        JButton saveBtn   = new JButton("Save changes");
        JButton closeBtn  = new JButton("Close");

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(newBtn);
        buttons.add(deleteBtn);
        buttons.add(saveBtn);
        buttons.add(closeBtn);

        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        dialog.getContentPane().add(buttons, BorderLayout.SOUTH);

        newBtn.addActionListener(e -> {
            if (createUserDialog(dialog)) reloadUserTable(model);
        });
        deleteBtn.addActionListener(e -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            int row = table.getSelectedRow();
            if (row < 0) {
                JOptionPane.showMessageDialog(dialog, "Please choose an user first.");
                return;
            }
            int userId  = (int) model.getValueAt(row, 0);
            String name = String.valueOf(model.getValueAt(row, 1));
            int ok = JOptionPane.showConfirmDialog(dialog,
                    "Should \"" + name + "\" really be deleted?\n" +
                            "All related subscriptions and notifications will be deleted.",
                    "Confirm deletion", JOptionPane.YES_NO_OPTION);
            if (ok == JOptionPane.YES_OPTION && deleteUser(dialog, userId))
                reloadUserTable(model);
        });
        saveBtn.addActionListener(e -> {
            if (table.isEditing()) table.getCellEditor().stopCellEditing();
            saveUsers(dialog, model);
        });
        closeBtn.addActionListener(e -> dialog.dispose());

        dialog.setSize(580, 360);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);

        loadUsers();
        loadNotifications();
    }

    private void reloadUserTable(UserTableModel model) {
        model.setRowCount(0);
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT user_id, display_name, email, notify_by_email " +
                             "FROM app_user ORDER BY display_name")) {
            while (rs.next()) {
                model.addRow(new Object[]{
                        rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)});
            }
        } catch (SQLException ex) { showError(ex); }
    }

    private boolean createUserDialog(Component parent) {
        JTextField nameField  = new JTextField();
        JTextField emailField = new JTextField();
        JCheckBox  notifyBox  = new JCheckBox("E-Mail Notifications", true);

        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Name:"));   form.add(nameField);
        form.add(new JLabel("E-Mail:")); form.add(emailField);
        form.add(new JLabel());          form.add(notifyBox);

        while (true) {
            int res = JOptionPane.showConfirmDialog(parent, form, "Create new user",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (res != JOptionPane.OK_OPTION) return false;

            String name  = nameField.getText().trim();
            String email = emailField.getText().trim();
            if (name.isEmpty() || email.isEmpty()) {
                JOptionPane.showMessageDialog(parent, "Please fill in both name and e-mail.");
                continue;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO app_user (email, display_name, created_at, notify_by_email) " +
                            "VALUES (?, ?, CURDATE(), ?)")) {
                ps.setString(1, email);
                ps.setString(2, name);
                ps.setBoolean(3, notifyBox.isSelected());
                ps.executeUpdate();
                return true;
            } catch (SQLException ex) {
                JOptionPane.showMessageDialog(parent, "Could not create:" + ex.getMessage());
            }
        }
    }

    private boolean deleteUser(Component parent, int userId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM app_user WHERE user_id = ?")) {
            ps.setInt(1, userId);
            ps.executeUpdate();
            return true;
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(parent, "Deletion failed: " + ex.getMessage());
            return false;
        }
    }

    private void saveUsers(Component parent, UserTableModel model) {
        for (int r = 0; r < model.getRowCount(); r++) {
            String name  = String.valueOf(model.getValueAt(r, 1)).trim();
            String email = String.valueOf(model.getValueAt(r, 2)).trim();
            if (name.isEmpty() || email.isEmpty()) {
                JOptionPane.showMessageDialog(parent,
                        "Row" + (r + 1) + ": Name and E-Mail should not be empty.");
                return;
            }
        }
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE app_user SET display_name = ?, email = ?, notify_by_email = ? " +
                            "WHERE user_id = ?")) {
                for (int r = 0; r < model.getRowCount(); r++) {
                    ps.setString(1, String.valueOf(model.getValueAt(r, 1)).trim());
                    ps.setString(2, String.valueOf(model.getValueAt(r, 2)).trim());
                    ps.setBoolean(3, Boolean.TRUE.equals(model.getValueAt(r, 3)));
                    ps.setInt(4, (int) model.getValueAt(r, 0));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
            JOptionPane.showMessageDialog(parent, "Changes saved.");
        } catch (SQLException ex) {
            try { conn.rollback(); } catch (SQLException ignore) {}
            JOptionPane.showMessageDialog(parent, "Saving failed:" + ex.getMessage());
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignore) {}
        }
    }

    private void showError(SQLException ex) {
        JOptionPane.showMessageDialog(this, "SQL error: " + ex.getMessage());
    }

    private static class UserItem {
        final int id; final String name;
        UserItem(int id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name; }
    }

    private static class TierItem {
        final int id; final String label;
        TierItem(int id, String label) { this.id = id; this.label = label; }
        @Override public String toString() { return label; }
    }

    private static class CategoryItem {
        final int id; final String name;
        CategoryItem(int id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name; }
    }

    private static class NotifItem {
        final int id; final String message; final boolean isRead;
        final Timestamp createdAt; final String userName; final String serviceName;
        NotifItem(int id, String message, Timestamp createdAt, boolean isRead,
                  String userName, String serviceName) {
            this.id = id; this.message = message;
            this.createdAt = createdAt; this.isRead = isRead;
            this.userName = userName; this.serviceName = serviceName;
        }
        @Override public String toString() {
            String ts = createdAt == null ? "" : createdAt.toString();
            if (ts.length() >= 16) ts = ts.substring(0, 16); // yyyy-MM-dd HH:mm
            return (isRead ? "    " : "● ")
                    + (ts.isEmpty() ? "" : "[" + ts + "] ")
                    + userName + " – " + serviceName + ": " + message;
        }
    }

    private static class UserTableModel extends DefaultTableModel {
        UserTableModel() {
            super(new String[]{"User ID", "Name", "E-Mail", "E-Mail-Benachrichtigung"}, 0);
        }
        @Override public boolean isCellEditable(int row, int col) {
            return col != 0;
        }
        @Override public Class<?> getColumnClass(int col) {
            if (col == 0) return Integer.class;
            if (col == 3) return Boolean.class;
            return String.class;
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SubOptApp().setVisible(true));
    }
}
