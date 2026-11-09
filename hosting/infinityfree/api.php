<?php
declare(strict_types=1);

$mailerAutoload = __DIR__ . '/vendor/autoload.php';
if (is_file($mailerAutoload)) {
    require_once $mailerAutoload;
}

function respond(int $status, array $data): never
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function fail(int $status, string $message): never
{
    respond($status, ['error' => $message]);
}

function loadConfig(): array
{
    $file = __DIR__ . '/config.php';
    if (!is_file($file)) {
        fail(500, 'The site administrator must configure config.php.');
    }

    $config = require $file;
    if (!is_array($config)) {
        fail(500, 'The site administrator must finish config.php setup.');
    }
    foreach (['db_host', 'db_name', 'db_user', 'db_password', 'admin_password_hash'] as $key) {
        if (!isset($config[$key]) || str_starts_with((string)$config[$key], 'REPLACE_')) {
            fail(500, 'The site administrator must finish config.php setup.');
        }
    }
    return $config;
}

function db(array $config): PDO
{
    static $connection = null;
    if ($connection instanceof PDO) {
        return $connection;
    }
    $dsn = $config['db_dsn'] ??
        ('mysql:host=' . $config['db_host'] . ';dbname=' . $config['db_name'] . ';charset=utf8mb4');
    $connection = new PDO($dsn, $config['db_user'] ?? null, $config['db_password'] ?? null, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES => false,
    ]);
    return $connection;
}

function startAdminSession(): void
{
    if (session_status() === PHP_SESSION_ACTIVE) {
        return;
    }
    $secure = isset($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== '' && $_SERVER['HTTPS'] !== 'off';
    session_name('spatibai_admin');
    session_set_cookie_params([
        'lifetime' => 0,
        'path' => '/',
        'secure' => $secure,
        'httponly' => true,
        'samesite' => 'Strict',
    ]);
    session_start();
}

function requireAdmin(): void
{
    startAdminSession();
    if (empty($_SESSION['admin_authenticated'])) {
        fail(401, 'Admin login required.');
    }
}

function requireSameOrigin(): void
{
    $origin = $_SERVER['HTTP_ORIGIN'] ?? '';
    if ($origin === '') {
        return;
    }
    $originHost = parse_url($origin, PHP_URL_HOST);
    $requestHost = parse_url('//' . ($_SERVER['HTTP_HOST'] ?? ''), PHP_URL_HOST);
    if (!is_string($originHost) || !is_string($requestHost) ||
        strcasecmp($originHost, $requestHost) !== 0) {
        fail(403, 'Cross-site request rejected.');
    }
}

function authBody(): array
{
    $raw = file_get_contents('php://input');
    if (!is_string($raw) || strlen($raw) > 16384) {
        fail(413, 'Request is too large.');
    }
    $body = json_decode($raw, true);
    if (!is_array($body)) {
        fail(400, 'Invalid request.');
    }
    return $body;
}

function checkAuthRate(PDO $pdo, string $action, int $limit): void
{
    $ip = (string)($_SERVER['REMOTE_ADDR'] ?? 'unknown');
    $key = hash('sha256', $action . "\0" . $ip);
    $record = $pdo->prepare(
        'INSERT INTO auth_rate_limits (rate_key, attempts, window_started_at)
         VALUES (:rate_key, 1, UTC_TIMESTAMP())
         ON DUPLICATE KEY UPDATE
            attempts = IF(window_started_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL 15 MINUTE),
                          1, attempts + 1),
            window_started_at = IF(window_started_at < DATE_SUB(UTC_TIMESTAMP(), INTERVAL 15 MINUTE),
                                   UTC_TIMESTAMP(), window_started_at)'
    );
    $record->execute([':rate_key' => $key]);
    $select = $pdo->prepare('SELECT attempts FROM auth_rate_limits WHERE rate_key = :rate_key');
    $select->execute([':rate_key' => $key]);
    if ((int)$select->fetchColumn() > $limit) {
        fail(429, 'Masyadong maraming request. Subukan ulit pagkalipas ng 15 minuto.');
    }
}

