import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.*;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class MailServer {
    static final int SMTP_PORT = 2525, POP3_PORT = 1100;
    static final String DEFAULT_GMAIL = "thanhdanh952006@gmail.com";
    static final String DEFAULT_APP_PASSWORD = "bgtraujqaaqgbdoa";

    // Trả về {email, mật khẩu} hoặc null (khớp đúng email hoặc trùng phần trước dấu @)
    static String[] findUser(String emailInput) throws SQLException {
        String target = emailInput.toLowerCase().trim();
        String local = target.split("@")[0];
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT email, password FROM users WHERE email = ? OR split_part(email, '@', 1) = ? " +
                 "ORDER BY (email = ?) DESC LIMIT 1")) {
            ps.setString(1, target);
            ps.setString(2, local);
            ps.setString(3, target);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : null;
            }
        }
    }

    static boolean userExists(String email) throws SQLException {
        return findUser(email) != null;
    }

    static void addMail(String email, String raw) throws SQLException {
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement("INSERT INTO mails(owner_email, raw) VALUES (?, ?)")) {
            ps.setString(1, email.toLowerCase().trim());
            ps.setString(2, raw);
            ps.executeUpdate();
        }
    }

    // Mỗi phần tử: {id (String), nội dung thư (String)}
    static List<Object[]> listMails(String email) throws SQLException {
        List<Object[]> r = new ArrayList<>();
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement("SELECT id, raw FROM mails WHERE owner_email = ? ORDER BY id")) {
            ps.setString(1, email.toLowerCase().trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) r.add(new Object[]{String.valueOf(rs.getLong(1)), rs.getString(2)});
            }
        }
        return r;
    }

    static void deleteMail(String email, String id) throws SQLException {
        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement("DELETE FROM mails WHERE id = ? AND owner_email = ?")) {
            ps.setLong(1, Long.parseLong(id));
            ps.setString(2, email.toLowerCase().trim());
            ps.executeUpdate();
        }
    }

    static synchronized String register(String email, String pw) throws Exception {
        email = email.toLowerCase().trim();
        if (!email.contains("@")) return "Vui lòng nhập đúng định dạng email thật (VD: ten@gmail.com)";
        if (pw.length() < 4) return "Mật khẩu tối thiểu 4 ký tự";
        if (userExists(email)) return "Tài khoản đã tồn tại";

        try (Connection c = Db.get();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO users(email, password) VALUES (?, ?) ON CONFLICT (email) DO NOTHING")) {
            ps.setString(1, email);
            ps.setString(2, pw);
            if (ps.executeUpdate() == 0) return "Tài khoản đã tồn tại";
        }
        return null;
    }

    static boolean check(String email, String pw) {
        try {
            String[] u = findUser(email);
            return u != null && u[1].equals(pw);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------- HÀM CHUYỂN TIẾP THƯ LÊN GMAIL THẬT (SMTP SSL) ----------------
    static void forwardToGmail(String senderEmail, String senderPw, String rcptEmail, String rawMail) {
        new Thread(() -> {
            // Nếu người gửi dùng Gmail và nhập mật khẩu ứng dụng 16 ký tự (bỏ dấu cách) thì dùng luôn tài khoản đó,
            // ngược lại sẽ dùng tài khoản Gmail trung gian mặc định ở trên
            String cleanPw = senderPw.replace(" ", "");
            String authUser = (senderEmail.endsWith("@gmail.com") && cleanPw.length() == 16) ? senderEmail : DEFAULT_GMAIL;
            String authPass = (senderEmail.endsWith("@gmail.com") && cleanPw.length() == 16) ? cleanPw : DEFAULT_APP_PASSWORD.replace(" ", "");

            if (authUser.equals("email_cua_ban@gmail.com")) {
                System.err.println("[GMAIL RELAY] Chưa cấu hình DEFAULT_GMAIL / DEFAULT_APP_PASSWORD trong MailServer!");
                return;
            }

            try (SSLSocket sock = (SSLSocket) SSLSocketFactory.getDefault().createSocket("smtp.gmail.com", 465)) {
                sock.setSoTimeout(15000);
                BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));

                readSmtpResp(in);
                sendSmtpCmd(out, in, "EHLO localhost");

                String cred = "\0" + authUser + "\0" + authPass;
                String b64 = Base64.getEncoder().encodeToString(cred.getBytes(StandardCharsets.UTF_8));
                String authResp = sendSmtpCmd(out, in, "AUTH PLAIN " + b64);
                if (!authResp.startsWith("235")) {
                    System.err.println("[GMAIL RELAY] Lỗi xác thực Gmail: " + authResp);
                    return;
                }

                sendSmtpCmd(out, in, "MAIL FROM:<" + authUser + ">");
                sendSmtpCmd(out, in, "RCPT TO:<" + rcptEmail + ">");
                sendSmtpCmd(out, in, "DATA");

                for (String l : rawMail.split("\r\n", -1)) {
                    out.write((l.startsWith(".") ? "." + l : l) + "\r\n");
                }
                out.write(".\r\n");
                out.flush();
                readSmtpResp(in);

                sendSmtpCmd(out, in, "QUIT");
                System.out.println("[GMAIL RELAY] Đã gửi thư thật thành công tới Gmail: " + rcptEmail);
            } catch (Exception e) {
                System.err.println("[GMAIL RELAY] Lỗi gửi tới " + rcptEmail + ": " + e.getMessage());
            }
        }).start();
    }

    static String readSmtpResp(BufferedReader in) throws IOException {
        String line;
        while ((line = in.readLine()) != null) {
            if (line.length() < 4 || line.charAt(3) == ' ') return line;
        }
        throw new IOException("Mất kết nối tới máy chủ Gmail");
    }

    static String sendSmtpCmd(BufferedWriter out, BufferedReader in, String cmd) throws IOException {
        out.write(cmd + "\r\n");
        out.flush();
        return readSmtpResp(in);
    }

    static class Conn {
        final Socket s;
        final BufferedReader in;
        final BufferedWriter out;

        Conn(Socket s) throws IOException {
            this.s = s;
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        }

        void send(String t) throws IOException { out.write(t + "\r\n"); out.flush(); }
        String line() throws IOException { return in.readLine(); }
    }

    // ---------------- SMTP NỘI BỘ ----------------
    static void smtp(Socket sock) {
        try (sock) {
            Conn c = new Conn(sock);
            String user = null, pass = null, sender = null;
            Set<String> rcpts = new LinkedHashSet<>();
            c.send("220 smtp.local SMTP ready");
            String ln;
            while ((ln = c.line()) != null) {
                String[] p = ln.split(" ", 2);
                String cmd = p[0].toUpperCase(), arg = p.length > 1 ? p[1] : "";
                switch (cmd) {
                    case "HELO", "EHLO" -> { c.send("250-smtp.local"); c.send("250 AUTH PLAIN"); }
                    case "AUTH" -> {
                        String[] t = arg.trim().split("\\s+");
                        String tok = t.length > 1 ? t[1] : null;
                        if (tok == null) { c.send("334 "); tok = c.line(); }
                        try {
                            String[] x = new String(Base64.getDecoder().decode(tok), StandardCharsets.UTF_8).split("\0", -1);
                            if (x.length == 3 && check(x[1], x[2])) {
                                user = findUser(x[1])[0];
                                pass = x[2];
                                c.send("235 Authentication successful");
                            } else c.send("535 Authentication failed");
                        } catch (Exception e) { c.send("501 Bad AUTH"); }
                    }
                    case "MAIL" -> {
                        Matcher m = Pattern.compile("<(.*?)>").matcher(arg);
                        if (user == null) c.send("530 Authentication required");
                        else if (!m.find()) c.send("553 Sender not allowed");
                        else { sender = m.group(1).toLowerCase(); rcpts.clear(); c.send("250 OK"); }
                    }
                    case "RCPT" -> {
                        Matcher m = Pattern.compile("<(.*?)>").matcher(arg);
                        String rcptEmail = m.find() ? m.group(1).toLowerCase().trim() : "";
                        if (sender == null) c.send("503 Need MAIL first");
                        else if (!rcptEmail.contains("@")) c.send("550 Invalid email address");
                        else { rcpts.add(rcptEmail); c.send("250 OK"); }
                    }
                    case "DATA" -> {
                        if (rcpts.isEmpty()) { c.send("503 Need RCPT first"); break; }
                        c.send("354 End data with <CRLF>.<CRLF>");
                        List<String> data = new ArrayList<>();
                        String l;
                        while ((l = c.line()) != null && !l.equals(".")) data.add(l.startsWith("..") ? l.substring(1) : l);
                        String raw = String.join("\r\n", data);
                        for (String r : rcpts) {
                            // 1. Lưu vào PostgreSQL
                            addMail(r, raw);
                            // 2. Đồng thời gửi ra Gmail thật nếu là địa chỉ email thật
                            if (!r.endsWith("@mail.local")) {
                                forwardToGmail(user, pass, r, raw);
                            }
                        }
                        sender = null; rcpts.clear();
                        c.send("250 Message accepted");
                    }
                    case "RSET" -> { sender = null; rcpts.clear(); c.send("250 OK"); }
                    case "NOOP" -> c.send("250 OK");
                    case "QUIT" -> { c.send("221 Bye"); return; }
                    default -> c.send("502 Command not implemented");
                }
            }
        } catch (Exception ignored) { }
    }

    // ---------------- POP3 NỘI BỘ ----------------
    static void pop3(Socket sock) {
        try (sock) {
            Conn c = new Conn(sock);
            String user = null;
            boolean authed = false;
            List<Object[]> msgs = new ArrayList<>();
            Set<Integer> dele = new HashSet<>();
            c.send("+OK POP3 ready");
            String ln;
            while ((ln = c.line()) != null) {
                String[] p = ln.split(" ", 2);
                String cmd = p[0].toUpperCase(), arg = p.length > 1 ? p[1] : "";
                if (cmd.equals("REG")) {
                    String[] a = arg.split(" ", 2);
                    try {
                        String err = register(a[0], new String(Base64.getDecoder().decode(a[1]), StandardCharsets.UTF_8));
                        c.send(err == null ? "+OK Registered" : "-ERR " + err);
                    } catch (Exception e) { c.send("-ERR Dữ liệu không hợp lệ"); }
                } else if (cmd.equals("USER")) { user = arg.trim().toLowerCase(); c.send("+OK"); }
                else if (cmd.equals("PASS") && user != null) {
                    if (check(user, arg)) {
                        authed = true;
                        user = findUser(user)[0];
                        msgs = listMails(user);
                        c.send("+OK " + msgs.size() + " messages");
                    } else c.send("-ERR Invalid credentials");
                } else if (cmd.equals("QUIT")) {
                    for (int i : dele) deleteMail(user, (String) msgs.get(i)[0]);
                    c.send("+OK Bye"); return;
                } else if (!authed) c.send("-ERR Authenticate first");
                else if (cmd.equals("STAT")) {
                    long size = 0;
                    for (Object[] m : msgs) size += ((String) m[1]).getBytes(StandardCharsets.UTF_8).length;
                    c.send("+OK " + msgs.size() + " " + size);
                } else if (cmd.equals("LIST") || cmd.equals("UIDL")) {
                    boolean list = cmd.equals("LIST");
                    c.send("+OK");
                    for (int i = 0; i < msgs.size(); i++) {
                        if (dele.contains(i)) continue;
                        c.send((i + 1) + " " + (list ? ((String) msgs.get(i)[1]).getBytes(StandardCharsets.UTF_8).length : msgs.get(i)[0]));
                    }
                    c.send(".");
                } else if (cmd.equals("RETR") || cmd.equals("DELE")) {
                    int n;
                    try { n = Integer.parseInt(arg.trim()); } catch (NumberFormatException e) { n = 0; }
                    if (n < 1 || n > msgs.size()) { c.send("-ERR No such message"); continue; }
                    if (cmd.equals("DELE")) { dele.add(n - 1); c.send("+OK Marked"); continue; }
                    String raw = (String) msgs.get(n - 1)[1];
                    c.send("+OK " + raw.getBytes(StandardCharsets.UTF_8).length + " octets");
                    for (String l : raw.split("\r\n", -1)) c.send(l.startsWith(".") ? "." + l : l);
                    c.send(".");
                } else if (cmd.equals("NOOP")) c.send("+OK");
                else c.send("-ERR Unknown command");
            }
        } catch (Exception ignored) { }
    }

    static void listen(int port, java.util.function.Consumer<Socket> handler) {
        new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(port)) {
                while (true) {
                    Socket s = ss.accept();
                    new Thread(() -> handler.accept(s)).start();
                }
            } catch (IOException e) { System.err.println("Lỗi cổng " + port + ": " + e.getMessage()); }
        }).start();
    }

    public static void main(String[] args) throws Exception {
        Db.init();
        listen(SMTP_PORT, MailServer::smtp);
        listen(POP3_PORT, MailServer::pop3);
        System.out.println("SMTP: " + SMTP_PORT + " | POP3: " + POP3_PORT + " | Đã bật chế độ Gmail Relay!");
    }
}