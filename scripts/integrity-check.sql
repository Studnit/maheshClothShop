-- Integrity check: every query must return ZERO rows. Safe to run any time (read-only).
--   psql "$DB_URL" -f scripts/integrity-check.sql
\pset format aligned
\echo '1) Stock balance must equal the sum of its ledger movements'
SELECT sb.variant_id, sb.available_qty, COALESCE(m.a,0) AS ledger_available, sb.damaged_qty, COALESCE(m.d,0) AS ledger_damaged
  FROM stock_balances sb
  LEFT JOIN (SELECT variant_id, sum(available_delta) a, sum(damaged_delta) d FROM stock_movements GROUP BY variant_id) m
         ON m.variant_id = sb.variant_id
 WHERE sb.available_qty <> COALESCE(m.a,0) OR sb.damaged_qty <> COALESCE(m.d,0);

\echo '2) Last ledger line of each variant must show the current balance'
SELECT * FROM (
  SELECT DISTINCT ON (m.variant_id) m.variant_id, m.available_after, sb.available_qty, m.damaged_after, sb.damaged_qty
    FROM stock_movements m JOIN stock_balances sb ON sb.variant_id = m.variant_id
   ORDER BY m.variant_id, m.seq DESC) x
 WHERE available_after <> available_qty OR damaged_after <> damaged_qty;

\echo '3) Sales invoice total must equal its lines (+ round-off)'
SELECT i.invoice_no, i.total, sum(si.line_total) + i.round_off AS lines_total
  FROM sales_invoices i JOIN sales_items si ON si.invoice_id = i.id
 GROUP BY i.id HAVING i.total <> sum(si.line_total) + i.round_off;

\echo '4) Paid + balance must equal total on completed invoices; paid must equal the payments recorded'
SELECT i.invoice_no, i.total, i.amount_paid, i.balance_due, COALESCE(p.paid,0) AS payments
  FROM sales_invoices i LEFT JOIN (SELECT invoice_id, sum(amount) paid FROM sales_payments GROUP BY invoice_id) p ON p.invoice_id = i.id
 WHERE i.status = 'COMPLETED'
   AND (i.amount_paid + i.balance_due <> i.total OR i.amount_paid <> COALESCE(p.paid,0));

\echo '5) Returned quantity on a sales line must equal the sum of its return lines'
SELECT si.id, si.returned_qty, COALESCE(r.q,0) AS return_lines
  FROM sales_items si LEFT JOIN (SELECT sales_item_id, sum(quantity) q FROM sales_return_items GROUP BY sales_item_id) r ON r.sales_item_id = si.id
 WHERE si.returned_qty <> COALESCE(r.q,0);

\echo '6) Purchase total must equal its lines + additional costs (received invoices)'
SELECT pi.purchase_no, pi.total, sum(pit.line_total) + pi.additional_costs AS lines_total
  FROM purchase_invoices pi JOIN purchase_items pit ON pit.purchase_id = pi.id
 WHERE pi.status = 'RECEIVED' GROUP BY pi.id HAVING pi.total <> sum(pit.line_total) + pi.additional_costs;

\echo '7) Purchase amount_paid must equal supplier payments linked to it'
SELECT pi.purchase_no, pi.amount_paid, COALESCE(sp.paid,0) AS payments
  FROM purchase_invoices pi LEFT JOIN (SELECT purchase_id, sum(amount) paid FROM supplier_payments WHERE purchase_id IS NOT NULL GROUP BY purchase_id) sp ON sp.purchase_id = pi.id
 WHERE pi.status = 'RECEIVED' AND pi.amount_paid <> COALESCE(sp.paid,0);

\echo '8) Cancelled invoices must have been refunded what was paid'
SELECT i.invoice_no, i.amount_paid, COALESCE(r.refunded,0) AS refunded
  FROM sales_invoices i LEFT JOIN (SELECT invoice_id, sum(amount) refunded FROM refunds WHERE refund_type = 'CANCELLATION' GROUP BY invoice_id) r ON r.invoice_id = i.id
 WHERE i.status = 'CANCELLED' AND i.amount_paid <> COALESCE(r.refunded,0);

\echo '9) Duplicate document numbers (must never happen)'
SELECT invoice_no, count(*) FROM sales_invoices GROUP BY invoice_no HAVING count(*) > 1;
\echo 'Integrity check finished: every section above should list no rows.'