function sendVerificationCode(array $config, string $email, string $name, string $code): void
{
    if (!class_exists(\PHPMailer\PHPMailer\PHPMailer::class)) {
        throw new RuntimeException('Email service is not installed. Upload the vendor directory.');
    }
    $host = (string)($config['verification_smtp_host'] ?? '');
    $username = (string)($config['verification_smtp_username'] ?? '');
    $password = preg_replace('/\s+/', '', (string)($config['verification_smtp_password'] ?? '')) ?? '';
    $from = (string)($config['verification_from_email'] ?? $username);
    $port = (int)($config['verification_smtp_port'] ?? 587);
    $encryption = strtolower((string)($config['verification_smtp_encryption'] ?? 'tls'));
    if (strcasecmp($host, 'smtp.gmail.com') !== 0 ||
        $username === '' || $password === '' ||
        !filter_var($username, FILTER_VALIDATE_EMAIL) ||
        !filter_var($from, FILTER_VALIDATE_EMAIL) ||
        strcasecmp($from, $username) !== 0 ||
        !in_array($encryption, ['tls', 'ssl'], true) ||
        ($encryption === 'tls' && $port !== 587) ||
        ($encryption === 'ssl' && $port !== 465)) {
        throw new RuntimeException('Gmail SMTP settings are incomplete or invalid.');
    }

    $mailer = new \PHPMailer\PHPMailer\PHPMailer(true);
    try {
        $mailer->isSMTP();
        $mailer->Host = $host;
        $mailer->SMTPAuth = true;
        $mailer->Username = $username;
        $mailer->Password = $password;
        $mailer->Port = $port;
        $mailer->SMTPSecure = $encryption === 'tls'
            ? \PHPMailer\PHPMailer\PHPMailer::ENCRYPTION_STARTTLS
            : \PHPMailer\PHPMailer\PHPMailer::ENCRYPTION_SMTPS;
        $mailer->Timeout = 15;
        $mailer->CharSet = 'UTF-8';
        $mailer->setFrom($from, 'Spatibai');
        $mailer->addAddress($email, $name);
        $mailer->Subject = 'Verify your Spatibai account';
        $mailer->Body = "Hi {$name},\n\nYour Spatibai verification code is: {$code}\n\n" .
            "It expires in 15 minutes. If you did not request this, you can ignore this email.";
        $mailer->send();
    } catch (\PHPMailer\PHPMailer\Exception $exception) {
        throw new RuntimeException('Gmail SMTP could not send the verification email.', 0, $exception);
    }
}

function deliverVerificationCode(array $config, string $email, string $name, string $code): void
{
    $mode = (string)($config['verification_mode'] ?? 'mail');
    if ($mode !== 'mail') {
        throw new RuntimeException('Email verification must use mail delivery.');
    }
    sendVerificationCode($config, $email, $name, $code);
}

function verificationFailureMessage(Throwable $exception): string
{
    $reason = $exception->getMessage();
    if (str_contains($reason, 'Email service is not installed')) {
        return 'Walang email library sa server. I-upload ang buong vendor folder sa htdocs.';
    }
    if (str_contains($reason, 'Gmail SMTP settings are incomplete or invalid')) {
        return 'Mali o kulang ang Gmail settings sa htdocs/config.php. Tingnan ang Gmail address, App Password, host, port, at TLS.';
    }
    if (str_contains($reason, 'Gmail SMTP could not send')) {
        return 'Hindi makakonekta ang hosting sa Gmail. Tingnan ang App Password at kung pinapayagan ng hosting ang palabas na SMTP sa port 587.';
    }
    return 'Hindi maipadala ang verification email. Tingnan ang server error log o subukan ulit mamaya.';
}

