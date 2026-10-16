-- =====================================================================
-- DEMONSTRATION DATA  (development profile only; never loaded in production)
--
-- Every product, supplier and customer below is labelled "[DEMO]". The quantities, prices, sales and
-- expenses are invented so the dashboards and reports have something to show. They are NOT the
-- shop's real stock. To start clean, run the app with the "prod" profile (or a fresh database).
-- No phone numbers are generated, so nothing can be sent to a real person by mistake.
-- =====================================================================

-- helpers living only for this migration's session
CREATE FUNCTION pg_temp.demo_fys(d date) RETURNS text AS $$
    SELECT CASE WHEN extract(month FROM d) >= 4
        THEN lpad((extract(year FROM d)::int % 100)::text, 2, '0') || lpad(((extract(year FROM d)::int + 1) % 100)::text, 2, '0')
        ELSE lpad(((extract(year FROM d)::int - 1) % 100)::text, 2, '0') || lpad((extract(year FROM d)::int % 100)::text, 2, '0') END
$$ LANGUAGE sql;

CREATE FUNCTION pg_temp.demo_next(seq text, period text) RETURNS bigint AS $$
    INSERT INTO number_sequences (seq_name, period_key, last_value) VALUES (seq, period, 1)
    ON CONFLICT (seq_name, period_key) DO UPDATE SET last_value = number_sequences.last_value + 1
    RETURNING last_value
$$ LANGUAGE sql;

DO $demo$
DECLARE
    spec      RECORD;
    it        RECORD;
    sz        text;
    col       text;
    p_id      uuid;
    v_id      uuid;
    qty       integer;
    counter   integer := 0;
    sup_id    uuid;
    pur_id    uuid;
    pur_total numeric(14,2);
    inv_id    uuid;
    inv_no    text;
    s_time    timestamptz;
    fys       text;
    cust_id   uuid;
    cust_name text;
    n_inv     integer;
    n_items   integer;
    q         integer;
    line      numeric(14,2);
    ld        numeric(14,2);
    net       numeric(14,2);
    sub       numeric(14,2);
    ldt       numeric(14,2);
    tot       numeric(14,2);
    cst       numeric(14,2);
    pay_roll  numeric;
    half      numeric(14,2);
    d         integer;
    k         integer;
    pk        integer;
    p_time    timestamptz;
    avail     integer;
    dmg       integer;
