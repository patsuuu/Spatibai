# Deploy Spatibai with Render, Aiven, and Gmail

The Node.js server supports the Android app's registration, six-digit email verification, login, and logout API. Aiven stores accounts and sessions; Render runs the API and website. Gmail SMTP secrets belong only in Render environment variables.

## 1. Create the Aiven MySQL database

1. Create an Aiven for MySQL service and wait until its status is `Running`.
2. Open the service's connection information. Record the host, port, database name, username, and password.
3. Download the service CA certificate from Aiven's connection information. You will paste its full PEM text into Render as `DB_CA_CERT`.
4. Do not put database credentials or the certificate in Git, the APK, or a public config file. The Node service creates the account, verification, session, and rate-limit tables on first start.

## 2. Create the Render web service

1. Push this project to a GitHub repository you control.
2. In Render, create a **Web Service** connected to that repository. Use the repository root as the root directory, `npm ci` as the build command, and `npm start` as the start command. Render supplies `PORT`; the server listens on that port and on `0.0.0.0`.
3. Set these environment variables in the Render service's **Environment** page:

   | Key | Value |
   | --- | --- |
   | `SPATIBAI_ADMIN_PASSWORD` | A new, unique admin password (at least 8 characters) |
   | `DB_HOST` | Aiven host |
   | `DB_PORT` | Aiven port |
   | `DB_NAME` | Aiven database name |
   | `DB_USER` | Aiven username |
   | `DB_PASSWORD` | Aiven database password |
   | `DB_CA_CERT` | Entire downloaded Aiven CA certificate, including the `BEGIN` and `END CERTIFICATE` lines |
   | `SMTP_HOST` | `smtp.gmail.com` |
   | `SMTP_PORT` | `587` |
   | `SMTP_USERNAME` | The Gmail account that sends verification messages |
   | `SMTP_PASSWORD` | Gmail App Password, not your normal Gmail password |
   | `SMTP_FROM_EMAIL` | The same Gmail address as `SMTP_USERNAME` |
   | `NODE_ENV` | `production` |
   | `DATA_DIR` | `/var/data` if you attach a persistent disk |

4. If the CA certificate or App Password is multiline / contains spaces, paste it directly into the Render environment value field. The server strips spaces from the Gmail App Password. Never send these secrets in chat or commit them.
5. Deploy and wait for the service to become `Live`. Render's health check can use `/healthz`.

## 3. Keep uploaded music persistent

Render's local filesystem is temporary unless the service has a persistent disk. To retain admin-uploaded audio and the track catalog through deploys/restarts, attach a persistent disk mounted at `/var/data` and set `DATA_DIR=/var/data`. Persistent disks require a paid Render web service and have a separate storage charge. If you use a free service without a disk, do not rely on uploaded tracks remaining available; use a persistent object-storage service instead.

## 4. Point the Android app at Render

After deployment, copy the public HTTPS URL from the Render service, for example `https://spatibai-api.onrender.com`. In the app, open **Settings** using the gear icon, set the music server URL to that exact URL (no trailing slash), and save it. The current APK's default URL is still the old InfinityFree URL; update this setting or replace `DEFAULT_SERVER_URL` in `app/src/main/java/com/example/spatibai/MainActivity.java` with your actual Render URL and rebuild the APK.

Check `https://YOUR-RENDER-HOST/healthz` returns `{"ok":true}` and `https://YOUR-RENDER-HOST/api.php?route=tracks` returns JSON such as `{"tracks":[]}`. The app must receive JSON rather than an HTML challenge page.

## 5. Gmail verification

1. Enable 2-Step Verification on the sending Gmail account.
2. Create a Google App Password at <https://myaccount.google.com/apppasswords>.
3. Put it in Render as `SMTP_PASSWORD`; don't use your regular Gmail password.
4. Register using an email address you can access. The six-digit code is sent only to that address, expires after 15 minutes, and is never returned by the API or shown in the app.
5. If signup reports SMTP failure, inspect the Render service logs. Confirm the App Password and check that the runtime can connect to Gmail SMTP on port 587. If the provider blocks SMTP, use an email provider's HTTPS API instead or move the service to a host that allows outbound SMTP.

## Notes

- The API creates its MySQL tables automatically during startup; no manual SQL import is needed for a new Aiven database.
- `/admin` remains the website music-upload page.
- If an old database or Gmail credential was ever exposed in a public file, rotate it before deployment.