function registerUser(PDO $pdo, array $config): never
{
    $body = authBody();
    checkAuthRate($pdo, 'register', 5);
    $name = trim(is_string($body['name'] ?? null) ? $body['name'] : '');
    $email = strtolower(trim(is_string($body['email'] ?? null) ? $body['email'] : ''));
    $password = $body['password'] ?? null;
    if ($name === '' || mb_strlen($name) > 80 ||
        !is_string($password) || strlen($password) < 8 || strlen($password) > 72 ||
        !filter_var($email, FILTER_VALIDATE_EMAIL) || strlen($email) > 254) {
        fail(400, 'Ilagay ang valid na pangalan at email. Dapat 8–72 characters ang password.');
    }

    $existing = $pdo->prepare('SELECT id FROM users WHERE email = :email');
    $existing->execute([':email' => $email]);
    if ($existing->fetch()) {
        fail(409, 'May account na gamit ang email na ito. Mag-log in na lang.');
    }

    $pendingQuery = $pdo->prepare(
        'SELECT requested_at FROM pending_registrations WHERE email = :email'
    );
    $pendingQuery->execute([':email' => $email]);
    $pending = $pendingQuery->fetch();
    if ($pending && strtotime((string)$pending['requested_at'] . ' UTC') > time() - 60) {
        fail(429, 'May verification code nang ipinadala. Hintayin muna nang isang minuto bago humingi ulit.');
    }

    $code = (string)random_int(100000, 999999);
    $verificationHash = password_hash($code, PASSWORD_DEFAULT);
    $insert = $pdo->prepare(
        'INSERT INTO pending_registrations
            (email, name, password_hash, verification_hash, expires_at, requested_at, attempts)
         VALUES
            (:email, :name, :password_hash, :verification_hash,
             DATE_ADD(UTC_TIMESTAMP(), INTERVAL 15 MINUTE), UTC_TIMESTAMP(), 0)
         ON DUPLICATE KEY UPDATE
            name = VALUES(name), password_hash = VALUES(password_hash),
            verification_hash = VALUES(verification_hash),
            expires_at = VALUES(expires_at), requested_at = VALUES(requested_at), attempts = 0'
    );
    $insert->execute([
        ':email' => $email,
        ':name' => $name,
        ':password_hash' => password_hash($password, PASSWORD_DEFAULT),
        ':verification_hash' => $verificationHash,
    ]);

    try {
        deliverVerificationCode($config, $email, $name, $code);
    } catch (Throwable $exception) {
        $cleanup = $pdo->prepare(
            'DELETE FROM pending_registrations
             WHERE email = :email AND verification_hash = :verification_hash'
        );
        $cleanup->execute([':email' => $email, ':verification_hash' => $verificationHash]);
        error_log('Spatibai verification email failed: ' . $exception->getMessage());
        fail(503, verificationFailureMessage($exception));
    }
    respond(200, ['message' => 'Naipadala ang verification code sa iyong email.']);
}

