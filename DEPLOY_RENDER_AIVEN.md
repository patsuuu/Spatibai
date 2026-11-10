# Deploy Spatibai with Render, Aiven, Gmail, and Cloudflare R2

The Node.js server supports the Android app's registration, six-digit email verification, login, and logout API. Aiven stores accounts and sessions; Render runs the API and website. Verification mail is sent through a Google Apps Script HTTPS relay, so no Gmail password or SMTP credential is stored in Render.

## 1. Create the Aiven MySQL database

1. Create an Aiven for MySQL service and wait until its status is `Running`.
2. Open the service's connection information. Record the host, port, database name, username, and password.
3. Download the service CA certificate from Aiven's connection information. You will paste its full PEM text into Render as `DB_CA_CERT`.
4. Do not put database credentials or the certificate in Git, the APK, or a public config file. The Node service creates the account, verification, session, and rate-limit tables on first start.

## 2. Create the Render web service

1. Push this project to a GitHub repository you control.
2. In Render, create a **Web Service** connected to that repository. Use the repository root as the root directory, `npm ci` as the build command, and `npm start` as the start command. Render supplies `PORT`; the server listens on that port and on `0.0.0.0`.
3. Before enabling uploads, create a Cloudflare R2 bucket:
   - In the Cloudflare dashboard, open **R2 Object Storage** and create a private bucket.
   - Create an R2 API token restricted to that bucket with object read/write permissions. Keep the token secret.
   - Copy the Cloudflare account ID, access key ID, secret access key, and bucket name. The app uses the S3-compatible endpoint and the `auto` region; do not make the bucket public.
4. Set these environment variables in the Render service's **Environment** page:

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
   | `R2_ACCOUNT_ID` | Cloudflare account ID |
   | `R2_ACCESS_KEY_ID` | Access key ID from the R2 API token |
   | `R2_SECRET_ACCESS_KEY` | Secret access key from the R2 API token |
   | `R2_BUCKET_NAME` | Exact name of the private R2 bucket |
   | `YOUTUBE_API_KEY` | Server-only Google API key restricted to YouTube Data API v3 |
   | `AUDIUS_API_KEY` | Optional Audius API key for previously imported Audius tracks |

5. Paste the CA certificate directly into the Render environment value field. Never send secrets in chat or commit them.
6. Deploy and wait for the service to become `Live`. Render's health check can use `/healthz`.

## 3. Keep uploaded music persistent with R2

Audio files are stored under `audio/` in the private R2 bucket, and the admin catalog is stored as `tracks.json` in that bucket. Render deploys and restarts do not remove R2 objects. On first startup with R2 enabled, the server attempts to copy an existing local catalog and its audio files into R2 if the bucket does not already have `tracks.json`.

Cloudflare R2 has a free monthly allowance for standard storage and operations. Usage over the free allowance can be billed; check the current [R2 pricing](https://developers.cloudflare.com/r2/pricing/) and set a Cloudflare billing alert/limit before uploading a large music collection. The admin page checks that the bucket is reachable and disables uploads if storage is not available. Keep a separate backup of important tracks; object storage is not itself a backup.

## 4. Configure YouTube search in the Android app

1. In Google Cloud Console, create or select a project and enable **YouTube Data API v3**.
2. Create an API key, restrict it to **YouTube Data API v3**, and store it only in Render's environment as `YOUTUBE_API_KEY`. Never add the key to Android source, the APK, or Git.
3. Check the project's YouTube Data API quota. Searches use the YouTube Data API search endpoint and can consume quota quickly; the server applies a per-account limit and a shared rolling 80-search-per-24-hours cap to protect the default project quota.
4. Redeploy the Render service. Signed-in app users can search current videos. Results are not added to the Spatibai catalog or saved as a song cache; playback uses YouTube's official embedded player, with YouTube controls and branding. YouTube availability, embedding permissions, ads, and playback rules remain controlled by YouTube and each video owner.

Admins use `/admin` only to upload audio they own or are licensed to distribute. YouTube search results cannot be imported as audio files or downloaded for offline use.

## 5. Existing Audius imports

Previously imported Audius tracks remain in the catalog and stream from Audius with an internet connection; the Android app does not cache them offline. The admin interface no longer searches/imports Audius tracks. This is a community-uploaded catalog, not the complete Spotify catalog. Check each creator's rights and track license.

If the catalog contains previously imported Audius tracks, the server may use `AUDIUS_API_KEY` for their stream requests. New Audius search/import is no longer available in the admin. See the [Audius developer docs](https://docs.audius.co/sdk) and current terms if you continue serving those legacy catalog entries.

## 6. Point the Android app at Render

After deployment, copy the public HTTPS URL from the Render service, for example `https://spatibai-api.onrender.com`. In the app, open **Settings** using the gear icon, set the music server URL to that exact URL (no trailing slash), and save it. The current APK's default URL is still the old InfinityFree URL; update this setting or replace `DEFAULT_SERVER_URL` in `app/src/main/java/com/example/spatibai/MainActivity.java` with your actual Render URL and rebuild the APK.

Check `https://YOUR-RENDER-HOST/healthz` returns `{"ok":true}` and `https://YOUR-RENDER-HOST/api.php?route=tracks` returns JSON such as `{"tracks":[]}`. The app must receive JSON rather than an HTML challenge page.

## 7. Configure Gmail verification relay

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
