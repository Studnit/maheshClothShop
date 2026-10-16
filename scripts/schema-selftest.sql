-- Schema self-test: every statement marked EXPECT FAIL must raise an error. Run with psql -v ON_ERROR_STOP=0.
\set QUIET on
BEGIN;
INSERT INTO products (product_code,name,sku,category_id,selling_price)
  SELECT 'P-T1','Test shirt','T-SHIRT-1',id,500 FROM categories LIMIT 1;
INSERT INTO product_variants (product_id, sku, size_id, colour_id)
  SELECT p.id,'T-SHIRT-1-M-BLU',(SELECT id FROM sizes WHERE name='M'),(SELECT id FROM colours WHERE name='Blue') FROM products p WHERE product_code='P-T1';
INSERT INTO stock_balances (variant_id, available_qty) SELECT id,5 FROM product_variants WHERE sku='T-SHIRT-1-M-BLU';
SAVEPOINT a;
\echo 'EXPECT FAIL 1: negative stock'
UPDATE stock_balances SET available_qty = available_qty - 6;
ROLLBACK TO a;
\echo 'EXPECT FAIL 2: duplicate size/colour combo for the same product'
INSERT INTO product_variants (product_id, sku, size_id, colour_id)
  SELECT p.id,'T-SHIRT-1-M-BLU-2',(SELECT id FROM sizes WHERE name='M'),(SELECT id FROM colours WHERE name='Blue') FROM products p WHERE product_code='P-T1';
ROLLBACK TO a;
\echo 'EXPECT FAIL 3: duplicate SKU (case-insensitive)'
INSERT INTO products (product_code,name,sku,category_id,selling_price)
  SELECT 'P-T2','Other','t-shirt-1',id,100 FROM categories LIMIT 1;
ROLLBACK TO a;
\echo 'EXPECT FAIL 4: stock ledger is append-only'
INSERT INTO stock_movements (variant_id,movement_type,available_delta,available_after,damaged_after)
  SELECT id,'OPENING',5,5,0 FROM product_variants WHERE sku='T-SHIRT-1-M-BLU';
UPDATE stock_movements SET available_delta = 99;
ROLLBACK TO a;
\echo 'EXPECT FAIL 5: audit log is append-only'
INSERT INTO audit_logs (action) VALUES ('X');
DELETE FROM audit_logs;
ROLLBACK TO a;
\echo 'EXPECT FAIL 6: duplicate supplier invoice number (case-insensitive)'
INSERT INTO suppliers (supplier_code,name) VALUES ('S1','Sup');
INSERT INTO purchase_invoices (purchase_no,supplier_id,supplier_invoice_no,purchase_date) SELECT 'PUR1',id,'ab-12',current_date FROM suppliers WHERE supplier_code='S1';
INSERT INTO purchase_invoices (purchase_no,supplier_id,supplier_invoice_no,purchase_date) SELECT 'PUR2',id,'AB-12',current_date FROM suppliers WHERE supplier_code='S1';
ROLLBACK TO a;
\echo 'EXPECT FAIL 7: over-return of a purchase item'
INSERT INTO suppliers (supplier_code,name) VALUES ('S1','Sup');
INSERT INTO purchase_invoices (purchase_no,supplier_id,supplier_invoice_no,purchase_date) SELECT 'PUR1',id,'X1',current_date FROM suppliers WHERE supplier_code='S1';
INSERT INTO purchase_items (purchase_id,variant_id,quantity,unit_price,returned_qty)
  SELECT (SELECT id FROM purchase_invoices WHERE purchase_no='PUR1'),(SELECT id FROM product_variants WHERE sku='T-SHIRT-1-M-BLU'),2,100,3;
ROLLBACK TO a;
\echo 'EXPECT FAIL 8: duplicate invoice idempotency key'
INSERT INTO sales_invoices (invoice_no,subtotal,taxable_total,total,client_request_id) VALUES ('I1',1,1,1,'11111111-1111-1111-1111-111111111111');
INSERT INTO sales_invoices (invoice_no,subtotal,taxable_total,total,client_request_id) VALUES ('I2',1,1,1,'11111111-1111-1111-1111-111111111111');
ROLLBACK TO a;
\echo 'EXPECT FAIL 9: invalid username'
INSERT INTO users (username,full_name,password_hash,role_id) SELECT 'Bad Name!','x','x',id FROM roles LIMIT 1;
ROLLBACK;
\echo 'ALL EXPECTED FAILURES CHECKED'