function resendVerification(PDO $pdo, array $config): never
{
    $body = authBody();
    checkAuthRate($pdo, 'resend', 5);
    $email = strtolower(trim(is_string($body['email'] ?? null) ? $body['email'] : ''));
    if (!filter_var($email, FILTER_VALIDATE_EMAIL) || strlen($email) > 254) {
        fail(400, 'Ilagay ang valid na email address.');
    }
    $query = $pdo->prepare(
        'SELECT name, requested_at FROM pending_registrations WHERE email = :email'
    );
    $query->execute([':email' => $email]);
    $pending = $query->fetch();
    if (!$pending) {
        fail(404, 'Walang nakabinbing registration para sa email na ito.');
    }
    if (strtotime((string)$pending['requested_at'] . ' UTC') > time() - 60) {
        fail(429, 'Maghintay muna nang isang minuto bago humingi ulit ng code.');
    }

    $code = (string)random_int(100000, 999999);
    $verificationHash = password_hash($code, PASSWORD_DEFAULT);
    $update = $pdo->prepare(
        'UPDATE pending_registrations
         SET verification_hash = :verification_hash,
             expires_at = DATE_ADD(UTC_TIMESTAMP(), INTERVAL 15 MINUTE),
             requested_at = UTC_TIMESTAMP(), attempts = 0
         WHERE email = :email'
    );
    $update->execute([':verification_hash' => $verificationHash, ':email' => $email]);
    try {
        deliverVerificationCode($config, $email, (string)$pending['name'], $code);
    } catch (Throwable $exception) {
        $cleanup = $pdo->prepare(
            'DELETE FROM pending_registrations
             WHERE email = :email AND verification_hash = :verification_hash'
        );
        $cleanup->execute([':email' => $email, ':verification_hash' => $verificationHash]);
        error_log('Spatibai verification email resend failed: ' . $exception->getMessage());
        fail(503, verificationFailureMessage($exception));
    }
    respond(200, ['message' => 'Naipadala ulit ang verification code.']);
}

function verifyRegistration(PDO $pdo): never
{
    $body = authBody();
    checkAuthRate($pdo, 'verify', 20);
    $email = strtolower(trim(is_string($body['email'] ?? null) ? $body['email'] : ''));
    $code = is_string($body['code'] ?? null) ? trim($body['code']) : '';
    if (!filter_var($email, FILTER_VALIDATE_EMAIL) || !preg_match('/^[0-9]{6}$/', $code)) {
        fail(400, 'Ilagay ang email at 6-digit verification code.');
    }

    $pdo->beginTransaction();
    try {
        $select = $pdo->prepare(
            'SELECT name, password_hash, verification_hash, attempts
             FROM pending_registrations
             WHERE email = :email AND expires_at > UTC_TIMESTAMP()
             FOR UPDATE'
        );
        $select->execute([':email' => $email]);
        $pending = $select->fetch();
        if (!$pending) {
            $pdo->rollBack();
            fail(400, 'Mali o expired na ang code. Mag-request ng bagong code.');
        }
        if ((int)$pending['attempts'] >= 5) {
            $delete = $pdo->prepare('DELETE FROM pending_registrations WHERE email = :email');
            $delete->execute([':email' => $email]);
            $pdo->commit();
            fail(429, 'Maraming maling code. Mag-register ulit para makatanggap ng bagong code.');
        }
        if (!password_verify($code, (string)$pending['verification_hash'])) {
            $update = $pdo->prepare(
                'UPDATE pending_registrations SET attempts = attempts + 1 WHERE email = :email'
            );
            $update->execute([':email' => $email]);
            $pdo->commit();
            fail(400, 'Mali ang verification code.');
        }

        $insert = $pdo->prepare(
            'INSERT INTO users (name, email, password_hash, email_verified_at)
             VALUES (:name, :email, :password_hash, UTC_TIMESTAMP())'
        );
        $insert->execute([
            ':name' => $pending['name'],
            ':email' => $email,
            ':password_hash' => $pending['password_hash'],
        ]);
        $delete = $pdo->prepare('DELETE FROM pending_registrations WHERE email = :email');
        $delete->execute([':email' => $email]);
        $pdo->commit();
    } catch (Throwable $exception) {
        if ($pdo->inTransaction()) $pdo->rollBack();
        throw $exception;
    }
    respond(200, ['message' => 'Verified na ang email. Maaari ka nang mag-log in.']);
}

