const crypto = require("node:crypto");
const fs = require("node:fs/promises");
const fsSync = require("node:fs");
const http = require("node:http");
const path = require("node:path");
const { pipeline } = require("node:stream/promises");
const { Readable, Transform } = require("node:stream");
const {
  DeleteObjectCommand,
  GetObjectCommand,
  HeadBucketCommand,
  HeadObjectCommand,
  PutObjectCommand,
  S3Client,
} = require("@aws-sdk/client-s3");
const mysql = require("mysql2/promise");
const { promisify } = require("node:util");
const musicMetadata = import("music-metadata");

const ROOT = __dirname;
const DATA_DIR = process.env.DATA_DIR || path.join(ROOT, "data");
const AUDIO_DIR = path.join(DATA_DIR, "audio");
const TRACKS_FILE = path.join(DATA_DIR, "tracks.json");
const r2Config = {
  accountId: process.env.R2_ACCOUNT_ID?.trim(),
  accessKeyId: process.env.R2_ACCESS_KEY_ID?.trim(),
  secretAccessKey: process.env.R2_SECRET_ACCESS_KEY?.trim(),
  bucket: process.env.R2_BUCKET_NAME?.trim(),
};
const r2Configured = Object.values(r2Config).every(Boolean);
const r2PartiallyConfigured = Object.values(r2Config).some(Boolean);
const r2Client = r2Configured
  ? new S3Client({
    region: "auto",
    endpoint: process.env.R2_ENDPOINT?.trim() ||
      `https://${r2Config.accountId}.r2.cloudflarestorage.com`,
    forcePathStyle: true,
    credentials: {
      accessKeyId: r2Config.accessKeyId,
      secretAccessKey: r2Config.secretAccessKey,
    },
  })
  : null;
const PORT = Number(process.env.PORT || 3000);
const HOST = process.env.HOST || "0.0.0.0";
const ADMIN_PASSWORD = process.env.SPATIBAI_ADMIN_PASSWORD;
const MAX_UPLOAD_BYTES = 100 * 1024 * 1024;
const MAX_UPLOAD_FILES = 20;
const MAX_UPLOAD_REQUEST_BYTES = 500 * 1024 * 1024;
const AUDIUS_API_BASE = "https://api.audius.co/v1";
const AUDIUS_API_KEY = process.env.AUDIUS_API_KEY?.trim();
const SESSION_TTL_MS = 12 * 60 * 60 * 1000;
const AUTH_SESSION_TTL_SECONDS = 30 * 24 * 60 * 60;
const sessions = new Map();
const loginAttempts = new Map();
const scrypt = promisify(crypto.scrypt);
const databaseConfig = {
  host: process.env.DB_HOST,
  port: Number(process.env.DB_PORT || 3306),
  database: process.env.DB_NAME,
  user: process.env.DB_USER,
  password: process.env.DB_PASSWORD,
  ca: process.env.DB_CA_CERT,
};
const databaseConfigured = Boolean(
  databaseConfig.host || databaseConfig.database || databaseConfig.user ||
  databaseConfig.password || databaseConfig.ca,
);
const dbPool = Object.values(databaseConfig).every(Boolean)
  ? mysql.createPool({
    host: databaseConfig.host,
    port: databaseConfig.port,
    database: databaseConfig.database,
    user: databaseConfig.user,
    password: databaseConfig.password,
    ssl: { ca: databaseConfig.ca, rejectUnauthorized: true },
    waitForConnections: true,
    connectionLimit: 5,
    queueLimit: 0,
    timezone: "Z",
  })
  : null;
const MIME_TYPES = {
  ".aac": "audio/aac",
  ".flac": "audio/flac",
  ".m4a": "audio/mp4",
  ".mp3": "audio/mpeg",
  ".ogg": "audio/ogg",
  ".opus": "audio/ogg",
  ".wav": "audio/wav",
  ".weba": "audio/webm",
};
let writeQueue = Promise.resolve();

if (!ADMIN_PASSWORD) {
  console.error("Set SPATIBAI_ADMIN_PASSWORD before starting the website.");
  process.exit(1);
}
if (Buffer.byteLength(ADMIN_PASSWORD, "utf8") < 8) {
  console.error("SPATIBAI_ADMIN_PASSWORD must be at least 8 bytes long.");
  process.exit(1);
}

function sendJson(res, status, value) {
  res.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
  });
  res.end(JSON.stringify(value));
}

function sendText(res, status, message) {
  res.writeHead(status, {
    "Content-Type": "text/plain; charset=utf-8",
    "X-Content-Type-Options": "nosniff",
  });
  res.end(message);
}

async function loadTracks() {
  if (r2Client) {
    try {
      const result = await r2Client.send(new GetObjectCommand({
        Bucket: r2Config.bucket,
        Key: "tracks.json",
      }));
      const tracks = JSON.parse(await result.Body.transformToString("utf-8"));
      if (!Array.isArray(tracks)) throw new Error("The R2 track catalog is not an array.");
      return tracks;
    } catch (error) {
      if (error.name === "NoSuchKey" || error.name === "NotFound" || error.$metadata?.httpStatusCode === 404) {
        return [];
      }
      throw error;
    }
  }
  try {
    const text = await fs.readFile(TRACKS_FILE, "utf8");
    const tracks = JSON.parse(text);
    return Array.isArray(tracks) ? tracks : [];
  } catch (error) {
    if (error.code === "ENOENT") return [];
    throw error;
  }
}

