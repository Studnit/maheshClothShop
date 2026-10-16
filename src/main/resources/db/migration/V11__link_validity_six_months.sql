-- Customer bill links stay valid for 6 months (180 days); links already issued are extended too.
UPDATE app_settings SET setting_value = '180' WHERE setting_key = 'whatsapp.link_validity_days' AND setting_value = '90';
UPDATE invoice_documents SET token_expires_at = created_at + interval '180 days' WHERE token_expires_at IS NOT NULL;