function loginUser(PDO $pdo): never
{
    $body = authBody();
    checkAuthRate($pdo, 'login', 10);
    $email = strtolower(trim(is_string($body['email'] ?? null) ? $body['email'] : ''));
    $password = $body['password'] ?? null;
    if (!filter_var($email, FILTER_VALIDATE_EMAIL) ||
        !is_string($password) || strlen($password) > 72) {
        fail(400, 'Ilagay ang valid na email at password.');
    }
    $select = $pdo->prepare(
        'SELECT id, name, email, password_hash FROM users WHERE email = :email'
    );
    $select->execute([':email' => $email]);
    $user = $select->fetch();
    if (!$user || !password_verify($password, (string)$user['password_hash'])) {
        fail(401, 'Hindi tugma ang email o password. Siguraduhing verified na ang email.');
    }

    $token = bin2hex(random_bytes(32));
    $insert = $pdo->prepare(
        'INSERT INTO user_sessions (token_hash, user_id, expires_at)
         VALUES (:token_hash, :user_id, DATE_ADD(UTC_TIMESTAMP(), INTERVAL 30 DAY))'
    );
    $insert->execute([
        ':token_hash' => hash('sha256', $token),
        ':user_id' => $user['id'],
    ]);
    respond(200, [
        'token' => $token,
        'name' => $user['name'],
        'email' => $user['email'],
    ]);
}

function authenticatedUser(PDO $pdo): array
{
    $authorization = (string)($_SERVER['HTTP_AUTHORIZATION'] ??
        $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '');
    if ($authorization === '' && function_exists('getallheaders')) {
        $headers = getallheaders();
        foreach ($headers as $name => $value) {
            if (strcasecmp((string)$name, 'Authorization') === 0) {
                $authorization = (string)$value;
                break;
            }
        }
    }
    if (!preg_match('/^Bearer ([a-f0-9]{64})$/i', $authorization, $matches)) {
        fail(401, 'Mag-log in ulit para magpatuloy.');
    }
    $select = $pdo->prepare(
        'SELECT u.id, u.name, u.email, s.token_hash
         FROM user_sessions s JOIN users u ON u.id = s.user_id
         WHERE s.token_hash = :token_hash AND s.expires_at > UTC_TIMESTAMP()'
    );
    $select->execute([':token_hash' => hash('sha256', $matches[1])]);
    $user = $select->fetch();
    if (!$user) fail(401, 'Nag-expire na ang session. Mag-log in ulit.');
    return $user;
}

function getAuthSession(PDO $pdo): never
{
    $user = authenticatedUser($pdo);
    respond(200, ['name' => $user['name'], 'email' => $user['email']]);
}

function logoutUser(PDO $pdo): never
{
    $user = authenticatedUser($pdo);
    $delete = $pdo->prepare('DELETE FROM user_sessions WHERE token_hash = :token_hash');
    $delete->execute([':token_hash' => $user['token_hash']]);
    respond(200, ['ok' => true]);
}

function publicTrack(array $track): array
{
    return [
        'id' => $track['id'],
        'title' => $track['title'],
        'artist' => $track['artist'],
        'mimeType' => $track['mime_type'],
        'size' => (int)$track['file_size'],
        'uploadedAt' => gmdate('c', strtotime($track['uploaded_at'] . ' UTC')),
    ];
}

function allTracks(PDO $pdo): array
{
    $rows = $pdo->query(
        'SELECT id, title, artist, filename, mime_type, file_size, uploaded_at
         FROM tracks ORDER BY uploaded_at DESC, id DESC'
    )->fetchAll();
    return array_map('publicTrack', $rows);
}

function audioMime(string $extension): ?string
{
    return [
        'aac' => 'audio/aac',
        'flac' => 'audio/flac',
        'm4a' => 'audio/mp4',
        'mp3' => 'audio/mpeg',
        'ogg' => 'audio/ogg',
        'opus' => 'audio/ogg',
        'wav' => 'audio/wav',
        'weba' => 'audio/webm',
    ][$extension] ?? null;
}