async function migrateLocalTracksToR2() {
  if (!r2Client) return;

  try {
    await r2Client.send(new HeadObjectCommand({
      Bucket: r2Config.bucket,
      Key: "tracks.json",
    }));
    return;
  } catch (error) {
    if (error.name !== "NoSuchKey" && error.name !== "NotFound" &&
        error.$metadata?.httpStatusCode !== 404) {
      console.error("Could not check the Cloudflare R2 catalog before migration:", error);
      return;
    }
  }

  let tracks;
  try {
    tracks = JSON.parse(await fs.readFile(TRACKS_FILE, "utf8"));
  } catch (error) {
    if (error.code === "ENOENT") return;
    throw error;
  }
  if (!Array.isArray(tracks) || tracks.length === 0) return;

  for (const track of tracks) {
    if (typeof track.filename !== "string" || path.basename(track.filename) !== track.filename) {
      throw new Error("Cannot migrate a track with an invalid filename.");
    }
    const audio = await fs.readFile(path.join(AUDIO_DIR, track.filename));
    await r2Client.send(new PutObjectCommand({
      Bucket: r2Config.bucket,
      Key: `audio/${track.filename}`,
      Body: audio,
      ContentType: track.mimeType || "application/octet-stream",
    }));
  }

  await r2Client.send(new PutObjectCommand({
    Bucket: r2Config.bucket,
    Key: "tracks.json",
    Body: JSON.stringify(tracks, null, 2),
    ContentType: "application/json; charset=utf-8",
  }));
  console.log(`Migrated ${tracks.length} existing track(s) to Cloudflare R2.`);
}

function updateTracks(update) {
  const operation = writeQueue.then(async () => {
    const tracks = await loadTracks();
    const result = await update(tracks);
    const body = JSON.stringify(tracks, null, 2);
    if (r2Client) {
      await r2Client.send(new PutObjectCommand({
        Bucket: r2Config.bucket,
        Key: "tracks.json",
        Body: body,
        ContentType: "application/json; charset=utf-8",
      }));
    } else {
      const temporary = `${TRACKS_FILE}.${crypto.randomUUID()}.tmp`;
      await fs.writeFile(temporary, body, { flag: "wx" });
      await fs.rename(temporary, TRACKS_FILE);
    }
    return result;
  });
  writeQueue = operation.catch(() => {});
  return operation;
}

function getCookie(req, name) {
  const cookies = req.headers.cookie || "";
  for (const part of cookies.split(";")) {
    const separator = part.indexOf("=");
    if (separator > 0 && part.slice(0, separator).trim() === name) {
      return decodeURIComponent(part.slice(separator + 1).trim());
    }
  }
  return "";
}

function isAdmin(req) {
  const token = getCookie(req, "spatibai_admin");
  const expiresAt = sessions.get(token);
  if (!expiresAt) return false;
  if (expiresAt < Date.now()) {
    sessions.delete(token);
    return false;
  }
  return true;
}

function passwordMatches(candidate) {
  const expected = crypto.createHash("sha256").update(ADMIN_PASSWORD).digest();
  const actual = crypto.createHash("sha256").update(candidate).digest();
  return crypto.timingSafeEqual(expected, actual);
}

function clientKey(req) {
  return req.socket.remoteAddress || "unknown";
}

async function readJson(req, maxLength = 8192) {
  const chunks = [];
  let length = 0;
  for await (const chunk of req) {
    length += chunk.length;
    if (length > maxLength) {
      const error = new Error("Request is too large.");
      error.status = 413;
      throw error;
    }
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    const error = new Error("Invalid JSON request.");
    error.status = 400;
    throw error;
  }
}

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

async function requestAudius(endpoint, params = {}) {
  const url = new URL(`${AUDIUS_API_BASE}/${endpoint}`);
  url.searchParams.set("app_name", "Spatibai");
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null) url.searchParams.set(key, String(value));
  }
  const headers = { Accept: "application/json" };
  if (AUDIUS_API_KEY) headers.Authorization = `Bearer ${AUDIUS_API_KEY}`;
  let response;
  try {
    response = await fetch(url, { headers, signal: AbortSignal.timeout(15000) });
  } catch (error) {
    console.error("Audius API request failed:", error);
    throw httpError(502, "Hindi makakonekta sa Audius. Subukan ulit mamaya.");
  }
  if (!response.ok) {
    console.error(`Audius API returned HTTP ${response.status} for ${endpoint}.`);
    throw httpError(502, "Hindi sumagot nang maayos ang Audius. Subukan ulit mamaya.");
  }
  let body;
  try {
    body = await response.json();
  } catch {
    throw httpError(502, "Hindi mabasa ang sagot ng Audius.");
  }
  return body.data;
}

function requireDatabase() {
  if (!dbPool) {
    throw httpError(503, "Authentication database is not configured. Set the Aiven DB_* environment variables.");
  }
  return dbPool;
}

async function initializeDatabase() {
  const pool = requireDatabase();
  await pool.query(`CREATE TABLE IF NOT EXISTS users (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    email_verified_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`);
  await pool.query(`CREATE TABLE IF NOT EXISTS pending_registrations (
    email VARCHAR(254) NOT NULL PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    verification_hash VARCHAR(255) NOT NULL,
    expires_at DATETIME NOT NULL,
    requested_at DATETIME NOT NULL,
    attempts TINYINT UNSIGNED NOT NULL DEFAULT 0,
    INDEX pending_registrations_expiry (expires_at)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`);
  await pool.query(`CREATE TABLE IF NOT EXISTS user_sessions (
    token_hash CHAR(64) NOT NULL PRIMARY KEY,
    user_id BIGINT UNSIGNED NOT NULL,
    expires_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX user_sessions_user (user_id),
    INDEX user_sessions_expiry (expires_at),
    CONSTRAINT user_sessions_user_fk FOREIGN KEY (user_id)
      REFERENCES users (id) ON DELETE CASCADE
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`);
  await pool.query(`CREATE TABLE IF NOT EXISTS auth_rate_limits (
    rate_key CHAR(64) NOT NULL PRIMARY KEY,
    attempts SMALLINT UNSIGNED NOT NULL DEFAULT 0,
    window_started_at DATETIME NOT NULL
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`);
}