BEGIN
    PERFORM setseed(0.42);

    -- ---------- customers (no phone numbers on purpose) ----------
    INSERT INTO customers (customer_code, name, notes, created_by) VALUES
        ('C90001', '[DEMO] Customer One',   'Demonstration customer', 'demo'),
        ('C90002', '[DEMO] Customer Two',   'Demonstration customer', 'demo'),
        ('C90003', '[DEMO] Customer Three', 'Demonstration customer', 'demo'),
        ('C90004', '[DEMO] Customer Four',  'Demonstration customer', 'demo'),
        ('C90005', '[DEMO] Customer Five',  'Demonstration customer', 'demo');

    -- ---------- products, variants, opening stock ----------
    FOR spec IN SELECT * FROM (VALUES
        (1, 'Men''s Cotton Shirt', 'Shirts',      'Cotton',  'Checked',  380.00,  799.00,  999.00, ARRAY['M','L','XL'],            ARRAY['Blue','White']),
        (2, 'Formal Trousers',     'Trousers',    'Poly-viscose', 'Plain', 450.00,  999.00, 1299.00, ARRAY['30','32','34','36'],      ARRAY['Black','Grey','Navy']),
        (3, 'Slim Fit Jeans',      'Jeans',       'Denim',   'Slim',     520.00, 1199.00, 1499.00, ARRAY['30','32','34'],          ARRAY['Blue','Black']),
        (4, 'Round Neck T-Shirt',  'T-Shirts',    'Cotton',  'Plain',    160.00,  399.00,  499.00, ARRAY['S','M','L','XL'],        ARRAY['Black','White','Red','Navy']),
        (5, 'Silk Saree',          'Sarees',      'Silk',    'Zari border', 1600.00, 2999.00, 3499.00, ARRAY['Free Size'],         ARRAY['Maroon','Green','Pink']),
        (6, 'Cotton Kurti',        'Kurtis',      'Cotton',  'Printed',  280.00,  699.00,  899.00, ARRAY['S','M','L','XL'],        ARRAY['Pink','Yellow','Blue']),
        (7, 'Kids Frock',          'Kids Wear',   'Cotton',  'Floral',   220.00,  549.00,  699.00, ARRAY['2-4 Yr','4-6 Yr','6-8 Yr'], ARRAY['Pink','Yellow']),
        (8, 'Leather Belt',        'Accessories', 'Leather', 'Classic',  120.00,  349.00,  449.00, ARRAY['Free Size'],             ARRAY['Black','Brown'])
    ) AS t(n, pname, cat, fabric, design, cost, price, mrp, sizes, colours)
    LOOP
        INSERT INTO products (product_code, name, sku, category_id, fabric, design, purchase_cost, selling_price, mrp,
                              tax_rate, min_stock, created_by)
        VALUES ('DEMO-P' || lpad(spec.n::text, 2, '0'), '[DEMO] ' || spec.pname, 'DEMO-P' || lpad(spec.n::text, 2, '0'),
                (SELECT id FROM categories WHERE name = spec.cat), spec.fabric, spec.design, spec.cost, spec.price,
                spec.mrp, 0, 5, 'demo')
        RETURNING id INTO p_id;

        FOREACH sz IN ARRAY spec.sizes LOOP
            FOREACH col IN ARRAY spec.colours LOOP
                INSERT INTO product_variants (product_id, sku, size_id, colour_id, created_by)
                VALUES (p_id,
                        'DEMO-P' || lpad(spec.n::text, 2, '0') || '-' || upper(substr(col, 1, 3)) || '-' ||
                        regexp_replace(upper(sz), '[^A-Z0-9]', '', 'g'),
                        (SELECT id FROM sizes WHERE name = sz), (SELECT id FROM colours WHERE name = col), 'demo')
                RETURNING id INTO v_id;

                counter := counter + 1;
                -- a few deliberately low / out-of-stock variants so alerts have something to show
                IF counter % 11 = 0 THEN
                    qty := 0;
                ELSIF counter % 7 = 0 THEN
                    qty := 2 + (counter % 3);
                ELSE
                    qty := 8 + floor(random() * 18)::int;
                END IF;

                INSERT INTO stock_balances (variant_id, available_qty, damaged_qty, avg_cost) VALUES (v_id, qty, 0, spec.cost);
                IF qty > 0 THEN
                    INSERT INTO stock_movements (variant_id, movement_type, available_delta, damaged_delta, unit_cost,
                                                 available_after, damaged_after, ref_type, ref_no, reason, created_at, created_by)
                    VALUES (v_id, 'OPENING', qty, 0, spec.cost, qty, 0, 'ADJUSTMENT', 'DEMO-OPEN', 'Demo opening stock',
                            now() - interval '45 days', 'demo');
                END IF;
            END LOOP;
        END LOOP;
    END LOOP;

    -- ---------- a supplier and two received purchases ----------
    INSERT INTO suppliers (supplier_code, name, address, opening_balance, notes, created_by)
    VALUES ('S90001', '[DEMO] Wholesale Traders', 'Demo address, not real', 0, 'Demonstration supplier', 'demo')
    RETURNING id INTO sup_id;

    -- a draft purchase, to show the draft state
    INSERT INTO purchase_invoices (purchase_no, supplier_id, supplier_invoice_no, purchase_date, status, subtotal, total, created_by)
    VALUES ('PUR/' || pg_temp.demo_fys(current_date) || '/' ||
            lpad(pg_temp.demo_next('PURCHASE', pg_temp.demo_fys(current_date))::text, 5, '0'),
            sup_id, 'DEMO-1003', current_date, 'DRAFT', 0, 0, 'demo');

    -- ---------- 30 days of sales ----------
    FOR d IN REVERSE 29..0 LOOP
        pk := CASE d WHEN 20 THEN 1 WHEN 8 THEN 2 ELSE 0 END;
        IF pk > 0 THEN
            p_time := ((current_date - d)::timestamp + interval '9 hours') AT TIME ZONE 'Asia/Kolkata';
            fys := pg_temp.demo_fys(current_date - d);
            INSERT INTO purchase_invoices (purchase_no, supplier_id, supplier_invoice_no, purchase_date, status, subtotal,
                                           total, amount_paid, received_at, received_by, created_by)
            VALUES ('PUR/' || fys || '/' || lpad(pg_temp.demo_next('PURCHASE', fys)::text, 5, '0'), sup_id,
                    'DEMO-' || (1000 + pk), current_date - d, 'RECEIVED', 0, 0, 0, p_time, 'demo', 'demo')
            RETURNING id INTO pur_id;

            FOR it IN SELECT v.id AS vid, p.purchase_cost AS pc
                        FROM product_variants v JOIN products p ON p.id = v.product_id
                       WHERE p.product_code = CASE pk WHEN 1 THEN 'DEMO-P01' ELSE 'DEMO-P04' END
                          OR p.product_code = CASE pk WHEN 1 THEN 'DEMO-P03' ELSE 'DEMO-P06' END
            LOOP
                INSERT INTO purchase_items (purchase_id, variant_id, quantity, unit_price, line_total, landed_unit_cost, created_by)
                VALUES (pur_id, it.vid, 10, it.pc, it.pc * 10, it.pc, 'demo');
                UPDATE stock_balances
                   SET available_qty = available_qty + 10,
                       avg_cost = round((available_qty * avg_cost + 10 * it.pc) / (available_qty + 10), 4)
                 WHERE variant_id = it.vid;
                INSERT INTO stock_movements (variant_id, movement_type, available_delta, damaged_delta, unit_cost,
                                             available_after, damaged_after, ref_type, ref_id, ref_no, created_at, created_by)
                SELECT it.vid, 'PURCHASE_RECEIPT', 10, 0, it.pc, sb.available_qty, sb.damaged_qty, 'PURCHASE', pur_id,
                       (SELECT purchase_no FROM purchase_invoices WHERE id = pur_id), p_time, 'demo'
                  FROM stock_balances sb WHERE sb.variant_id = it.vid;
            END LOOP;

            SELECT sum(line_total) INTO pur_total FROM purchase_items WHERE purchase_id = pur_id;
            UPDATE purchase_invoices
               SET subtotal = pur_total, total = pur_total, amount_paid = round(pur_total * CASE pk WHEN 1 THEN 1.0 ELSE 0.5 END, 0)
             WHERE id = pur_id;
            INSERT INTO supplier_payments (payment_no, supplier_id, purchase_id, amount, method, reference, paid_on, created_by)
            VALUES ('SPAY/' || fys || '/' || lpad(pg_temp.demo_next('SUPPLIER_PAYMENT', fys)::text, 5, '0'), sup_id, pur_id,
                    round(pur_total * CASE pk WHEN 1 THEN 1.0 ELSE 0.5 END, 0),
                    CASE pk WHEN 1 THEN 'BANK_TRANSFER' ELSE 'CASH' END, NULL, current_date - d, 'demo');
        END IF;


        n_inv := 3 + floor(random() * 6)::int;
        FOR k IN 1..n_inv LOOP
            PERFORM 1 FROM stock_balances sb JOIN product_variants v ON v.id = sb.variant_id
                      JOIN products p ON p.id = v.product_id
                     WHERE sb.available_qty > 0 AND p.product_code LIKE 'DEMO-%';
            EXIT WHEN NOT FOUND;

            s_time := ((current_date - d)::timestamp + (10 + random() * 10) * interval '1 hour') AT TIME ZONE 'Asia/Kolkata';
            IF s_time > now() THEN
                s_time := now() - interval '5 minutes';
            END IF;
            fys := pg_temp.demo_fys((s_time AT TIME ZONE 'Asia/Kolkata')::date);
            inv_no := 'INV/' || fys || '/' || lpad(pg_temp.demo_next('INVOICE', fys)::text, 5, '0');
            inv_id := gen_random_uuid();

            cust_id := NULL;
            cust_name := NULL;
            IF random() < 0.3 THEN
                SELECT id, name INTO cust_id, cust_name FROM customers WHERE customer_code LIKE 'C9%' ORDER BY random() LIMIT 1;
            END IF;

            INSERT INTO sales_invoices (id, invoice_no, sale_time, customer_id, customer_name, status, prices_include_tax,
                                        subtotal, taxable_total, total, amount_paid, balance_due, total_cost,
                                        client_request_id, created_at, created_by)
            VALUES (inv_id, inv_no, s_time, cust_id, cust_name, 'COMPLETED', true, 0, 0, 0, 0, 0, 0,
                    gen_random_uuid(), s_time, 'demo');

            n_items := 1 + floor(random() * 3)::int;
            sub := 0; ldt := 0; tot := 0; cst := 0;
            FOR it IN SELECT v.id AS vid, p.name AS pname, v.sku AS vsku, sz2.name AS szn, cl.name AS cln,
                             p.selling_price AS pr, sb.available_qty AS av, sb.avg_cost AS ac, p.hsn_code AS hsn
                        FROM stock_balances sb
                        JOIN product_variants v ON v.id = sb.variant_id
                        JOIN products p ON p.id = v.product_id
                        LEFT JOIN sizes sz2 ON sz2.id = v.size_id
                        LEFT JOIN colours cl ON cl.id = v.colour_id
                       WHERE sb.available_qty > 0 AND p.product_code LIKE 'DEMO-%'
                       ORDER BY random() LIMIT n_items
            LOOP
                q := least(it.av, 1 + (random() < 0.2)::int);
                line := it.pr * q;
                ld := CASE WHEN random() < 0.12 THEN round(line * 0.10) ELSE 0 END;
                net := line - ld;
                INSERT INTO sales_items (invoice_id, variant_id, product_name, sku, size_name, colour_name, hsn_code,
                                         quantity, unit_price, line_discount, taxable_value, tax_rate, tax_amount,
                                         line_total, unit_cost, created_at, created_by)
                VALUES (inv_id, it.vid, it.pname, it.vsku, it.szn, it.cln, it.hsn, q, it.pr, ld, net, 0, 0, net, it.ac,
                        s_time, 'demo');
                UPDATE stock_balances SET available_qty = available_qty - q WHERE variant_id = it.vid;
                SELECT available_qty, damaged_qty INTO avail, dmg FROM stock_balances WHERE variant_id = it.vid;
                INSERT INTO stock_movements (variant_id, movement_type, available_delta, damaged_delta, unit_cost,
                                             available_after, damaged_after, ref_type, ref_id, ref_no, created_at, created_by)
                VALUES (it.vid, 'SALE', -q, 0, it.ac, avail, dmg, 'SALE', inv_id, inv_no, s_time, 'demo');
                sub := sub + line; ldt := ldt + ld; tot := tot + net; cst := cst + it.ac * q;
            END LOOP;

            UPDATE sales_invoices
               SET subtotal = sub, line_discount_total = ldt, taxable_total = tot, total = tot, amount_paid = tot,
                   total_cost = cst
             WHERE id = inv_id;

            pay_roll := random();
            IF pay_roll < 0.15 AND tot >= 2 THEN
                half := round(tot / 2, 0);
                INSERT INTO sales_payments (invoice_id, payment_kind, method, amount, received_at, created_at, created_by)
                VALUES (inv_id, 'SALE', 'CASH', half, s_time, s_time, 'demo'),
                       (inv_id, 'SALE', 'UPI', tot - half, s_time, s_time, 'demo');
            ELSE
                INSERT INTO sales_payments (invoice_id, payment_kind, method, amount, received_at, created_at, created_by)
                VALUES (inv_id, 'SALE', CASE WHEN pay_roll < 0.65 THEN 'CASH' WHEN pay_roll < 0.92 THEN 'UPI' ELSE 'CARD' END,
                        tot, s_time, s_time, 'demo');
            END IF;
        END LOOP;
    END LOOP;

    -- ---------- one cancelled invoice (stock restored, money refunded) ----------
    SELECT id, invoice_no, sale_time INTO inv_id, inv_no, s_time
      FROM sales_invoices WHERE status = 'COMPLETED' AND created_by = 'demo'
       AND sale_time < now() - interval '3 days' ORDER BY sale_time DESC LIMIT 1;
    FOR it IN SELECT variant_id AS vid, quantity AS qty, unit_cost AS uc FROM sales_items WHERE invoice_id = inv_id LOOP
        UPDATE stock_balances SET available_qty = available_qty + it.qty WHERE variant_id = it.vid;
        SELECT available_qty, damaged_qty INTO avail, dmg FROM stock_balances WHERE variant_id = it.vid;
        INSERT INTO stock_movements (variant_id, movement_type, available_delta, damaged_delta, unit_cost, available_after,
                                     damaged_after, ref_type, ref_id, ref_no, reason, created_at, created_by)
        VALUES (it.vid, 'SALE_CANCEL', it.qty, 0, it.uc, avail, dmg, 'SALE', inv_id, inv_no, 'Demo: customer changed mind',
                now(), 'demo');
    END LOOP;
    UPDATE sales_invoices SET status = 'CANCELLED', cancelled_at = now(), cancelled_by = 'demo',
           cancel_reason = 'Demo: customer changed mind' WHERE id = inv_id;
    INSERT INTO refunds (refund_no, refund_type, invoice_id, amount, method, refunded_at, created_by)
    SELECT 'REF/' || pg_temp.demo_fys(current_date) || '/' ||
           lpad(pg_temp.demo_next('REFUND', pg_temp.demo_fys(current_date))::text, 5, '0'),
           'CANCELLATION', inv_id, amount, method, now(), 'demo'
      FROM sales_payments WHERE invoice_id = inv_id;

    -- ---------- one partial return (one unit goes back on the shelf) ----------
    SELECT i.id, i.invoice_no INTO inv_id, inv_no
      FROM sales_invoices i WHERE i.status = 'COMPLETED' AND i.created_by = 'demo'
       AND i.sale_time < now() - interval '1 day' AND i.sale_time > now() - interval '3 days'
       AND EXISTS (SELECT 1 FROM sales_items x WHERE x.invoice_id = i.id AND x.line_discount = 0)
     ORDER BY i.sale_time DESC LIMIT 1;
    IF inv_id IS NOT NULL THEN
        SELECT si.id, si.variant_id, si.line_total, si.quantity, si.unit_cost INTO it
          FROM sales_items si WHERE si.invoice_id = inv_id AND si.line_discount = 0 LIMIT 1;
        line := round(it.line_total / it.quantity, 2);
        INSERT INTO sales_returns (id, return_no, invoice_id, return_time, return_type, reason, taxable_total, total,
                                   refund_total, client_request_id, created_by)
        VALUES (gen_random_uuid(), 'RET/' || pg_temp.demo_fys(current_date) || '/' ||
                lpad(pg_temp.demo_next('RETURN', pg_temp.demo_fys(current_date))::text, 5, '0'), inv_id,
                now(), 'RETURN', 'Demo: size did not fit', line, line, line, gen_random_uuid(), 'demo')
        RETURNING id INTO v_id;
        INSERT INTO sales_return_items (return_id, sales_item_id, variant_id, quantity, taxable_value, tax_amount, amount,
                                        unit_cost, item_condition, created_by)
        VALUES (v_id, it.id, it.variant_id, 1, line, 0, line, it.unit_cost, 'RESELLABLE', 'demo');
        UPDATE sales_items SET returned_qty = 1, returned_taxable = line, returned_total = line WHERE id = it.id;
        UPDATE stock_balances SET available_qty = available_qty + 1 WHERE variant_id = it.variant_id;
        SELECT available_qty, damaged_qty INTO avail, dmg FROM stock_balances WHERE variant_id = it.variant_id;
        INSERT INTO stock_movements (variant_id, movement_type, available_delta, damaged_delta, unit_cost, available_after,
                                     damaged_after, ref_type, ref_id, ref_no, reason, created_at, created_by)
        VALUES (it.variant_id, 'SALE_RETURN', 1, 0, it.unit_cost, avail, dmg, 'RETURN', v_id, inv_no,
                'Demo: size did not fit', now(), 'demo');
        INSERT INTO refunds (refund_no, refund_type, invoice_id, return_id, amount, method, refunded_at, created_by)
        VALUES ('REF/' || pg_temp.demo_fys(current_date) || '/' ||
                lpad(pg_temp.demo_next('REFUND', pg_temp.demo_fys(current_date))::text, 5, '0'),
                'RETURN', inv_id, v_id, line, 'CASH', now(), 'demo');
    END IF;

    -- ---------- expenses this month ----------
    INSERT INTO expenses (category_id, amount, expense_date, payment_method, description, created_by)
    SELECT c.id, e.amount, date_trunc('month', current_date)::date + e.day_offset, e.method, '[DEMO] ' || e.descr, 'demo'
      FROM (VALUES ('Rent', 12000.00, 0, 'BANK_TRANSFER', 'Shop rent'),
                   ('Electricity', 2350.00, 4, 'UPI', 'Electricity bill'),
                   ('Packaging', 640.00, 6, 'CASH', 'Carry bags'),
                   ('Transport', 900.00, 8, 'CASH', 'Goods transport'),
                   ('Tea and refreshments', 380.00, 9, 'CASH', 'Tea for staff')) AS e(cat, amount, day_offset, method, descr)
      JOIN expense_categories c ON c.name = e.cat
     WHERE date_trunc('month', current_date)::date + e.day_offset <= current_date;
END
$demo$;