function uploadTrack(PDO $pdo, array $config): never
{
    requireAdmin();
    requireSameOrigin();
    if (!isset($_FILES['audio']) || !is_array($_FILES['audio'])) {
        if ((int)($_SERVER['CONTENT_LENGTH'] ?? 0) > 0) {
            fail(413, 'The upload exceeds this hosting account’s PHP limits. Try a smaller audio file.');
        }
        fail(400, 'Choose an audio file to upload.');
    }

    $upload = $_FILES['audio'];
    if ((int)$upload['error'] !== UPLOAD_ERR_OK) {
        $messages = [
            UPLOAD_ERR_INI_SIZE => 'File exceeds the hosting PHP upload_max_filesize limit.',
            UPLOAD_ERR_FORM_SIZE => 'File exceeds the upload form limit.',
            UPLOAD_ERR_PARTIAL => 'Upload was interrupted. Try again.',
            UPLOAD_ERR_NO_FILE => 'Choose an audio file to upload.',
            UPLOAD_ERR_NO_TMP_DIR => 'The hosting server has no temporary upload directory.',
            UPLOAD_ERR_CANT_WRITE => 'The hosting server could not save the upload.',
            UPLOAD_ERR_EXTENSION => 'A hosting PHP extension blocked the upload.',
        ];
        fail(400, $messages[(int)$upload['error']] ?? 'Audio upload failed.');
    }

    $maxBytes = (int)($config['max_upload_bytes'] ?? 104857600);
    $fileSize = (int)$upload['size'];
    if ($fileSize < 1 || $fileSize > $maxBytes) {
        fail(413, 'Audio files must be between 1 byte and the configured upload limit.');
    }

    $originalName = (string)($upload['name'] ?? '');
    $extension = strtolower(pathinfo($originalName, PATHINFO_EXTENSION));
    $mimeType = audioMime($extension);
    if ($mimeType === null || !is_uploaded_file((string)$upload['tmp_name'])) {
        fail(415, 'Use an MP3, M4A, AAC, WAV, OGG, OPUS, FLAC, or WEBM audio file.');
    }

    $title = trim((string)($_POST['title'] ?? ''));
    $artist = trim((string)($_POST['artist'] ?? ''));
    if ($title === '' || mb_strlen($title) > 120 || mb_strlen($artist) > 120) {
        fail(400, 'Add a title (up to 120 characters) and an artist up to 120 characters.');
    }
    if ($artist === '') {
        $artist = 'Spatibai artist';
    }

    $directory = (string)($config['audio_directory'] ?? (__DIR__ . '/storage/audio'));
    if (!is_dir($directory) && !mkdir($directory, 0750, true) && !is_dir($directory)) {
        fail(500, 'Could not create the audio storage directory.');
    }
    if (!is_writable($directory)) {
        fail(500, 'The audio storage directory is not writable by PHP.');
    }

    $id = sprintf(
        '%08x-%04x-%04x-%04x-%012x',
        random_int(0, 0xffffffff),
        random_int(0, 0xffff),
        random_int(0, 0x0fff) | 0x4000,
        random_int(0, 0x3fff) | 0x8000,
        random_int(0, 0xffffffffffff)
    );
    $filename = $id . '.' . $extension;
    $destination = rtrim($directory, DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR . $filename;
    if (!move_uploaded_file((string)$upload['tmp_name'], $destination)) {
        fail(500, 'Could not save this audio file. Check hosting storage permissions.');
    }

    try {
        $insert = $pdo->prepare(
            'INSERT INTO tracks (id, title, artist, filename, mime_type, file_size)
             VALUES (:id, :title, :artist, :filename, :mime_type, :file_size)'
        );
        $insert->execute([
            ':id' => $id,
            ':title' => $title,
            ':artist' => $artist,
            ':filename' => $filename,
            ':mime_type' => $mimeType,
            ':file_size' => $fileSize,
        ]);
    } catch (Throwable $exception) {
        if (!unlink($destination)) {
            error_log('Spatibai could not remove an upload after a database error.');
        }
        error_log('Spatibai upload database insert failed: ' . $exception->getMessage());
        fail(500, 'Could not add this track to the music catalog.');
    }

    $select = $pdo->prepare('SELECT * FROM tracks WHERE id = :id');
    $select->execute([':id' => $id]);
    respond(201, ['track' => publicTrack($select->fetch())]);
}

function deleteTrack(PDO $pdo, array $config, string $id): never
{
    requireAdmin();
    requireSameOrigin();
    $select = $pdo->prepare('SELECT filename FROM tracks WHERE id = :id');
    $select->execute([':id' => $id]);
    $track = $select->fetch();
    if (!$track) {
        fail(404, 'Track not found.');
    }

    $directory = (string)($config['audio_directory'] ?? (__DIR__ . '/storage/audio'));
    $filename = basename((string)$track['filename']);
    $path = rtrim($directory, DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR . $filename;
    $delete = $pdo->prepare('DELETE FROM tracks WHERE id = :id');
    $delete->execute([':id' => $id]);
    if (is_file($path) && !unlink($path)) {
        error_log('Spatibai could not remove an audio file: ' . $filename);
        fail(500, 'The track was removed from the catalog, but its file could not be deleted.');
    }
    respond(200, ['ok' => true]);
}

function streamTrack(PDO $pdo, array $config, string $id): never
{
    $select = $pdo->prepare('SELECT filename, mime_type, file_size FROM tracks WHERE id = :id');
    $select->execute([':id' => $id]);
    $track = $select->fetch();
    if (!$track) {
        http_response_code(404);
        exit('Audio not found');
    }
    $directory = (string)($config['audio_directory'] ?? (__DIR__ . '/storage/audio'));
    $path = rtrim($directory, DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR .
        basename((string)$track['filename']);
    if (!is_file($path) || !is_readable($path)) {
        http_response_code(404);
        exit('Audio file not found');
    }
    $size = filesize($path);
    if ($size === false || $size < 1) {
        http_response_code(500);
        exit('Audio file unavailable');
    }

    header('Content-Type: ' . $track['mime_type']);
    header('Accept-Ranges: bytes');
    header('X-Content-Type-Options: nosniff');
    header('Cache-Control: public, max-age=3600');
    header('Content-Disposition: inline');

    $start = 0;
    $end = $size - 1;
    $range = $_SERVER['HTTP_RANGE'] ?? '';
    if ($range !== '') {
        if (!preg_match('/^bytes=(\d*)-(\d*)$/', $range, $matches) ||
            ($matches[1] === '' && $matches[2] === '')) {
            http_response_code(416);
            header('Content-Range: bytes */' . $size);
            exit;
        }
        if ($matches[1] === '') {
            $suffix = (int)$matches[2];
            if ($suffix < 1) {
                http_response_code(416);
                header('Content-Range: bytes */' . $size);
                exit;
            }
            $start = max(0, $size - $suffix);
        } else {
            $start = (int)$matches[1];
            if ($matches[2] !== '') {
                $end = min((int)$matches[2], $size - 1);
            }
        }
        if ($start >= $size || $start > $end) {
            http_response_code(416);
            header('Content-Range: bytes */' . $size);
            exit;
        }
        http_response_code(206);
        header("Content-Range: bytes {$start}-{$end}/{$size}");
    }

    $length = $end - $start + 1;
    header('Content-Length: ' . $length);
    if ($_SERVER['REQUEST_METHOD'] === 'HEAD') {
        exit;
    }

    $handle = fopen($path, 'rb');
    if ($handle === false || fseek($handle, $start) !== 0) {
        if (is_resource($handle)) {
            fclose($handle);
        }
        http_response_code(500);
        exit('Could not read audio file');
    }
    while ($length > 0 && !feof($handle)) {
        $chunk = fread($handle, min(65536, $length));
        if ($chunk === false || $chunk === '') {
            break;
        }
        echo $chunk;
        $length -= strlen($chunk);
        flush();
        if (connection_aborted()) {
            break;
        }
    }
    fclose($handle);
    exit;
}

$config = loadConfig();
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';
$route = $_GET['route'] ?? '';

try {
    $pdo = db($config);
    if ($method === 'POST' && $route === 'auth-register') {
        registerUser($pdo, $config);
    }
    if ($method === 'POST' && $route === 'auth-resend') {
        resendVerification($pdo, $config);
    }
    if ($method === 'POST' && $route === 'auth-verify') {
        verifyRegistration($pdo);
    }
    if ($method === 'POST' && $route === 'auth-login') {
        loginUser($pdo);
    }
    if ($method === 'GET' && $route === 'auth-session') {
        getAuthSession($pdo);
    }
    if ($method === 'POST' && $route === 'auth-logout') {
        logoutUser($pdo);
    }
    if ($method === 'GET' && $route === 'tracks') {
        respond(200, ['tracks' => allTracks($pdo)]);
    }

    if ($method === 'GET' && $route === 'admin-session') {
        startAdminSession();
        respond(200, ['authenticated' => !empty($_SESSION['admin_authenticated'])]);
    }

    if ($method === 'POST' && $route === 'admin-login') {
        requireSameOrigin();
        startAdminSession();
        $now = time();
        if (!isset($_SESSION['login_window']) || $now - (int)$_SESSION['login_window'] > 900) {
            $_SESSION['login_window'] = $now;
            $_SESSION['login_attempts'] = 0;
        }
        if ((int)($_SESSION['login_attempts'] ?? 0) >= 10) {
            fail(429, 'Too many attempts. Try again in 15 minutes.');
        }
        $body = json_decode((string)file_get_contents('php://input'), true);
        $password = is_array($body) ? ($body['password'] ?? null) : null;
        if (!is_string($password) || strlen($password) > 256 ||
            !password_verify($password, (string)$config['admin_password_hash'])) {
            $_SESSION['login_attempts'] = (int)($_SESSION['login_attempts'] ?? 0) + 1;
            fail(401, 'Incorrect admin password.');
        }
        session_regenerate_id(true);
        $_SESSION['admin_authenticated'] = true;
        $_SESSION['login_attempts'] = 0;
        respond(200, ['authenticated' => true]);
    }

    if ($method === 'POST' && $route === 'admin-logout') {
        requireSameOrigin();
        startAdminSession();
        $_SESSION = [];
        if (ini_get('session.use_cookies')) {
            $params = session_get_cookie_params();
            setcookie(session_name(), '', [
                'expires' => time() - 42000,
                'path' => $params['path'],
                'domain' => $params['domain'],
                'secure' => $params['secure'],
                'httponly' => $params['httponly'],
                'samesite' => 'Strict',
            ]);
        }
        session_destroy();
        respond(200, ['authenticated' => false]);
    }

    if ($method === 'POST' && $route === 'admin-tracks') {
        uploadTrack($pdo, $config);
    }

    if ($method === 'DELETE' && $route === 'admin-track') {
        $id = (string)($_GET['id'] ?? '');
        if (!preg_match('/^[a-fA-F0-9-]{36}$/', $id)) {
            fail(404, 'Track not found.');
        }
        deleteTrack($pdo, $config, $id);
    }

    if (($method === 'GET' || $method === 'HEAD') && $route === 'media') {
        $id = (string)($_GET['id'] ?? '');
        if (!preg_match('/^[a-fA-F0-9-]{36}$/', $id)) {
            http_response_code(404);
            exit('Audio not found');
        }
        streamTrack($pdo, $config, $id);
    }

    fail(404, 'Not found.');
} catch (PDOException $exception) {
    error_log('Spatibai database request failed: ' . $exception->getMessage());
    fail(500, 'Could not connect to the music database. Check the hosting database settings.');
} catch (Throwable $exception) {
    error_log('Spatibai API request failed: ' . $exception->getMessage());
    fail(500, 'An unexpected server error occurred.');
}