async function hashSecret(secret) {
  const salt = crypto.randomBytes(16);
  const derived = await scrypt(secret, salt, 64);
  return `scrypt$${salt.toString("hex")}$${derived.toString("hex")}`;
}

async function verifySecret(secret, encoded) {
  const [algorithm, saltHex, hashHex] = String(encoded).split("$");
  if (algorithm !== "scrypt" || !/^[a-f0-9]{32}$/i.test(saltHex || "") ||
      !/^[a-f0-9]{128}$/i.test(hashHex || "")) return false;
  const expected = Buffer.from(hashHex, "hex");
  const actual = await scrypt(secret, Buffer.from(saltHex, "hex"), expected.length);
  return crypto.timingSafeEqual(actual, expected);
}

async function checkAuthRate(action, req, limit) {
  const pool = requireDatabase();
  const key = crypto.createHash("sha256")
    .update(`${action}\0${clientKey(req)}`)
    .digest("hex");
  await pool.execute(
    `INSERT INTO auth_rate_limits (rate_key, attempts, window_started_at)
     VALUES (?, 1, UTC_TIMESTAMP())
     ON DUPLICATE KEY UPDATE
       attempts = IF(window_started_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL 15 MINUTE),
                     1, attempts + 1),
       window_started_at = IF(window_started_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL 15 MINUTE),
                              UTC_TIMESTAMP(), window_started_at)`,
    [key],
  );
  const [rows] = await pool.execute(
    "SELECT attempts FROM auth_rate_limits WHERE rate_key = ?",
    [key],
  );
  if (Number(rows[0]?.attempts || 0) > limit) {
    throw httpError(429, "Masyadong maraming request. Subukan ulit pagkalipas ng 15 minuto.");
  }
}

async function sendVerificationCode(email, name, code) {
  const bridgeUrl = process.env.GMAIL_BRIDGE_URL;
  const bridgeSecret = process.env.GMAIL_BRIDGE_SECRET;
  if (!bridgeUrl || !bridgeSecret || bridgeSecret.length < 32) {
    throw new Error("Gmail HTTPS relay is not configured.");
  }
  let parsedUrl;
  try {
    parsedUrl = new URL(bridgeUrl);
  } catch {
    throw new Error("Gmail HTTPS relay URL is invalid.");
  }
  if (parsedUrl.protocol !== "https:" || !parsedUrl.pathname.endsWith("/exec")) {
    throw new Error("Gmail HTTPS relay URL is invalid.");
  }

  const response = await fetch(parsedUrl, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ secret: bridgeSecret, email, name, code }),
    signal: AbortSignal.timeout(20000),
    redirect: "follow",
  });
  const result = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(`Gmail HTTPS relay returned HTTP ${response.status}.`);
  }
  if (result?.ok !== true) {
    const relayError = ["unauthorized", "invalid_request", "delivery_failed"]
      .includes(result?.error) ? result.error : "invalid_response";
    throw new Error(`Gmail HTTPS relay rejected the email request (${relayError}).`);
  }
}

function emailFailureMessage(error) {
  if (error.message.includes("not configured") || error.message.includes("URL is invalid")) {
    return "Hindi pa kumpleto ang Gmail HTTPS relay setup sa Render. Tingnan ang GMAIL_BRIDGE_URL at GMAIL_BRIDGE_SECRET.";
  }
  if (error.message.includes("(unauthorized)")) {
    return "Hindi tugma ang GMAIL_BRIDGE_SECRET sa Render at sa Script Properties ng Google Apps Script.";
  }
  if (error.message.includes("(delivery_failed)")) {
    return "Hindi nakapagpadala ang Gmail account ng Apps Script. Tingnan ang Executions at pahintulutan ang MailApp.";
  }
  if (error.message.includes("(invalid_request)")) {
    return "Hindi tama ang Apps Script request. Tiyaking nailagay at na-deploy ang tamang relay code.";
  }
  if (error.message.includes("(invalid_response)")) {
    return "Hindi JSON ang sagot ng Apps Script. Tiyaking tama ang Web App URL, naka-access ito sa Anyone, at aktibo ang deployment.";
  }
  return "Hindi nakapagpadala ng email ang Gmail relay. Tingnan ang Google Apps Script Executions at Render Logs.";
}

