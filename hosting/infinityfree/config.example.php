<?php
declare(strict_types=1);

return [
    'db_host' => 'REPLACE_WITH_INFINITYFREE_MYSQL_HOST',
    'db_name' => 'REPLACE_WITH_INFINITYFREE_DATABASE_NAME',
    'db_user' => 'REPLACE_WITH_INFINITYFREE_DATABASE_USERNAME',
    'db_password' => 'REPLACE_WITH_INFINITYFREE_DATABASE_PASSWORD',
    'admin_password_hash' => 'REPLACE_WITH_PASSWORD_HASH',
    'verification_mode' => 'mail',
    'verification_smtp_host' => 'smtp.gmail.com',
    'verification_smtp_port' => 587,
    'verification_smtp_encryption' => 'tls',
    'verification_smtp_username' => 'REPLACE_WITH_YOUR_GMAIL_ADDRESS',
    'verification_smtp_password' => 'REPLACE_WITH_GMAIL_APP_PASSWORD',
    'verification_from_email' => 'REPLACE_WITH_YOUR_GMAIL_ADDRESS',
    'audio_directory' => __DIR__ . '/storage/audio',
    'max_upload_bytes' => 100 * 1024 * 1024,
];
