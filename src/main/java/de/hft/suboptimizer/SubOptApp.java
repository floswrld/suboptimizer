// =====================================================================
//  Subscription & Fixed-Cost Optimizer  -  Java Swing + JDBC client
//  Database Systems II  -  Pre-Exam Project (Part 1, no AI)
//
//  Compile:  javac -cp .:mysql-connector-j-8.x.jar SubOptApp.java
//  Run:      java  -cp .:mysql-connector-j-8.x.jar SubOptApp
// =====================================================================
package de.hft.suboptimizer;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.*;

/**
 * Small GUI that connects to the MySQL "subopt" database via JDBC and lets
 * the user view subscriptions, compute monthly spend, detect overlapping
 * (wasteful) services and cancel a subscription transactionally.
 */
public class SubOptApp extends JFrame {

    // --- connection parameters (adjust to your environment) ---------
    private static final String URL  =
        "jdbc:mysql://localhost:3306/subopt?serverTimezone=UTC";
    private static final String USER = "root";
    private static final String PASS = "";

    private Connection conn;
    private final JComboBox<UserItem> userBox = new JComboBox<>();
    private final DefaultTableModel subModel =
        new DefaultTableModel(new String[]{"Sub ID","Service","Tier",
                "Price","Period","Renewal","Trial?"}, 0);
    private final JLabel spendLabel = new JLabel("Monthly spend: -");
    private final JTextArea wasteArea = new JTextArea(6, 40);

    public SubOptApp() {
        super("Subscription & Fixed-Cost Optimizer");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(820, 560);
        setLocationRelativeTo(null);
        buildUi();
        connect();
        loadUsers();
    }

    /** Build the Swing layout. */
    private void buildUi() {
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        top.add(new JLabel("User:"));
        top.add(userBox);
        JButton refresh = new JButton("Show subscriptions");
        JButton waste   = new JButton("Find wasted overlap");
        JButton cancel  = new JButton("Cancel selected");
        top.add(refresh);
        top.add(waste);
        top.add(cancel);

        JTable table = new JTable(subModel);

        wasteArea.setEditable(false);
        wasteArea.setBorder(BorderFactory.createTitledBorder("Overlap / waste"));

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(spendLabel, BorderLayout.NORTH);
        bottom.add(new JScrollPane(wasteArea), BorderLayout.CENTER);

        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(bottom, BorderLayout.SOUTH);

        // event handlers
        refresh.addActionListener(e -> { loadSubscriptions(); loadSpend(); });
        waste.addActionListener(e -> loadWaste());
        cancel.addActionListener(e -> cancelSelected(table));
    }

    /** Open the JDBC connection. */
    private void connect() {
        try {
            conn = DriverManager.getConnection(URL, USER, PASS);
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(this,
                "Connection failed: " + ex.getMessage());
        }
    }

    /** Fill the user drop-down. */
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

    /**
     * Query 1 (JOIN): list every active subscription of the selected user
     * together with its service, category and price tier.
     */
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

    /** Call the stored procedure sp_monthly_spend for the selected user. */
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

    /**
     * Query 2 (self-JOIN): detect overlapping services in the same category,
     * i.e. the "waste" the user could save by cancelling one of them.
     */
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

    /** Cancel the selected subscription via the transactional procedure. */
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
        } catch (SQLException ex) {
            showError(ex);
        }
    }

    private void showError(SQLException ex) {
        JOptionPane.showMessageDialog(this, "SQL error: " + ex.getMessage());
    }

    /** Helper holding a user id together with a printable name. */
    private static class UserItem {
        final int id; final String name;
        UserItem(int id, String name) { this.id = id; this.name = name; }
        @Override public String toString() { return name; }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new SubOptApp().setVisible(true));
    }
}
