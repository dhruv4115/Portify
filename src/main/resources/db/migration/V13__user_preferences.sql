-- Display preferences that follow the account rather than the device.
--
-- Every column is NULLABLE with no DEFAULT, and that is the important part: NULL means "this
-- user has never expressed a server-side preference", which is different from "this user chose
-- the default". The client only overrides its own local choice when it receives a non-NULL
-- value, so adding this table column cannot silently reset the theme of anyone who was already
-- happy with what their browser had stored.
--
-- Values are the lowercase tokens the frontend writes straight onto <html data-theme="...">
-- rather than an uppercase enum, because that is the vocabulary these belong to — see
-- PreferencesDto for why they are not modelled as Java enums.
--
-- language_code, not `language`: LANGUAGE is a (non-reserved) keyword in MySQL and reads
-- ambiguously in a WHERE clause. The API field is still called `language`.
ALTER TABLE app_user
    ADD COLUMN theme         VARCHAR(16) NULL,
    ADD COLUMN language_code VARCHAR(16) NULL,
    ADD COLUMN density       VARCHAR(16) NULL,
    ADD COLUMN motion        VARCHAR(16) NULL;
