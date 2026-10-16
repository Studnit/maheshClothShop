-- =====================================================================
-- Reference data every installation needs. No demo data, no shop contact details,
-- no GST numbers: those are entered by the owner under Settings.
-- =====================================================================

INSERT INTO permissions (code, group_name, description) VALUES
 ('PRODUCT_VIEW',      'Catalogue',  'View products, variants and prices'),
 ('PRODUCT_MANAGE',    'Catalogue',  'Create, edit and deactivate products, variants, categories, brands, sizes, colours; import products'),
 ('COST_VIEW',         'Catalogue',  'See purchase costs and margins'),
 ('STOCK_VIEW',        'Inventory',  'View stock levels and stock movement history'),
 ('STOCK_ADJUST',      'Inventory',  'Record stock adjustments, opening stock and damaged items'),
 ('STOCK_APPROVE',     'Inventory',  'Approve sensitive stock changes: write-offs and physical-count reconciliation'),
 ('SUPPLIER_VIEW',     'Purchasing', 'View suppliers and balances'),
 ('SUPPLIER_MANAGE',   'Purchasing', 'Create and edit suppliers'),
 ('PURCHASE_VIEW',     'Purchasing', 'View purchase invoices'),
 ('PURCHASE_MANAGE',   'Purchasing', 'Create, receive and return purchase invoices'),
 ('SUPPLIER_PAY',      'Purchasing', 'Record supplier payments'),
 ('SALE_CREATE',       'Sales',      'Use the billing screen and complete sales'),
 ('SALE_VIEW',         'Sales',      'View sales invoices'),
 ('SALE_CANCEL',       'Sales',      'Cancel (reverse) a completed sales invoice'),
 ('CREDIT_SALE',       'Sales',      'Complete a sale with an unpaid balance (credit sale)'),
 ('DISCOUNT_UNLIMITED','Sales',      'Give discounts above the cashier discount limit'),
 ('INVOICE_PRINT',     'Sales',      'Print and reprint invoices'),
 ('CUSTOMER_VIEW',     'Customers',  'View customers and their history'),
 ('CUSTOMER_MANAGE',   'Customers',  'Create and edit customers'),
 ('RETURN_PROCESS',    'Sales',      'Process sales returns and exchanges'),
 ('EXPENSE_VIEW',      'Expenses',   'View expenses'),
 ('EXPENSE_MANAGE',    'Expenses',   'Record and edit expenses'),
 ('REPORT_SALES',      'Reports',    'View sales and payment reports and all invoices'),
 ('REPORT_STOCK',      'Reports',    'View stock, low-stock and stock-movement reports'),
 ('REPORT_FINANCIAL',  'Reports',    'View profit, stock valuation, supplier and customer balances'),
 ('DASHBOARD_FINANCIAL','Reports',   'See financial tiles on the dashboard'),
 ('USER_MANAGE',       'Administration', 'Manage users and role permissions'),
 ('SETTINGS_MANAGE',   'Administration', 'Change shop, tax, invoice and system settings'),
 ('AUDIT_VIEW',        'Administration', 'View the audit log');

INSERT INTO roles (id, name, description, system_role) VALUES
 ('00000000-0000-0000-0000-0000000000a1', 'OWNER',         'Full access to everything', true),
 ('00000000-0000-0000-0000-0000000000a2', 'CASHIER',       'Billing, returns and customers', true),
 ('00000000-0000-0000-0000-0000000000a3', 'STOCK_MANAGER', 'Products, purchasing and inventory', true);

-- Owner: everything
INSERT INTO role_permissions (role_id, permission_code)
SELECT '00000000-0000-0000-0000-0000000000a1', code FROM permissions;

-- Cashier
INSERT INTO role_permissions (role_id, permission_code) VALUES
 ('00000000-0000-0000-0000-0000000000a2', 'PRODUCT_VIEW'),
 ('00000000-0000-0000-0000-0000000000a2', 'STOCK_VIEW'),
 ('00000000-0000-0000-0000-0000000000a2', 'SALE_CREATE'),
 ('00000000-0000-0000-0000-0000000000a2', 'SALE_VIEW'),
 ('00000000-0000-0000-0000-0000000000a2', 'INVOICE_PRINT'),
 ('00000000-0000-0000-0000-0000000000a2', 'CUSTOMER_VIEW'),
 ('00000000-0000-0000-0000-0000000000a2', 'CUSTOMER_MANAGE'),
 ('00000000-0000-0000-0000-0000000000a2', 'RETURN_PROCESS');