async function registerUser(req, res) {
  const pool = requireDatabase();
  await checkAuthRate("register", req, 5);
  const body = await readJson(req);
  const name = typeof body.name === "string" ? body.name.trim() : "";
  const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
  const password = body.password;
  if (!name || name.length > 80 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) ||
      email.length > 254 || typeof password !== "string" ||
      Buffer.byteLength(password, "utf8") < 8 || Buffer.byteLength(password, "utf8") > 72) {
    throw httpError(400, "Ilagay ang valid na pangalan at email. Dapat 8–72 bytes ang password.");
  }
  const [users] = await pool.execute("SELECT id FROM users WHERE email = ?", [email]);
  if (users.length) throw httpError(409, "May account na gamit ang email na ito. Mag-log in na lang.");
  const [pendingRows] = await pool.execute(
    "SELECT requested_at FROM pending_registrations WHERE email = ?",
    [email],
  );
  if (pendingRows.length && new Date(pendingRows[0].requested_at).getTime() > Date.now() - 60000) {
    throw httpError(429, "May verification code nang ipinadala. Hintayin muna nang isang minuto bago humingi ulit.");
  }

  const code = String(crypto.randomInt(100000, 1000000));
  const verificationHash = await hashSecret(code);
  const passwordHash = await hashSecret(password);
  await pool.execute(
    `INSERT INTO pending_registrations
      (email, name, password_hash, verification_hash, expires_at, requested_at, attempts)
     VALUES (?, ?, ?, ?, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 15 MINUTE), UTC_TIMESTAMP(), 0)
     ON DUPLICATE KEY UPDATE name = VALUES(name), password_hash = VALUES(password_hash),
       verification_hash = VALUES(verification_hash),
       expires_at = VALUES(expires_at), requested_at = VALUES(requested_at), attempts = 0`,
    [email, name, passwordHash, verificationHash],
  );
  try {
    await sendVerificationCode(email, name, code);
  } catch (error) {
    await pool.execute(
      "DELETE FROM pending_registrations WHERE email = ? AND verification_hash = ?",
      [email, verificationHash],
    );
    console.error("Verification email delivery failed:", error.message);
    throw httpError(503, emailFailureMessage(error));
  }
  sendJson(res, 200, { message: "Naipadala ang verification code sa iyong email." });
}

async function resendVerification(req, res) {
  const pool = requireDatabase();
  await checkAuthRate("resend", req, 5);
  const body = await readJson(req);
  const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || email.length > 254) {
    throw httpError(400, "Ilagay ang valid na email address.");
  }
  const [rows] = await pool.execute(
    "SELECT name, requested_at FROM pending_registrations WHERE email = ?",
    [email],
  );
  if (!rows.length) throw httpError(404, "Walang nakabinbing registration para sa email na ito.");
  if (new Date(rows[0].requested_at).getTime() > Date.now() - 60000) {
    throw httpError(429, "Maghintay muna nang isang minuto bago humingi ulit ng code.");
  }
  const code = String(crypto.randomInt(100000, 1000000));
  const verificationHash = await hashSecret(code);
  await pool.execute(
    `UPDATE pending_registrations SET verification_hash = ?,
       expires_at = DATE_ADD(UTC_TIMESTAMP(), INTERVAL 15 MINUTE),
       requested_at = UTC_TIMESTAMP(), attempts = 0 WHERE email = ?`,
    [verificationHash, email],
  );
  try {
    await sendVerificationCode(email, rows[0].name, code);
  } catch (error) {
    await pool.execute(
      "DELETE FROM pending_registrations WHERE email = ? AND verification_hash = ?",
      [email, verificationHash],
    );
    console.error("Verification email resend failed:", error.message);
    throw httpError(503, emailFailureMessage(error));
  }
  sendJson(res, 200, { message: "Naipadala ulit ang verification code." });
}

async function verifyRegistration(req, res) {
  const pool = requireDatabase();
  await checkAuthRate("verify", req, 20);
  const body = await readJson(req);
  const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
  const code = typeof body.code === "string" ? body.code.trim() : "";
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || !/^\d{6}$/.test(code)) {
    throw httpError(400, "Ilagay ang email at 6-digit verification code.");
  }
  const connection = await pool.getConnection();
  let inTransaction = false;
  try {
    await connection.beginTransaction();
    inTransaction = true;
    const [rows] = await connection.execute(
      `SELECT name, password_hash, verification_hash, attempts
       FROM pending_registrations
       WHERE email = ? AND expires_at > UTC_TIMESTAMP() FOR UPDATE`,
      [email],
    );
    const pending = rows[0];
    if (!pending) throw httpError(400, "Mali o expired na ang code. Mag-request ng bagong code.");
    if (Number(pending.attempts) >= 5) {
      await connection.execute("DELETE FROM pending_registrations WHERE email = ?", [email]);
      await connection.commit();
      inTransaction = false;
      throw httpError(429, "Maraming maling code. Mag-register ulit para makatanggap ng bagong code.");
    }
    if (!await verifySecret(code, pending.verification_hash)) {
      await connection.execute(
        "UPDATE pending_registrations SET attempts = attempts + 1 WHERE email = ?",
        [email],
      );
      await connection.commit();
      inTransaction = false;
      throw httpError(400, "Mali ang verification code.");
    }
    await connection.execute(
      `INSERT INTO users (name, email, password_hash, email_verified_at)
       VALUES (?, ?, ?, UTC_TIMESTAMP())`,
      [pending.name, email, pending.password_hash],
    );
    await connection.execute("DELETE FROM pending_registrations WHERE email = ?", [email]);
    await connection.commit();
    inTransaction = false;
    sendJson(res, 200, { message: "Verified na ang email. Maaari ka nang mag-log in." });
  } catch (error) {
    if (inTransaction) await connection.rollback();
    throw error;
  } finally {
    connection.release();
  }
}

