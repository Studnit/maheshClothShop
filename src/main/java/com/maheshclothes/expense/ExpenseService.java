package com.maheshclothes.expense;

import com.maheshclothes.common.AuditService;
import com.maheshclothes.common.BusinessException;
import com.maheshclothes.common.Money;
import com.maheshclothes.common.NotFoundException;
import com.maheshclothes.common.PageResult;
import com.maheshclothes.common.ShopClock;
import com.maheshclothes.common.SqlUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Shop running costs (rent, electricity, wages...). They reduce net profit on the reports. */
@Service
@RequiredArgsConstructor
public class ExpenseService {

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ShopClock clock;

    public record ExpenseInput(UUID categoryId, BigDecimal amount, LocalDate date, String method, String description,
                               UUID receiptFileId) {
    }

    public record CategoryRow(UUID id, String name, boolean active) {
    }

    public record ExpenseRow(UUID id, LocalDate date, String category, BigDecimal amount, String method,
                             String description, UUID receiptFileId, String createdBy) {
    }

    public record CategoryTotal(String category, BigDecimal total) {
    }

    public List<CategoryRow> categories(boolean includeInactive) {
        return jdbc.query("select id, name, active from expense_categories "
                        + (includeInactive ? "" : "where active ") + "order by sort_order, lower(name)",
                (rs, n) -> new CategoryRow(SqlUtil.uuid(rs, 1), rs.getString(2), rs.getBoolean(3)));
    }

    @Transactional
    public UUID addCategory(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("expense.error.category");
        }
        Integer dup = jdbc.queryForObject("select count(*) from expense_categories where lower(name) = lower(?)",
                Integer.class, name.trim());
        if (dup != null && dup > 0) {
            throw new BusinessException("expense.error.category.duplicate");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("insert into expense_categories (id, name, created_by) values (?, ?, ?)", id, name.trim(),
                com.maheshclothes.security.CurrentUser.username());
        return id;
    }

    @Transactional
    public UUID save(UUID id, ExpenseInput in) {
        BigDecimal amt = Money.scale(in.amount());
        if (amt.signum() <= 0) {
            throw new BusinessException("expense.error.amount");
        }
        if (in.categoryId() == null) {
            throw new BusinessException("expense.error.category");
        }
        if (in.date() == null || in.date().isAfter(clock.today())) {
            throw new BusinessException("expense.error.date");
        }
        if (in.method() == null || in.method().isBlank()) {
            throw new BusinessException("payment.error.method");
        }
        String desc = in.description() == null || in.description().isBlank() ? null : in.description().trim();
        String user = com.maheshclothes.security.CurrentUser.username();
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.update("insert into expenses (id, category_id, amount, expense_date, payment_method, description, "
                    + "receipt_file_id, created_by) values (?,?,?,?,?,?,?,?)",
                    id, in.categoryId(), amt, in.date(), in.method(), desc, in.receiptFileId(), user);
            audit.log("EXPENSE_CREATED", "expense", id, amt + " " + desc);
        } else {
            int n = jdbc.update("update expenses set category_id = ?, amount = ?, expense_date = ?, payment_method = ?, "
                            + "description = ?, receipt_file_id = coalesce(?, receipt_file_id), updated_at = now(), updated_by = ? "
                            + "where id = ?",
                    in.categoryId(), amt, in.date(), in.method(), desc, in.receiptFileId(), user, id);
            if (n == 0) {
                throw new NotFoundException("Expense");
            }
            audit.log("EXPENSE_UPDATED", "expense", id, amt + " " + desc);
        }
        return id;
    }

    @Transactional
    public void delete(UUID id) {
        int n = jdbc.update("delete from expenses where id = ?", id);
        if (n == 0) {
            throw new NotFoundException("Expense");
        }
        audit.log("EXPENSE_DELETED", "expense", id, null);
    }

    public ExpenseRow get(UUID id) {
        return jdbc.query(SELECT + " where e.id = ?", ExpenseService::map, id).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Expense"));
    }

    private static final String SELECT = "select e.id, e.expense_date, c.name, e.amount, e.payment_method, e.description, "
            + "e.receipt_file_id, e.created_by from expenses e join expense_categories c on c.id = e.category_id";

    private static ExpenseRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ExpenseRow(SqlUtil.uuid(rs, 1), rs.getDate(2).toLocalDate(), rs.getString(3), rs.getBigDecimal(4),
                rs.getString(5), rs.getString(6), SqlUtil.uuid(rs, 7), rs.getString(8));
    }

    public PageResult<ExpenseRow> list(LocalDate from, LocalDate to, UUID categoryId, String q, int page, int size) {
        StringBuilder where = new StringBuilder(" where e.expense_date >= ? and e.expense_date <= ?");
        List<Object> args = new ArrayList<>(List.of(from, to));
        if (categoryId != null) {
            where.append(" and e.category_id = ?");
            args.add(categoryId);
        }
        for (String t : SqlUtil.tokens(q)) {
            where.append(" and (lower(coalesce(e.description,'')) like ? or lower(c.name) like ?)");
            args.add(SqlUtil.like(t));
            args.add(SqlUtil.like(t));
        }
        Long total = jdbc.queryForObject("select count(*) from expenses e join expense_categories c on c.id = e.category_id"
                + where, Long.class, args.toArray());
        List<Object> pa = new ArrayList<>(args);
        pa.add(size);
        pa.add(page * size);
        List<ExpenseRow> rows = jdbc.query(SELECT + where + " order by e.expense_date desc, e.created_at desc limit ? offset ?",
                ExpenseService::map, pa.toArray());
        return new PageResult<>(rows, page, size, total == null ? 0 : total);
    }

    public BigDecimal total(LocalDate from, LocalDate to) {
        BigDecimal v = jdbc.queryForObject("select coalesce(sum(amount),0) from expenses where expense_date between ? and ?",
                BigDecimal.class, from, to);
        return v == null ? Money.ZERO : v;
    }

    public List<CategoryTotal> byCategory(LocalDate from, LocalDate to) {
        return jdbc.query("select c.name, sum(e.amount) from expenses e join expense_categories c on c.id = e.category_id "
                        + "where e.expense_date between ? and ? group by c.name order by sum(e.amount) desc",
                (rs, n) -> new CategoryTotal(rs.getString(1), rs.getBigDecimal(2)), from, to);
    }
}