-- Stock manager
INSERT INTO role_permissions (role_id, permission_code) VALUES
 ('00000000-0000-0000-0000-0000000000a3', 'PRODUCT_VIEW'),
 ('00000000-0000-0000-0000-0000000000a3', 'PRODUCT_MANAGE'),
 ('00000000-0000-0000-0000-0000000000a3', 'COST_VIEW'),
 ('00000000-0000-0000-0000-0000000000a3', 'STOCK_VIEW'),
 ('00000000-0000-0000-0000-0000000000a3', 'STOCK_ADJUST'),
 ('00000000-0000-0000-0000-0000000000a3', 'SUPPLIER_VIEW'),
 ('00000000-0000-0000-0000-0000000000a3', 'SUPPLIER_MANAGE'),
 ('00000000-0000-0000-0000-0000000000a3', 'PURCHASE_VIEW'),
 ('00000000-0000-0000-0000-0000000000a3', 'PURCHASE_MANAGE'),
 ('00000000-0000-0000-0000-0000000000a3', 'REPORT_STOCK');

INSERT INTO payment_methods (code, name, requires_reference, selectable, sort_order) VALUES
 ('CASH',            'Cash',            false, true,  1),
 ('UPI',             'UPI',             true,  true,  2),
 ('CARD',            'Debit / Credit card', true, true, 3),
 ('BANK_TRANSFER',   'Bank transfer',   true,  true,  4),
 ('EXCHANGE_CREDIT', 'Exchange credit', false, false, 99);

INSERT INTO categories (name, sort_order) VALUES
 ('Shirts', 1), ('T-Shirts', 2), ('Trousers', 3), ('Jeans', 4), ('Sarees', 5),
 ('Dresses', 6), ('Kurtis', 7), ('Kids Wear', 8), ('Traditional Wear', 9), ('Accessories', 10);

INSERT INTO sizes (name, sort_order) VALUES
 ('Free Size', 1), ('XS', 2), ('S', 3), ('M', 4), ('L', 5), ('XL', 6), ('XXL', 7), ('3XL', 8),
 ('28', 20), ('30', 21), ('32', 22), ('34', 23), ('36', 24), ('38', 25), ('40', 26), ('42', 27),
 ('0-1 Yr', 40), ('1-2 Yr', 41), ('2-4 Yr', 42), ('4-6 Yr', 43), ('6-8 Yr', 44), ('8-10 Yr', 45), ('10-12 Yr', 46);

INSERT INTO colours (name, hex_code, sort_order) VALUES
 ('Black', '#111111', 1), ('White', '#FFFFFF', 2), ('Blue', '#2563EB', 3), ('Navy', '#1E3A8A', 4),
 ('Red', '#DC2626', 5), ('Green', '#16A34A', 6), ('Yellow', '#FACC15', 7), ('Pink', '#EC4899', 8),
 ('Grey', '#6B7280', 9), ('Maroon', '#7F1D1D', 10), ('Beige', '#D6C7A1', 11), ('Orange', '#F97316', 12),
 ('Purple', '#7C3AED', 13), ('Brown', '#78350F', 14), ('Multicolour', NULL, 30);

INSERT INTO expense_categories (name, sort_order) VALUES
 ('Rent', 1), ('Electricity', 2), ('Salaries', 3), ('Packaging', 4), ('Transport', 5),
 ('Repairs and maintenance', 6), ('Tea and refreshments', 7), ('Other', 99);

-- Defaults. Anything shop-specific is left blank on purpose.
INSERT INTO app_settings (setting_key, setting_value) VALUES
 ('shop.name',              'Mahesh Clothes Shop'),
 ('shop.address',           ''),
 ('shop.village',           'Lohara'),
 ('shop.district',          'Dharashiv'),
 ('shop.state',             'Maharashtra'),
 ('shop.pin',               ''),
 ('shop.phone',             ''),
 ('shop.email',             ''),
 ('shop.gstin',             ''),
 ('shop.logo_file_id',      ''),
 ('tax.enabled',            'false'),
 ('tax.prices_include_tax', 'true'),
 ('tax.default_rate',       '0'),
 ('invoice.prefix',         'INV'),
 -- Tokens: {PREFIX} {FY} (2026-27) {FYS} (2627) {YYYY} {YY} {SEQ}. Default stays within the
 -- 16-character limit GST places on invoice numbers, e.g. INV/2627/00001
 ('invoice.format',         '{PREFIX}/{FYS}/{SEQ}'),
 ('invoice.seq_padding',    '5'),
 ('invoice.footer',         'Thank you for shopping with us. Please visit again!'),
 ('invoice.return_policy',  ''),
 ('invoice.round_off',      'false'),
 ('receipt.paper_width',    '80'),
 ('language.default',       'en'),
 ('stock.low_threshold',    '5'),
 ('credit.enabled',         'false'),
 ('discount.cashier_max_percent', '10'),
 ('return.window_days',     ''),
 ('purchase.tax_in_cost',   'true'),
 ('backup.enabled',         'false'),
 ('backup.retention_days',  '30'),
 ('backup.notes',           '');