async function loginUser(req, res) {
  const pool = requireDatabase();
  await checkAuthRate("login", req, 10);
  const body = await readJson(req);
  const email = typeof body.email === "string" ? body.email.trim().toLowerCase() : "";
  const password = body.password;
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email) || typeof password !== "string" ||
      Buffer.byteLength(password, "utf8") > 72) {
    throw httpError(400, "Ilagay ang valid na email at password.");
  }
  const [rows] = await pool.execute(
    "SELECT id, name, email, password_hash FROM users WHERE email = ?",
    [email],
  );
  const user = rows[0];
  if (!user || !await verifySecret(password, user.password_hash)) {
    throw httpError(401, "Hindi tugma ang email o password. Siguraduhing verified na ang email.");
  }
  const token = crypto.randomBytes(32).toString("hex");
  const tokenHash = crypto.createHash("sha256").update(token).digest("hex");
  await pool.execute(
    `INSERT INTO user_sessions (token_hash, user_id, expires_at)
     VALUES (?, ?, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 30 DAY))`,
    [tokenHash, user.id],
  );
  sendJson(res, 200, { token, name: user.name, email: user.email });
}

async function getAuthSession(req, res) {
  const pool = requireDatabase();
  const authorization = req.headers.authorization || "";
  const token = authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
  if (!/^[a-f0-9]{64}$/.test(token)) throw httpError(401, "Mag-log in para magpatuloy.");
  const tokenHash = crypto.createHash("sha256").update(token).digest("hex");
  const [rows] = await pool.execute(
    `SELECT u.id, u.name, u.email FROM user_sessions s
     JOIN users u ON u.id = s.user_id
     WHERE s.token_hash = ? AND s.expires_at > UTC_TIMESTAMP()`,
    [tokenHash],
  );
  if (!rows.length) throw httpError(401, "Expired o hindi valid ang session.");
  sendJson(res, 200, { user: rows[0] });
}

async function logoutUser(req, res) {
  const pool = requireDatabase();
  const authorization = req.headers.authorization || "";
  const token = authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
  if (/^[a-f0-9]{64}$/.test(token)) {
    const tokenHash = crypto.createHash("sha256").update(token).digest("hex");
    await pool.execute("DELETE FROM user_sessions WHERE token_hash = ?", [tokenHash]);
  }
  sendJson(res, 200, { ok: true });
}

function staticFile(res, pathname) {
  const routes = {
    "/": ["index.html", "text/html; charset=utf-8"],
    "/library": ["library.html", "text/html; charset=utf-8"],
    "/library.html": ["library.html", "text/html; charset=utf-8"],
    "/app.html": ["app.html", "text/html; charset=utf-8"],
    "/index.html": ["index.html", "text/html; charset=utf-8"],
    "/admin": ["admin.html", "text/html; charset=utf-8"],
    "/admin.html": ["admin.html", "text/html; charset=utf-8"],
    "/download/spatibai.apk": ["download/spatibai.apk", "application/vnd.android.package-archive", "Spatibai.apk"],
    "/styles.css": ["styles.css", "text/css; charset=utf-8"],
    "/app.js": ["app.js", "text/javascript; charset=utf-8"],
    "/admin.js": ["admin.js", "text/javascript; charset=utf-8"],
  };
  const entry = routes[pathname];
  if (!entry) {
    sendText(res, 404, "Not found");
    return;
  }
  const [filename, contentType, downloadName] = entry;
  const file = path.join(ROOT, "public", filename);
  fs.readFile(file).then((body) => {
    const headers = {
      "Content-Type": contentType,
      "Cache-Control": "no-cache",
      "X-Content-Type-Options": "nosniff",
      "Content-Security-Policy": "default-src 'self'; media-src 'self'; style-src 'self'; script-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'",
      "Referrer-Policy": "same-origin",
    };
    if (downloadName) {
      headers["Content-Disposition"] = `attachment; filename="${downloadName}"`;
    }
    res.writeHead(200, headers);
    res.end(body);
  }).catch((error) => {
    console.error("Unable to read website file:", error);
    sendText(res, 500, "Website file unavailable");
  });
}

async function readForm(req) {
  const contentLength = Number(req.headers["content-length"] || 0);
  if (contentLength > MAX_UPLOAD_REQUEST_BYTES + 2 * 1024 * 1024) {
    const error = new Error("Ang kabuuang batch upload ay lampas 500 MB.");
    error.status = 413;
    throw error;
  }
  if (!req.headers["content-type"]?.toLowerCase().startsWith("multipart/form-data;")) {
    const error = new Error("Expected a multipart form upload.");
    error.status = 415;
    throw error;
  }
  let receivedBytes = 0;
  const limitedBody = new Transform({
    transform(chunk, encoding, callback) {
      receivedBytes += chunk.length;
      if (receivedBytes > MAX_UPLOAD_REQUEST_BYTES + 2 * 1024 * 1024) {
        callback(httpError(413, "Ang kabuuang batch upload ay lampas 500 MB."));
        return;
      }
      callback(null, chunk);
    },
  });
  req.pipe(limitedBody);
  const request = new Request("http://localhost/upload", {
    method: "POST",
    headers: req.headers,
    body: Readable.toWeb(limitedBody),
    duplex: "half",
  });
  let form;
  try {
    form = await request.formData();
  } catch (parseError) {
    if (parseError.status) throw parseError;
    const formError = new Error("The upload form could not be read.");
    formError.status = 400;
    throw formError;
  }
  const files = form.getAll("audio").filter((file) => file && typeof file.arrayBuffer === "function" &&
    (file.size > 0 || file.name));
  if (files.length === 0) throw httpError(400, "Pumili ng kahit isang audio file.");
  if (files.length > MAX_UPLOAD_FILES) {
    throw httpError(400, `Hanggang ${MAX_UPLOAD_FILES} kanta lang bawat bulk upload.`);
  }
  return files;
}

