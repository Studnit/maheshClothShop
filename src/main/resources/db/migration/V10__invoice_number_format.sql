-- Bill numbers look like MCS-20261016-134512 (prefix, date, time). Owners can still change this in Settings.
UPDATE app_settings SET setting_value = 'MCS' WHERE setting_key = 'invoice.prefix' AND setting_value = 'INV';
UPDATE app_settings SET setting_value = '{PREFIX}-{YYYYMMDD}-{HHMMSS}' WHERE setting_key = 'invoice.format' AND setting_value = '{PREFIX}/{FYS}/{SEQ}';
