# Spatibai on InfinityFree

InfinityFree hosts PHP and MySQL, not the Node.js `server.js`. This folder contains a PHP replacement for the API, while the existing `public` folder contains the shared website pages.

## 1. Create a MySQL database

In the InfinityFree control panel, create a MySQL database and open phpMyAdmin for that database. Import `spatibai.sql`. This creates the music catalog and the account, pending-verification, and session tables. Copy the database name, username, password, and hostname exactly as shown in the control panel.

## 2. Upload the site

In the hosting account's `htdocs` directory, upload the contents of `htdocs-upload`. If using FTP, enable showing and uploading hidden files so the `.htaccess` files are included.

The admin page is available at `/admin` and `/admin.html`. The `.htaccess` file maps the short `/admin` address to `admin.html`.

Rename `config.example.php` to `config.php`, then edit it with the database credentials and a new admin password hash. Do not reuse the short password shared in chat. From CMD or PowerShell, generate a hash for a new unique password:

```text
php -r "echo password_hash('YOUR_NEW_LONG_UNIQUE_PASSWORD', PASSWORD_DEFAULT), PHP_EOL;"
```

Replace the example password before running the command, then copy the printed hash into `config.php` as `admin_password_hash`. The API uses Gmail SMTP through PHPMailer. To prepare Gmail:

1. Turn on 2-Step Verification for the Gmail account.
2. Create a Google App Password at `https://myaccount.google.com/apppasswords`. Use the 16-character App Password without spaces, not your normal Gmail password. Google may not show App Passwords for some managed or Advanced Protection accounts.
3. Keep `verification_mode` set to `mail` and fill in `verification_smtp_username` and `verification_from_email` with that Gmail address, plus `verification_smtp_password` with the App Password. Keep host `smtp.gmail.com`, port `587`, and encryption `tls`. Store these values only in the server's `config.php`; never put the App Password in the APK, a public file, or source control. The code is emailed only to the submitted address and is never included in the API response or shown in the APK.
4. Upload the `vendor` directory and `composer.json`/`composer.lock` from `htdocs-upload` along with the updated PHP files. PHPMailer is required by the API to connect to Gmail SMTP. Put the Gmail settings in the real server-side `htdocs/config.php`, not `config.example.php`.

**Hosting limitation:** InfinityFree shared hosting may block outbound SMTP connections, including Gmail's ports 587/465. If a signup fails with an SMTP connection error, correct credentials will not fix a blocked port. Gmail SMTP then cannot be used from that hosting account; the API must run on hosting that permits outbound SMTP or use an email provider's HTTPS API from a host that allows outbound HTTPS.

**Credential safety:** `config.example.php` is a template only; never put working database or Gmail credentials in it. If credentials were ever placed in a template or uploaded publicly, immediately change the database password in the hosting control panel and revoke the Gmail App Password, then place newly generated credentials only in the private server-side `config.php`.

App registrations receive a six-digit code at the submitted email address in production. Codes expire after 15 minutes and allow up to five verification attempts. The account is inserted into `users` only after the code is confirmed. Users must verify their email before they can log in. Passwords must be 8–72 characters and are stored as PHP password hashes; app sessions use expiring, revocable random tokens.

Create `htdocs/storage/audio`. It must be writable by PHP; start with the hosting panel's file manager permissions and avoid granting more access than necessary. Files in this folder are blocked from direct access by its `.htaccess`; the API streams them to the player.

## 3. Connect the Android app

Open the deployed site over HTTPS and confirm that `/api.php?route=tracks` returns JSON (not an HTML browser/security challenge). If the host returns a challenge or blocks requests from the APK, the app cannot register accounts there; use a host that allows API requests from Android apps. Upload the updated `htdocs-upload` files, including `vendor`, and configure the Gmail SMTP settings in the server's private `config.php`. Install the updated APK. If needed, tap the gear icon to set the HTTPS site address (for example `https://your-site.infinityfreeapp.com`). Test signup only after verifying that hosting allows outbound SMTP and that Gmail accepts the App Password. Admin uploads stay on the website at `/admin.html`. The native Android app refreshes the music catalog every 30 seconds while open and downloads tracks to the phone for offline playback. New tracks need one successful online refresh/download first.

## Upload limits and maintenance

Shared hosting sets its own PHP upload, request-size, execution-time, and disk limits. The PHP API accepts files up to 100 MB only when the hosting PHP limits also allow them; for larger files, reduce `upload_max_filesize` and `post_max_size` expectations or use hosting with larger limits. Check InfinityFree's current account limits if uploads fail. Back up both the MySQL database and `storage/audio`; the database contains track records, not the audio itself.

Only upload music you have permission to share. Do not publish `config.php` or database credentials.
