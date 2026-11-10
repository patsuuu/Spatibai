# Spatibai music website

A self-hosted music catalog with a public player, account registration and email verification, and a password-protected admin page. The Node.js version stores music on a persistent server disk and accounts/sessions in MySQL.

## Run the Node.js version locally

Install Node.js 20 or newer. In PowerShell, from the project folder:

```powershell
$env:SPATIBAI_ADMIN_PASSWORD = "choose-a-long-unique-password"
npm start
```

Open `http://localhost:3000` for the Spatibai home page, `http://localhost:3000/library.html` for the public music library, and `http://localhost:3000/admin` to upload music. The home page provides the Android APK download at `/download/spatibai.apk`. Supported uploads: MP3, M4A, AAC, WAV, OGG, OPUS, FLAC, and WEBM audio; maximum file size is 100 MB. You can add a title and artist, and remove tracks from the admin page.

The development server binds to `0.0.0.0` by default for Render compatibility. To run it locally for this computer only, set `HOST=127.0.0.1`. Use a unique strong admin password, set `NODE_ENV=production` when HTTPS is configured so the admin session cookie is marked secure, and back up the `data` folder.

## Deploy to Render and Aiven

For the Android app's API, account verification, and Gmail code delivery, use the Node.js backend with Render and Aiven MySQL. Follow `DEPLOY_RENDER_AIVEN.md` for service setup, TLS database connection, environment variables, and updating the app's server URL. Render's local filesystem is temporary; add a paid persistent disk or external object storage to keep uploaded audio and the track catalog. A PHP/MySQL alternative for InfinityFree is documented in `hosting\infinityfree`, but InfinityFree's browser challenge may block requests from the APK.

## Connect the Android app

The home page at `/` links to the web music library at `/library.html`, the admin upload page at `/admin`, and the Android download at `/download/spatibai.apk`. The Android APK has its own native music interface; it does not open the admin website. It refreshes the public catalog every 30 seconds while open, downloads new tracks into private app storage, and plays saved tracks offline. A new upload needs an internet connection once so the app can refresh and save it. Set the app's server URL to the Render HTTPS URL in its gear settings. Account registration, six-digit Gmail verification, login, and logout use the Node.js API with Aiven MySQL; verification codes are sent to the registered address and never shown in the APK.

The site streams the audio files you upload; it does not include Spotify's catalog or automatically make the music available offline on a user's device. Only upload music you have permission to share.
"# Spatibai" 
"# Spatibai" 
