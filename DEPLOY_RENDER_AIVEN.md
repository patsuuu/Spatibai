# Deploy Spatibai with Render, Aiven, and Gmail

The Node.js server supports the Android app's registration, six-digit email verification, login, and logout API. Aiven stores accounts and sessions; Render runs the API and website. Verification mail is sent through a Google Apps Script HTTPS relay, so no Gmail password or SMTP credential is stored in Render.

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
   | `GMAIL_BRIDGE_URL` | The deployed Google Apps Script Web App URL ending in `/exec` |
   | `GMAIL_BRIDGE_SECRET` | The same long random secret stored in the Apps Script project property |
   | `NODE_ENV` | `production` |
   | `DATA_DIR` | `/var/data` if you attach a persistent disk |

4. Paste the CA certificate directly into the Render environment value field. Never send secrets in chat or commit them.
5. Deploy and wait for the service to become `Live`. Render's health check can use `/healthz`.

## 3. Keep uploaded music persistent

Render's local filesystem is temporary unless the service has a persistent disk. To retain admin-uploaded audio and the track catalog through deploys/restarts, attach a persistent disk mounted at `/var/data` and set `DATA_DIR=/var/data`. Persistent disks require a paid Render web service and have a separate storage charge. If you use a free service without a disk, do not rely on uploaded tracks remaining available; use a persistent object-storage service instead.

## 4. Point the Android app at Render

After deployment, copy the public HTTPS URL from the Render service, for example `https://spatibai-api.onrender.com`. In the app, open **Settings** using the gear icon, set the music server URL to that exact URL (no trailing slash), and save it. The current APK's default URL is still the old InfinityFree URL; update this setting or replace `DEFAULT_SERVER_URL` in `app/src/main/java/com/example/spatibai/MainActivity.java` with your actual Render URL and rebuild the APK.

Check `https://YOUR-RENDER-HOST/healthz` returns `{"ok":true}` and `https://YOUR-RENDER-HOST/api.php?route=tracks` returns JSON such as `{"tracks":[]}`. The app must receive JSON rather than an HTML challenge page.

## 5. Configure Gmail verification relay

1. Sign in to the Google account that should send verification mail, then open <https://script.google.com> and create a project.
2. Copy `hosting/render-gmail-relay.gs` into the project's `Code.gs`.
3. In **Project Settings → Script Properties**, add `GMAIL_BRIDGE_SECRET`. Set its value to a unique random secret of at least 32 characters. Do not put that value in the source file or Git.
4. In **Deploy → New deployment**, choose **Web app**, set **Execute as** to your account, and set access to **Anyone**. Deploy and authorize the requested Gmail permission. Copy the Web App URL ending in `/exec`.
5. Add that URL as Render's `GMAIL_BRIDGE_URL` and the exact same secret as `GMAIL_BRIDGE_SECRET`. Save the environment changes and redeploy.
6. Register using an email address you can access. The six-digit code is sent only to that address, expires after 15 minutes, and is never returned by the API or shown in the app.
7. If signup reports an email failure, inspect **Executions** in Apps Script and the Render service logs. Confirm the Web App deployment is active, the URL ends in `/exec`, and both secrets match. After changing Apps Script code, create a new deployment version.

## Notes

- The API creates its MySQL tables automatically during startup; no manual SQL import is needed for a new Aiven database.
- `/admin` remains the website music-upload page.
- If an old database or Gmail credential was ever exposed in a public file, rotate it before deployment.
