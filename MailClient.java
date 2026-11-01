import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

public class MailClient {
    static final int SMTP_PORT = 2525, POP3_PORT = 1100;

    // ======================= GIAO THỨC SMTP =======================
    static class SmtpClient {
        final Socket s;
        final BufferedReader in;
        final OutputStream out;

        SmtpClient(String host) throws IOException {
            s = new Socket();
            s.connect(new java.net.InetSocketAddress(host, SMTP_PORT), 10000);
            s.setSoTimeout(10000);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = s.getOutputStream();
            expect(read(), "220");
        }

        String read() throws IOException {
            while (true) {
                String l = in.readLine();
                if (l == null) throw new IOException("Server đóng kết nối");
                if (l.length() < 4 || l.charAt(3) == ' ') return l;
            }
        }

        static void expect(String resp, String... codes) throws IOException {
            for (String c : codes) if (resp.startsWith(c)) return;
            throw new IOException(resp);
        }

        void cmd(String line, String... ok) throws IOException {
            out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            expect(read(), ok);
        }

        void sendMail(String userEmail, String pw, List<String> rcpts, String raw) throws IOException {
            cmd("EHLO client", "250");
            String cred = "\0" + userEmail + "\0" + pw;
            cmd("AUTH PLAIN " + Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8)), "235");
            cmd("MAIL FROM:<" + userEmail + ">", "250");
            for (String r : rcpts) cmd("RCPT TO:<" + r + ">", "250");
            cmd("DATA", "354");
            StringBuilder sb = new StringBuilder();
            for (String l : raw.split("\r\n", -1)) sb.append(l.startsWith(".") ? "." + l : l).append("\r\n");
            sb.append(".\r\n");
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            expect(read(), "250");
            cmd("QUIT", "221");
            s.close();
        }
    }

    // ======================= GIAO THỨC POP3 =======================
    static class Pop3Client {
        final Socket s;
        final BufferedReader in;
        final OutputStream out;

        Pop3Client(String host) throws IOException {
            s = new Socket();
            s.connect(new java.net.InetSocketAddress(host, POP3_PORT), 10000);
            s.setSoTimeout(10000);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = s.getOutputStream();
            resp();
        }

        String line() throws IOException {
            String l = in.readLine();
            if (l == null) throw new IOException("Server đóng kết nối");
            return l;
        }

        String resp() throws IOException {
            String l = line();
            if (!l.startsWith("+OK")) throw new IOException(l.startsWith("-ERR ") ? l.substring(5) : l);
            return l;
        }

        String cmd(String c) throws IOException {
            out.write((c + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            return resp();
        }

        List<String> multiline() throws IOException {
            List<String> r = new ArrayList<>();
            String l;
            while (!(l = line()).equals(".")) r.add(l.startsWith("..") ? l.substring(1) : l);
            return r;
        }

        void register(String email, String pw) throws IOException {
            cmd("REG " + email + " " + Base64.getEncoder().encodeToString(pw.getBytes(StandardCharsets.UTF_8)));
        }

        void login(String email, String pw) throws IOException { cmd("USER " + email); cmd("PASS " + pw); }

        List<String[]> uidl() throws IOException {
            cmd("UIDL");
            List<String[]> r = new ArrayList<>();
            for (String l : multiline()) r.add(l.split(" "));
            return r;
        }

        String retr(String n) throws IOException { cmd("RETR " + n); return String.join("\r\n", multiline()); }

        void quit() throws IOException { try { cmd("QUIT"); } finally { s.close(); } }
    }

    // ======================= PHÂN TÍCH / TẠO THƯ =======================
    static String decWord(String t) {
        Matcher m = Pattern.compile("=\\?UTF-8\\?B\\?(.*?)\\?=").matcher(t);
        StringBuilder sb = new StringBuilder();
        while (m.find())
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Base64.getDecoder().decode(m.group(1)), StandardCharsets.UTF_8)));
        m.appendTail(sb);
        return sb.toString();
    }

    static String buildMail(String from, List<String> to, String subject, String body) {
        return "From: " + from + "\r\nTo: " + String.join(", ", to)
                + "\r\nSubject: " + subject 
                + "\r\nDate: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())
                + "\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8"
                + "\r\nContent-Transfer-Encoding: 8bit\r\n\r\n" + body;
    }

    record Row(String id, String sender, String recipient, String subject, String body, String date, long ts) { }

    // ======================= LƯU TRỮ BẰNG POSTGRESQL =======================
    static class LocalStore {
        LocalStore() throws IOException {
            try { Db.init(); } catch (SQLException e) { throw new IOException("Lỗi kết nối PostgreSQL: " + e.getMessage(), e); }
        }

        boolean has(String acc, String folder, String uidl) throws IOException {
            try (Connection c = Db.get();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM client_mails WHERE account = ? AND folder = ? AND uidl = ?")) {
                ps.setString(1, acc); ps.setString(2, folder); ps.setString(3, uidl);
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            } catch (SQLException e) { throw new IOException(e.getMessage(), e); }
        }

        void add(String acc, String folder, String uidl, String raw) throws IOException {
            try (Connection c = Db.get();
                 PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO client_mails(account, folder, uidl, raw) VALUES (?, ?, ?, ?) " +
                     "ON CONFLICT (account, folder, uidl) DO UPDATE SET raw = EXCLUDED.raw")) {
                ps.setString(1, acc); ps.setString(2, folder); ps.setString(3, uidl); ps.setString(4, raw);
                ps.executeUpdate();
            } catch (SQLException e) { throw new IOException(e.getMessage(), e); }
        }

        List<Row> list(String acc, String folder) throws IOException {
            List<Row> r = new ArrayList<>();
            try (Connection c = Db.get();
                 PreparedStatement ps = c.prepareStatement(
                     "SELECT uidl, raw FROM client_mails WHERE account = ? AND folder = ?")) {
                ps.setString(1, acc); ps.setString(2, folder);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) r.add(parse(rs.getString(1), rs.getString(2)));
                }
            } catch (SQLException e) { throw new IOException(e.getMessage(), e); }
            r.sort((x, y) -> Long.compare(y.ts(), x.ts()));
            return r;
        }

        void delete(String acc, String folder, String id) throws IOException {
            try (Connection c = Db.get();
                 PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM client_mails WHERE account = ? AND folder = ? AND uidl = ?")) {
                ps.setString(1, acc); ps.setString(2, folder); ps.setString(3, id);
                ps.executeUpdate();
            } catch (SQLException e) { throw new IOException(e.getMessage(), e); }
        }

        static Row parse(String id, String raw) {
            int i = raw.indexOf("\r\n\r\n");
            String head = i < 0 ? raw : raw.substring(0, i), body = i < 0 ? "" : raw.substring(i + 4);
            Map<String, String> h = new HashMap<>();
            String last = null;
            for (String l : head.split("\r\n")) {
                if ((l.startsWith(" ") || l.startsWith("\t")) && last != null) h.put(last, h.get(last) + l.trim());
                else if (l.contains(":")) { last = l.substring(0, l.indexOf(':')).toLowerCase(); h.put(last, l.substring(l.indexOf(':') + 1).trim()); }
            }
            if ("base64".equalsIgnoreCase(h.getOrDefault("content-transfer-encoding", "")))
                try { body = new String(Base64.getMimeDecoder().decode(body.trim()), StandardCharsets.UTF_8); } catch (Exception ignored) { }
            String date = h.getOrDefault("date", ""), disp = date;
            long ts = 0;
            try {
                ZonedDateTime z = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME);
                ts = z.toEpochSecond();
                disp = z.withZoneSameInstant(java.time.ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
            } catch (Exception ignored) { }
            return new Row(id, decWord(h.getOrDefault("from", "")), decWord(h.getOrDefault("to", "")),
                    decWord(h.getOrDefault("subject", "(Không có chủ đề)")), body, disp, ts);
        }
    }

    // ======================= GIAO DIỆN =======================
    static class App extends JFrame {
        LocalStore db;
        String host = "127.0.0.1", user, pw, folder = "inbox";
        JPasswordField pw1, pw2;
        JButton eye;
        JLabel status = new JLabel(" "), title = new JLabel("Hộp thư đến");
        DefaultTableModel model;
        JTable table;
        JTextArea view;
        List<Row> rows = new ArrayList<>();

        App() throws Exception {
            super("Mini Mail (Gmail Integrated)");
            db = new LocalStore();
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setSize(1000, 620);
            setLocationRelativeTo(null);
            loginScreen(false);
        }

        void err(Component p, String msg) { JOptionPane.showMessageDialog(p, msg, "Lỗi", JOptionPane.ERROR_MESSAGE); }

        void loginScreen(boolean register) {
            JPanel f = new JPanel(new GridBagLayout());
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(5, 5, 5, 5); g.fill = GridBagConstraints.HORIZONTAL; g.gridx = 0; g.gridy = 0; g.gridwidth = 3;
            JLabel t = new JLabel("📧 Mini Mail", SwingConstants.CENTER);
            t.setFont(new Font("SansSerif", Font.BOLD, 26));
            f.add(t, g);
            g.gridy++;
            f.add(new JLabel(register ? "Tạo tài khoản bằng Email thật" : "Đăng nhập", SwingConstants.CENTER), g);

            JTextField userF = new JTextField(22);
            pw1 = new JPasswordField(22); pw2 = new JPasswordField(22);
            eye = new JButton("👁");
            eye.addActionListener(e -> {
                boolean hidden = pw1.getEchoChar() != 0;
                char c = hidden ? (char) 0 : '•';
                pw1.setEchoChar(c); pw2.setEchoChar(c);
                eye.setText(hidden ? "🙈" : "👁");
            });
            g.gridwidth = 1;

            Object[][] r = {{"Email (VD: a@gmail.com)", userF}, {"Mật khẩu / App Password", pw1}, {"Nhập lại MK", pw2}};
            for (int i = 0; i < (register ? 3 : 2); i++) {
                g.gridy++; g.gridx = 0; f.add(new JLabel((String) r[i][0]), g);
                g.gridx = 1; f.add((Component) r[i][1], g);
                if (i == 1) { g.gridx = 2; f.add(eye, g); }
            }

            JButton go = new JButton(register ? "Đăng ký" : "Đăng nhập");
            JButton sw = new JButton(register ? "← Quay lại đăng nhập" : "Chưa có tài khoản? Đăng ký");
            g.gridx = 0; g.gridwidth = 3; g.gridy++; f.add(go, g);
            g.gridy++; f.add(sw, g);
            sw.addActionListener(e -> loginScreen(!register));
            go.addActionListener(e -> {
                String u = userF.getText().trim().toLowerCase(), p = new String(pw1.getPassword());
                if (u.isEmpty() || p.isEmpty()) { err(this, "Vui lòng nhập đầy đủ email và mật khẩu"); return; }
                if (!u.contains("@")) u += "@gmail.com"; // Tự động thêm @gmail.com nếu quên gõ
                if (register && !p.equals(new String(pw2.getPassword()))) { err(this, "Mật khẩu nhập lại không khớp"); return; }
                go.setEnabled(false);
                final String finalEmail = u;
                new Thread(() -> {
                    try {
                        Pop3Client c = new Pop3Client(host);
                        if (register) {
                            c.register(finalEmail, p); c.quit();
                            SwingUtilities.invokeLater(() -> {
                                JOptionPane.showMessageDialog(this, "Đã tạo tài khoản: " + finalEmail);
                                loginScreen(false);
                            });
                        } else {
                            c.login(finalEmail, p); c.quit();
                            user = finalEmail; pw = p;
                            SwingUtilities.invokeLater(() -> { mainScreen(); reload(); });
                        }
                    } catch (Exception ex) {
                        SwingUtilities.invokeLater(() -> { go.setEnabled(true); err(this, ex.getMessage()); });
                    }
                }).start();
            });
            getRootPane().setDefaultButton(go);
            setContentPane(f); revalidate(); repaint();
        }

        void mainScreen() {
            JPanel root = new JPanel(new BorderLayout());
            JPanel top = new JPanel(new BorderLayout());
            top.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            JLabel logo = new JLabel("📧 Mini Mail");
            logo.setFont(new Font("SansSerif", Font.BOLD, 18));
            JButton out = new JButton("Đăng xuất");
            out.addActionListener(e -> { user = pw = null; loginScreen(false); });
            JPanel tr = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            tr.add(status); tr.add(new JLabel(user)); tr.add(out);
            top.add(logo, BorderLayout.WEST); top.add(tr, BorderLayout.EAST);
            root.add(top, BorderLayout.NORTH);

            JPanel side = new JPanel(new GridLayout(0, 1, 0, 6));
            side.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            JButton compose = new JButton("✏  Soạn thư"), inbox = new JButton("📥  Hộp thư đến"), sent = new JButton("📤  Đã gửi");
            compose.setFont(compose.getFont().deriveFont(Font.BOLD, 14f));
            compose.addActionListener(e -> compose("", "", "", 0)); 
            inbox.addActionListener(e -> switchTo("inbox"));
            sent.addActionListener(e -> switchTo("sent"));
            side.add(compose); side.add(inbox); side.add(sent);
            JPanel sideWrap = new JPanel(new BorderLayout());
            sideWrap.add(side, BorderLayout.NORTH);
            root.add(sideWrap, BorderLayout.WEST);

            JPanel right = new JPanel(new BorderLayout());
            JPanel bar = new JPanel(new BorderLayout());
            bar.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 10));
            title.setFont(new Font("SansSerif", Font.BOLD, 15));
            
            JButton reload = new JButton("⟳ Tải lại"), del = new JButton("🗑 Xóa"), reply = new JButton("↩ Trả lời");
            reload.addActionListener(e -> reload());
            del.addActionListener(e -> deleteSelected());
            reply.addActionListener(e -> replySelected());
            
            JPanel br = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            br.add(reply); br.add(reload); br.add(del);
            bar.add(title, BorderLayout.WEST); bar.add(br, BorderLayout.EAST);
            right.add(bar, BorderLayout.NORTH);

            model = new DefaultTableModel(new Object[]{"Người gửi", "Chủ đề", "Thời gian"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            table = new JTable(model);
            table.setRowHeight(26);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.getColumnModel().getColumn(0).setPreferredWidth(220);
            table.getColumnModel().getColumn(1).setPreferredWidth(380);
            table.getColumnModel().getColumn(2).setPreferredWidth(130);
            table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showMail(); });
            view = new JTextArea();
            view.setEditable(false); view.setLineWrap(true); view.setWrapStyleWord(true);
            view.setMargin(new Insets(10, 10, 10, 10)); view.setFont(new Font("SansSerif", Font.PLAIN, 14));
            JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(table), new JScrollPane(view));
            sp.setResizeWeight(0.4);
            right.add(sp, BorderLayout.CENTER);
            root.add(right, BorderLayout.CENTER);
            setContentPane(root);
            switchTo("inbox");
            revalidate(); repaint();
        }

        void switchTo(String f) {
            folder = f;
            boolean in = f.equals("inbox");
            title.setText(in ? "Hộp thư đến" : "Thư đã gửi");
            model.setColumnIdentifiers(new Object[]{in ? "Người gửi" : "Người nhận", "Chủ đề", "Thời gian"});
            refresh();
        }

        void refresh() {
            try {
                rows = db.list(user, folder);
            } catch (IOException e) { err(this, e.getMessage()); return; }
            model.setRowCount(0);
            for (Row r : rows) model.addRow(new Object[]{folder.equals("inbox") ? r.sender() : r.recipient(), r.subject(), r.date()});
            view.setText("");
        }

        void showMail() {
            int i = table.getSelectedRow();
            if (i < 0 || i >= rows.size()) return;
            Row r = rows.get(i);
            String inlineHeader = "Vào " + r.date() + ", " + r.sender() + " đã viết:\n";
            view.setText("Chủ đề: " + r.subject() + "\n"
                    + "Từ: " + r.sender() + "\n"
                    + "Đến: " + r.recipient() + "\n"
                    + "Ngày: " + r.date() + "\n"
                    + "=========================================================\n\n" 
                    + inlineHeader 
                    + r.body());
            view.setCaretPosition(0);
        }

        void deleteSelected() {
            int i = table.getSelectedRow();
            if (i < 0 || JOptionPane.showConfirmDialog(this, "Xóa thư này khỏi máy?", "Xóa", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            try { db.delete(user, folder, rows.get(i).id()); refresh(); } catch (IOException e) { err(this, e.getMessage()); }
        }

        void replySelected() {
            int i = table.getSelectedRow();
            if (i < 0) {
                JOptionPane.showMessageDialog(this, "Vui lòng chọn một thư trong danh sách để trả lời!");
                return;
            }
            Row r = rows.get(i);
            String toAddress = folder.equals("inbox") ? r.sender() : r.recipient();
            if (toAddress.contains("<") && toAddress.contains(">")) {
                toAddress = toAddress.substring(toAddress.indexOf("<") + 1, toAddress.indexOf(">"));
            }
            
            String oldAuthor = r.sender();
            if (oldAuthor.contains("<") && oldAuthor.contains(">")) {
                oldAuthor = oldAuthor.substring(oldAuthor.indexOf("<") + 1, oldAuthor.indexOf(">"));
            }
            
            String sub = r.subject().startsWith("Re: ") ? r.subject() : "Re: " + r.subject();
            String body = "\n\n" 
                        + "---------------------------------------------------------\n"
                        + "Vào " + r.date() + ", " + oldAuthor + " đã viết:\n" 
                        + r.body();
            
            compose(toAddress, sub, body, 0);
        }

        void reload() {
            status.setText("Đang tải thư...");
            final String u = user, p = pw;
            new Thread(() -> {
                try {
                    Pop3Client c = new Pop3Client(host);
                    c.login(u, p);
                    int added = 0;
                    for (String[] x : c.uidl())
                        if (!db.has(u, "inbox", x[1])) { db.add(u, "inbox", x[1], c.retr(x[0])); added++; }
                    c.quit();
                    final int n = added;
                    SwingUtilities.invokeLater(() -> { status.setText(n > 0 ? "Có " + n + " thư mới" : "Đã cập nhật"); refresh(); });
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> status.setText("Lỗi: " + e.getMessage()));
                }
            }).start();
        }

        void compose(String defTo, String defSub, String defBody, int caretPos) {
            JDialog d = new JDialog(this, "Thư mới", false);
            d.setSize(560, 460); d.setLocationRelativeTo(this);
            JTextField to = new JTextField(defTo), sub = new JTextField(defSub);
            JTextArea txt = new JTextArea(defBody);
            
            txt.setLineWrap(true); txt.setWrapStyleWord(true);
            JPanel h = new JPanel(new GridLayout(2, 2, 6, 6));
            h.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
            h.add(new JLabel("Đến (Email thật, cách bằng dấu phẩy):")); h.add(to);
            h.add(new JLabel("Chủ đề:")); h.add(sub);
            JButton send = new JButton("Gửi");
            d.add(h, BorderLayout.NORTH);
            d.add(new JScrollPane(txt), BorderLayout.CENTER);
            JPanel bp = new JPanel();
            bp.add(send);
            d.add(bp, BorderLayout.SOUTH);
            
            d.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowOpened(java.awt.event.WindowEvent e) {
                    txt.requestFocusInWindow();
                    txt.setCaretPosition(caretPos);
                }
            });

            send.addActionListener(e -> {
                List<String> rc = new ArrayList<>();
                for (String x : to.getText().replace(';', ',').split(","))
                    if (!x.isBlank()) rc.add(x.contains("@") ? x.trim() : x.trim() + "@gmail.com");
                if (rc.isEmpty()) { err(d, "Nhập địa chỉ người nhận"); return; }
                String raw = buildMail(user, rc, sub.getText().isBlank() ? "(Không có chủ đề)" : sub.getText(), txt.getText());
                send.setEnabled(false); send.setText("Đang gửi...");
                final String u = user, p = pw;
                new Thread(() -> {
                    try {
                        new SmtpClient(host).sendMail(u, p, rc, raw);
                        //db.add(u, "sent", UUID.randomUUID().toString(), raw);
                        SwingUtilities.invokeLater(() -> {
                            d.dispose(); status.setText("Đã gửi thư (Lưu DB & Gmail)");
                            if (folder.equals("sent")) refresh();
                        });
                    } catch (Exception ex) {
                        SwingUtilities.invokeLater(() -> { send.setEnabled(true); send.setText("Gửi"); err(d, "Gửi thất bại: " + ex.getMessage()); });
                    }
                }).start();
            });
            d.setVisible(true);
        }
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeLater(() -> {
            try { new App().setVisible(true); }
            catch (Exception e) { JOptionPane.showMessageDialog(null, "Không mở được database: " + e.getMessage()); }
        });
    }
}