function fallbackTrackNames(filename) {
  const base = path.basename(filename, path.extname(filename))
    .replace(/[_.]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  const parts = base.split(/\s+-\s+/, 2).map((part) => part.trim());
  if (parts.length === 2 && parts[0] && parts[1]) {
    return { title: parts[1], artist: parts[0] };
  }
  return { title: base || "Untitled track", artist: "Unknown artist" };
}

async function getTrackMetadata(file, audio, extension) {
  const fallback = fallbackTrackNames(file.name);
  const notes = [];
  let title = "";
  let artist = "";
  try {
    const { parseBuffer } = await musicMetadata;
    const metadata = await parseBuffer(audio, MIME_TYPES[extension], {
      fileInfo: { path: file.name },
      duration: false,
    });
    title = String(metadata.common.title || "").trim();
    artist = String(metadata.common.artist ||
      (metadata.common.artists || []).join(", ")).trim();
  } catch (error) {
    console.warn(`Could not read audio tags from "${path.basename(file.name)}":`, error.message);
    notes.push("Hindi mabasa ang tags; filename ang ginamit.");
  }
  if (!title) {
    title = fallback.title;
    if (!notes.length) notes.push("Walang title tag; filename ang ginamit.");
  }
  if (!artist) {
    artist = fallback.artist;
    if (!notes.length) notes.push("Walang artist tag; filename ang ginamit.");
  }
  return {
    title: title.slice(0, 120),
    artist: artist.slice(0, 120),
    metadataNote: notes.join(" "),
  };
}

async function saveUploadedTrack(file) {
  const extension = path.extname(file.name).toLowerCase();
  if (!Object.hasOwn(MIME_TYPES, extension)) {
    throw httpError(415, "Hindi suportadong format. Gumamit ng MP3, M4A, AAC, WAV, OGG, OPUS, FLAC o WEBM.");
  }
  if (file.size === 0 || file.size > MAX_UPLOAD_BYTES) {
    throw httpError(file.size > MAX_UPLOAD_BYTES ? 413 : 400,
      "Dapat 1 byte hanggang 100 MB ang bawat audio file.");
  }
  const audio = Buffer.from(await file.arrayBuffer());
  const { title, artist, metadataNote } = await getTrackMetadata(file, audio, extension);
  const id = crypto.randomUUID();
  const filename = `${id}${extension}`;
  const key = `audio/${filename}`;
  if (r2Client) {
    await r2Client.send(new PutObjectCommand({
      Bucket: r2Config.bucket,
      Key: key,
      Body: audio,
      ContentType: MIME_TYPES[extension],
    }));
  } else {
    await fs.writeFile(path.join(AUDIO_DIR, filename), audio, { flag: "wx" });
  }
  const track = {
    id,
    title,
    artist,
    lyrics: "",
    filename,
    mimeType: MIME_TYPES[extension],
    size: file.size,
    uploadedAt: new Date().toISOString(),
  };
  try {
    await updateTracks((tracks) => {
      tracks.unshift(track);
      return track;
    });
  } catch (error) {
    if (r2Client) {
      await r2Client.send(new DeleteObjectCommand({ Bucket: r2Config.bucket, Key: key })).catch((cleanupError) => {
        console.error("Could not remove an unlisted R2 audio upload:", cleanupError);
      });
    } else {
      await fs.unlink(path.join(AUDIO_DIR, filename)).catch((cleanupError) => {
        if (cleanupError.code !== "ENOENT") console.error("Could not clean up failed audio upload:", cleanupError);
      });
    }
    throw error;
  }
  return { track, metadataNote };
}

async function uploadTrack(req, res) {
  if (!isAdmin(req)) return sendJson(res, 401, { error: "Admin login required." });
  if (process.env.RENDER && !r2Client) {
    return sendJson(res, 503, {
      error: "Uploads are paused. Configure Cloudflare R2 using R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, and R2_BUCKET_NAME.",
    });
  }
  try {
    const files = await readForm(req);
    const uploaded = [];
    const failed = [];
    for (const file of files.slice().reverse()) {
      const displayName = path.basename(file.name || "audio file");
      try {
        const result = await saveUploadedTrack(file);
        uploaded.push({
          title: result.track.title,
          artist: result.track.artist,
          metadataNote: result.metadataNote,
        });
      } catch (error) {
        if (!error.status) console.error(`Bulk audio upload failed for "${displayName}":`, error);
        failed.push({ file: displayName, error: error.status ? error.message : "Hindi na-save ang audio file." });
      }
    }
    uploaded.reverse();
    failed.reverse();
    sendJson(res, 207, { uploaded, failed });
  } catch (error) {
    if (error.status) return sendJson(res, error.status, { error: error.message });
    console.error("Bulk track upload failed:", error);
    sendJson(res, 500, { error: "Hindi natuloy ang bulk music upload." });
  }
}

async function updateTrackLyrics(req, res, id) {
  if (!isAdmin(req)) return sendJson(res, 401, { error: "Admin login required." });
  const body = await readJson(req, 65536);
  if (typeof body.lyrics !== "string" || body.lyrics.length > 12000) {
    return sendJson(res, 400, { error: "Lyrics must be text with up to 12,000 characters." });
  }
  const track = await updateTracks((tracks) => {
    const found = tracks.find((item) => item.id === id);
    if (!found) return null;
    found.lyrics = body.lyrics.trim();
    return found;
  });
  if (!track) return sendJson(res, 404, { error: "Track not found." });
  sendJson(res, 200, { track });
}

async function deleteTrack(req, res, id) {
  if (!isAdmin(req)) return sendJson(res, 401, { error: "Admin login required." });
  const track = await updateTracks((tracks) => {
    const found = tracks.find((item) => item.id === id);
    if (!found) return null;
    tracks.splice(tracks.indexOf(found), 1);
    return found;
  });
  if (!track) return sendJson(res, 404, { error: "Track not found." });
  if (track.source === "audius") {
    sendJson(res, 200, { ok: true });
    return;
  }
  if (r2Client) {
    await r2Client.send(new DeleteObjectCommand({
      Bucket: r2Config.bucket,
      Key: `audio/${track.filename}`,
    }));
  } else {
    await fs.unlink(path.join(AUDIO_DIR, track.filename)).catch((error) => {
      if (error.code !== "ENOENT") throw error;
    });
  }
  sendJson(res, 200, { ok: true });
}

async function serveAudio(req, res, id) {
  const tracks = await loadTracks();
  const track = tracks.find((item) => item.id === id);
  if (!track) return sendText(res, 404, "Audio not found");
  if (track.source === "audius") {
    if (!/^[a-zA-Z0-9_-]{1,64}$/.test(track.providerTrackId || "")) {
      return sendText(res, 502, "Invalid Audius track reference");
    }
    const streamUrl = new URL(
      `${AUDIUS_API_BASE}/tracks/${encodeURIComponent(track.providerTrackId)}/stream`,
    );
    streamUrl.searchParams.set("app_name", "Spatibai");
    if (AUDIUS_API_KEY) streamUrl.searchParams.set("api_key", AUDIUS_API_KEY);
    res.writeHead(302, {
      Location: streamUrl.toString(),
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    });
    res.end();
    return;
  }
  const range = req.headers.range;
  const headers = {
    "Content-Type": track.mimeType,
    "Accept-Ranges": "bytes",
    "Cache-Control": "public, max-age=3600",
    "X-Content-Type-Options": "nosniff",
  };
  let start = 0;
  let end = track.size - 1;
  let partial = false;
  if (range) {
    const match = /^bytes=(\d*)-(\d*)$/.exec(range);
    if (!match || (!match[1] && !match[2])) {
      res.writeHead(416, { ...headers, "Content-Range": `bytes */${track.size}` });
      return res.end();
    }
    if (!match[1]) {
      const suffixLength = Number(match[2]);
      start = Math.max(0, track.size - suffixLength);
    } else {
      start = Number(match[1]);
    }
    end = match[2] && match[1] ? Math.min(Number(match[2]), track.size - 1) : end;
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) ||
        start >= track.size || start > end) {
      res.writeHead(416, { ...headers, "Content-Range": `bytes */${track.size}` });
      return res.end();
    }
    partial = true;
  }

  if (r2Client) {
    let result;
    try {
      result = await r2Client.send(new GetObjectCommand({
        Bucket: r2Config.bucket,
        Key: `audio/${track.filename}`,
        ...(partial ? { Range: `bytes=${start}-${end}` } : {}),
      }));
    } catch (error) {
      if (error.name === "NoSuchKey" || error.name === "NotFound" ||
          error.$metadata?.httpStatusCode === 404) {
        return sendText(res, 404, "Audio file not found");
      }
      throw error;
    }
    const responseHeaders = {
      ...headers,
      "Content-Length": result.ContentLength ?? end - start + 1,
    };
    if (partial) responseHeaders["Content-Range"] = `bytes ${start}-${end}/${track.size}`;
    res.writeHead(partial ? 206 : 200, responseHeaders);
    return pipeline(result.Body, res);
  }

  const filename = path.join(AUDIO_DIR, track.filename);
  let stat;
  try {
    stat = await fs.stat(filename);
  } catch (error) {
    if (error.code === "ENOENT") return sendText(res, 404, "Audio file not found");
    throw error;
  }
  end = Math.min(end, stat.size - 1);
  const responseHeaders = {
    ...headers,
    "Content-Length": partial ? end - start + 1 : stat.size,
  };
  if (partial) responseHeaders["Content-Range"] = `bytes ${start}-${end}/${stat.size}`;
  res.writeHead(partial ? 206 : 200, responseHeaders);
  return pipeline(fsSync.createReadStream(filename, partial ? { start, end } : {}), res);
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);
  const pathname = url.pathname;
  const apiRoute = pathname === "/api.php" ? url.searchParams.get("route") : null;
  try {
    if (req.method === "GET" && pathname === "/healthz") {
      return sendJson(res, 200, { ok: true });
    }
    if (req.method === "GET" && apiRoute === "tracks") {
      return sendJson(res, 200, { tracks: await loadTracks() });
    }
    if (req.method === "POST" && apiRoute === "auth-register") {
      return await registerUser(req, res);
    }
    if (req.method === "POST" && apiRoute === "auth-resend") {
      return await resendVerification(req, res);
    }
    if (req.method === "POST" && apiRoute === "auth-verify") {
      return await verifyRegistration(req, res);
    }
    if (req.method === "POST" && apiRoute === "auth-login") {
      return await loginUser(req, res);
    }
    if (req.method === "GET" && apiRoute === "auth-session") {
      return await getAuthSession(req, res);
    }
    if (req.method === "POST" && apiRoute === "auth-logout") {
      return await logoutUser(req, res);
    }
    if (req.method === "GET" && apiRoute === "media") {
      const id = url.searchParams.get("id") || "";
      if (!/^[0-9a-f-]{36}$/i.test(id)) return sendText(res, 404, "Audio not found");
      return await serveAudio(req, res, id);
    }
    if (req.method === "GET" && pathname.startsWith("/media/")) {
      const id = pathname.slice("/media/".length);
      if (!/^[0-9a-f-]{36}$/i.test(id)) return sendText(res, 404, "Audio not found");
      return await serveAudio(req, res, id);
    }
    if (req.method === "GET" && pathname === "/api/tracks") {
      return sendJson(res, 200, { tracks: await loadTracks() });
    }
    if (req.method === "GET" && pathname === "/api/admin/session") {
      return sendJson(res, 200, { authenticated: isAdmin(req) });
    }
    if (req.method === "GET" && pathname === "/api/admin/storage") {
      if (!isAdmin(req)) return sendJson(res, 401, { error: "Admin login required." });
      if (!r2Client) {
        return sendJson(res, 200, {
          onRender: Boolean(process.env.RENDER),
          provider: "local",
          configured: false,
          available: !process.env.RENDER,
        });
      }
      try {
        await r2Client.send(new HeadBucketCommand({ Bucket: r2Config.bucket }));
        return sendJson(res, 200, {
          onRender: Boolean(process.env.RENDER),
          provider: "cloudflare-r2",
          configured: true,
          available: true,
        });
      } catch (error) {
        console.error("Cloudflare R2 storage check failed:", error);
        return sendJson(res, 200, {
          onRender: Boolean(process.env.RENDER),
          provider: "cloudflare-r2",
          configured: true,
          available: false,
        });
      }
    }
    if (req.method === "POST" && pathname === "/api/admin/login") {
      const key = clientKey(req);
      const attempt = loginAttempts.get(key);
      if (attempt && attempt.count >= 10 && Date.now() - attempt.startedAt < 15 * 60 * 1000) {
        return sendJson(res, 429, { error: "Too many attempts. Try again in 15 minutes." });
      }
      try {
        const body = await readJson(req);
        if (typeof body.password !== "string" || body.password.length > 256 ||
            !passwordMatches(body.password)) {
          const current = loginAttempts.get(key);
          loginAttempts.set(key, current && Date.now() - current.startedAt < 15 * 60 * 1000
            ? { ...current, count: current.count + 1 }
            : { startedAt: Date.now(), count: 1 });
          return sendJson(res, 401, { error: "Incorrect admin password." });
        }
        loginAttempts.delete(key);
        const token = crypto.randomBytes(32).toString("base64url");
        sessions.set(token, Date.now() + SESSION_TTL_MS);
        res.writeHead(200, {
          "Set-Cookie": `spatibai_admin=${encodeURIComponent(token)}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${SESSION_TTL_MS / 1000}${process.env.NODE_ENV === "production" ? "; Secure" : ""}`,
          "Cache-Control": "no-store",
          "Content-Type": "application/json; charset=utf-8",
        });
        return res.end(JSON.stringify({ authenticated: true }));
      } catch (error) {
        return sendJson(res, error.status || 400, { error: error.message });
      }
    }
    if (req.method === "POST" && pathname === "/api/admin/logout") {
      sessions.delete(getCookie(req, "spatibai_admin"));
      res.writeHead(200, {
        "Set-Cookie": "spatibai_admin=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0",
        "Cache-Control": "no-store",
        "Content-Type": "application/json; charset=utf-8",
      });
      return res.end(JSON.stringify({ authenticated: false }));
    }
    if (req.method === "POST" && pathname === "/api/admin/tracks") {
      return await uploadTrack(req, res);
    }
    const deleteMatch = /^\/api\/admin\/tracks\/([0-9a-f-]{36})$/.exec(pathname);
    if (req.method === "PATCH" && deleteMatch) {
      return await updateTrackLyrics(req, res, deleteMatch[1]);
    }
    if (req.method === "DELETE" && deleteMatch) {
      return await deleteTrack(req, res, deleteMatch[1]);
    }
    if (req.method === "GET") return staticFile(res, pathname);
    sendJson(res, 404, { error: "Not found." });
  } catch (error) {
    if (res.headersSent) {
      res.destroy(error);
      return;
    }
    if (error.status) {
      return sendJson(res, error.status, { error: error.message });
    }
    console.error("Request failed:", error);
    sendJson(res, 500, { error: "An unexpected server error occurred." });
  }
});

async function start() {
  if (r2PartiallyConfigured && !r2Configured) {
    throw new Error("Cloudflare R2 configuration is incomplete. Set R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, and R2_BUCKET_NAME.");
  }
  if (process.env.RENDER && !r2Client) {
    console.warn("Cloudflare R2 is not configured. Admin uploads are disabled to prevent data loss.");
  }
  if (!r2Client) {
    await fs.mkdir(AUDIO_DIR, { recursive: true });
  } else {
    try {
      await migrateLocalTracksToR2();
    } catch (error) {
      console.error("Could not migrate existing local tracks to Cloudflare R2:", error);
    }
  }
  if (databaseConfigured) {
    if (!dbPool) {
      throw new Error("Aiven database configuration is incomplete. Set DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD, and DB_CA_CERT.");
    }
    await initializeDatabase();
  }
  server.listen(PORT, HOST, () => {
    console.log(`Spatibai music site is running at http://localhost:${PORT}`);
    console.log("Admin page: /admin");
    if (!dbPool) console.warn("Authentication routes are disabled until Aiven DB_* variables are configured.");
  });
}

start().catch((error) => {
  console.error("Could not start Spatibai music site:", error);
  process.exit(1);
});
