package com.maheshclothes.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Small helpers for the hand-written read queries. */
public final class SqlUtil {

    private SqlUtil() {
    }

    /** Wraps user text for LIKE, escaping the wildcard characters so "50%" searches for a literal 50%. */
    public static String like(String s) {
        String e = s.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + e + "%";
    }

    public static List<String> tokens(String q) {
        List<String> out = new ArrayList<>();
        if (q != null) {
            for (String t : q.trim().split("\\s+")) {
                if (!t.isBlank()) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    /** The PostgreSQL driver cannot bind java.time.Instant directly; OffsetDateTime it can. */
    public static OffsetDateTime ts(Instant i) {
        return i == null ? null : OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    public static UUID uuid(ResultSet rs, int col) throws SQLException {
        return rs.getObject(col, UUID.class);
    }

    public static Instant instant(ResultSet rs, int col) throws SQLException {
        java.sql.Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    public static Integer intOrNull(ResultSet rs, int col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }
}
