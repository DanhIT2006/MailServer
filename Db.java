import java.sql.*;

/** Kết nối PostgreSQL dùng chung cho MailServer và MailClient. */
public class Db {
    // Có thể đổi bằng biến môi trường: DB_URL, DB_USER, DB_PASS
    static final String URL  = env("DB_URL",  "jdbc:postgresql://localhost:5432/mailapp");
    static final String USER = env("DB_USER", "postgres");
    static final String PASS = env("DB_PASS", "postgres");

    static String env(String k, String def) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? def : v;
    }

    static Connection get() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    /** Tạo bảng nếu chưa có. */
    static void init() throws SQLException {
        try (Connection c = get(); Statement st = c.createStatement()) {
            // Phía server: tài khoản + hộp thư
            st.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    id         BIGSERIAL PRIMARY KEY,
                    email      TEXT NOT NULL UNIQUE,
                    password   TEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT now()
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS mails (
                    id          BIGSERIAL PRIMARY KEY,
                    owner_email TEXT NOT NULL,
                    raw         TEXT NOT NULL,
                    created_at  TIMESTAMP NOT NULL DEFAULT now()
                )""");
            st.execute("CREATE INDEX IF NOT EXISTS idx_mails_owner ON mails(owner_email)");

            // Phía client: bản sao thư đã tải về (thay cho thư mục mail_data)
            st.execute("""
                CREATE TABLE IF NOT EXISTS client_mails (
                    account TEXT NOT NULL,
                    folder  TEXT NOT NULL,
                    uidl    TEXT NOT NULL,
                    raw     TEXT NOT NULL,
                    PRIMARY KEY (account, folder, uidl)
                )""");
        }
    }
}