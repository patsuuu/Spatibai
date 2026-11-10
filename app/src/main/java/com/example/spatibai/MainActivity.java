package com.example.spatibai;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String PREFS = "spatibai_account";
    private static final int BACKGROUND = Color.rgb(12, 17, 15);
    private static final int SURFACE = Color.rgb(27, 35, 31);
    private static final int MUTED = Color.rgb(164, 177, 168);
    private static final int GREEN = Color.rgb(29, 215, 96);
    private static final String DEFAULT_SERVER_URL = "https://spatibai.onrender.com";
    private SharedPreferences preferences;
    private final ArrayList<Track> tracks = new ArrayList<>();
    private final ArrayList<Track> onlineTracks = new ArrayList<>();
    private MediaPlayer mediaPlayer;
    private ProgressBar miniProgressBar;
    private int currentTrack = -1;
    private boolean isPlaying = false;
    private boolean playerPrepared = false;
    private boolean offlineOnly = false;
    private String activeTab = "Home";
    private String homeSearchQuery = "";
    private ActivityResultLauncher<Intent> audioPicker;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService authExecutor = Executors.newSingleThreadExecutor();
    private final Runnable miniProgressRunnable = new Runnable() {
        @Override public void run() {
            MediaPlayer player = mediaPlayer;
            if (player == null) return;
            if (player.isPlaying()) {
                updateMiniProgress();
            }
            mainHandler.postDelayed(this, 500);
        }
    };
    private String onlineStatus = "Kinukuha ang online music…";
    private boolean syncInProgress;
    private WebView webView;
    private int webViewChecks;
    private final Runnable syncRunnable = new Runnable() {
        @Override public void run() {
            if (preferences != null && preferences.getBoolean("logged_in", false)) {
                refreshOnlineTracks(false);
            }
            mainHandler.postDelayed(this, 30_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        restoreTracks();
        restoreOnlineTracks();
        audioPicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        importSelectedAudio(result.getData());
                    }
                });
        String authToken = preferences.getString("auth_token", "");
        if (authToken.isEmpty()) {
            preferences.edit().remove("logged_in").remove("password_hash").apply();
            showAuth(false);
        } else {
            preferences.edit().putBoolean("logged_in", false).apply();
            showAuth(false);
            requestAuth("auth-session", null, authToken, (response, error) -> {
                if (error != null) {
                    preferences.edit().remove("auth_token").remove("logged_in").apply();
                    showAuth(false);
                    toast(error);
                    return;
                }
                preferences.edit()
                        .putString("name", response.optString("name", "Spatibai user"))
                        .putString("email", response.optString("email", ""))
                        .putBoolean("logged_in", true)
                        .apply();
                showHome();
                refreshOnlineTracks(false);
                mainHandler.removeCallbacks(syncRunnable);
                mainHandler.postDelayed(syncRunnable, 30_000);
            });
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mainHandler.removeCallbacks(syncRunnable);
        if (preferences.getBoolean("logged_in", false)) {
            refreshOnlineTracks(false);
            mainHandler.postDelayed(syncRunnable, 30_000);
        }
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(syncRunnable);
        super.onPause();
    }

    private void showAuth(boolean register) {
        LinearLayout page = page();
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(28), dp(28), dp(28), dp(24));

        TextView logo = text("♫", 45, GREEN, true);
        logo.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(82), dp(82));
        logoParams.topMargin = dp(48);
        logo.setBackground(round(GREEN, 24));
        logo.setTextColor(BACKGROUND);
        page.addView(logo, logoParams);

        TextView title = text("Spatibai", 32, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = wrap();
        titleParams.topMargin = dp(18);
        page.addView(title, titleParams);

        TextView subtitle = text(register ? "Gumawa ng account at simulan ang iyong soundtrack." :
                "Ang tunog ng bawat sandali mo.", 15, MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = dp(8);
        subtitleParams.bottomMargin = dp(30);
        page.addView(subtitle, subtitleParams);

        TextView serverStatus = null;
        if (register) {
            serverStatus = text("Sinusuri ang koneksyon sa API…", 13, MUTED, false);
            serverStatus.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            statusParams.bottomMargin = dp(16);
            page.addView(serverStatus, statusParams);
        }

        EditText nameInput = null;
        if (register) {
            nameInput = input("Pangalan", false);
            page.addView(nameInput, fieldParams());
        }
        EditText emailInput = input("Email address", false);
        emailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        page.addView(emailInput, fieldParams());
        EditText passwordInput = input("Password", true);
        page.addView(passwordInput, fieldParams());

        EditText finalNameInput = nameInput;
        Button submit = button(register ? "Gumawa ng account" : "Mag-log in", true);
        LinearLayout.LayoutParams submitParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        submitParams.topMargin = dp(12);
        page.addView(submit, submitParams);
        submit.setOnClickListener(v -> {
            String email = emailInput.getText().toString().trim().toLowerCase(Locale.ROOT);
            String password = passwordInput.getText().toString();
            if (register) {
                String name = finalNameInput.getText().toString().trim();
                if (name.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
                        || password.length() < 8 || password.length() > 72) {
                    toast("Ilagay ang pangalan at valid na email. Dapat 8–72 characters ang password.");
                    return;
                }
                try {
                    JSONObject body = new JSONObject()
                            .put("name", name)
                            .put("email", email)
                            .put("password", password);
                    submit.setEnabled(false);
                    requestAuth("auth-register", body, null, (response, error) -> {
                        submit.setEnabled(true);
                        if (error != null) {
                            toast(error);
                            return;
                        }
                        showVerifyEmail(email);
                    });
                } catch (JSONException exception) {
                    toast("Hindi naihanda ang registration. Subukan ulit.");
                }
            } else {
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches() || password.isEmpty()) {
                    toast("Ilagay ang iyong email at password.");
                    return;
                }
                try {
                    JSONObject body = new JSONObject()
                            .put("email", email)
                            .put("password", password);
                    submit.setEnabled(false);
                    requestAuth("auth-login", body, null, (response, error) -> {
                        submit.setEnabled(true);
                        if (error != null) {
                            toast(error);
                            return;
                        }
                        String token = response.optString("token", "");
                        if (!token.matches("[a-f0-9]{64}")) {
                            toast("Hindi kumpleto ang tugon ng server. Subukan ulit.");
                            return;
                        }
                        preferences.edit()
                                .putString("name", response.optString("name", "Spatibai user"))
                                .putString("email", response.optString("email", email))
                                .putString("auth_token", token)
                                .putBoolean("logged_in", true)
                                .apply();
                        showHome();
                        refreshOnlineTracks(false);
                        mainHandler.removeCallbacks(syncRunnable);
                        mainHandler.postDelayed(syncRunnable, 30_000);
                    });
                } catch (JSONException exception) {
                    toast("Hindi naihanda ang pag-log in. Subukan ulit.");
                }
            }
        });

        TextView switchMode = text(register
                ? "May account ka na? Mag-log in"
                : "Bago ka rito? Gumawa ng account", 14, Color.WHITE, true);
        switchMode.setGravity(Gravity.CENTER);
        switchMode.setPadding(0, dp(22), 0, dp(18));
        page.addView(switchMode, matchWrap());
        switchMode.setOnClickListener(v -> showAuth(!register));

        TextView note = text("Music para sa lahat ng mood.", 13, MUTED, false);
        note.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteParams = wrap();
        noteParams.topMargin = dp(18);
        page.addView(note, noteParams);
        ScrollView authScroll = new ScrollView(this);
        authScroll.setFillViewport(true);
        authScroll.setVerticalScrollBarEnabled(false);
        authScroll.addView(page);
        setContentView(authScroll);
        if (register) {
            checkServerApi(serverStatus);
        }
    }

    private void showVerifyEmail(String email) {
        LinearLayout page = page();
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(28), dp(28), dp(28), dp(24));

        TextView title = text("I-verify ang email", 26, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = wrap();
        titleParams.topMargin = dp(64);
        page.addView(title, titleParams);

        TextView instruction = text("Nagpadala kami ng 6-digit code sa\n" + email,
                15, MUTED, false);
        instruction.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams instructionParams = matchWrap();
        instructionParams.topMargin = dp(12);
        instructionParams.bottomMargin = dp(28);
        page.addView(instruction, instructionParams);

        EditText codeInput = input("Verification code", false);
        codeInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeInput.setMaxLines(1);
        page.addView(codeInput, fieldParams());

        Button verify = button("I-verify at magpatuloy", true);
        LinearLayout.LayoutParams verifyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        verifyParams.topMargin = dp(12);
        page.addView(verify, verifyParams);
        verify.setOnClickListener(v -> {
            String code = codeInput.getText().toString().trim();
            if (!code.matches("[0-9]{6}")) {
                toast("Ilagay ang 6-digit code mula sa email.");
                return;
            }
            try {
                JSONObject body = new JSONObject().put("email", email).put("code", code);
                verify.setEnabled(false);
                requestAuth("auth-verify", body, null, (response, error) -> {
                    verify.setEnabled(true);
                    if (error != null) {
                        toast(error);
                        return;
                    }
                    toast("Verified na ang email. Mag-log in para magpatuloy.");
                    showAuth(false);
                });
            } catch (JSONException exception) {
                toast("Hindi naihanda ang verification. Subukan ulit.");
            }
        });

        TextView resend = text("Muling magpadala ng code", 14, GREEN, true);
        resend.setGravity(Gravity.CENTER);
        resend.setPadding(0, dp(22), 0, dp(18));
        page.addView(resend, matchWrap());
        resend.setOnClickListener(v -> {
            try {
                JSONObject body = new JSONObject().put("email", email);
                requestAuth("auth-resend", body, null, (response, error) -> {
                    if (error != null) toast(error);
                    else toast("Naipadala ulit ang verification code sa iyong email.");
                });
            } catch (JSONException exception) {
                toast("Hindi naihanda ang request. Subukan ulit.");
            }
        });

        TextView back = text("Bumalik sa registration", 14, Color.WHITE, true);
        back.setGravity(Gravity.CENTER);
        page.addView(back, matchWrap());
        back.setOnClickListener(v -> showAuth(true));
        setContentView(page);
    }

    private interface AuthCallback {
        void onResult(JSONObject response, String error);
    }

    private void requestAuth(String route, JSONObject body, String token, AuthCallback callback) {
        authExecutor.execute(() -> {
            JSONObject response = new JSONObject();
            String error = null;
            try {
                ApiResponse apiResponse = sendApiRequest(route, body, token);
                response = apiResponse.body;
                if (apiResponse.status < 200 || apiResponse.status >= 300) {
                    error = response.optString("error", "Hindi naisagawa ang request. Subukan ulit.");
                }
            } catch (IOException | JSONException exception) {
                android.util.Log.w("Spatibai", "Auth API request failed.", exception);
                error = exception instanceof JSONException
                        ? "Hindi JSON ang tugon ng server. Tingnan ang Render API configuration."
                        : "Hindi maabot ang Render API. Tingnan ang internet connection at Render service.";
            }
            JSONObject result = response;
            String resultError = error;
            mainHandler.post(() -> callback.onResult(result, resultError));
        });
    }

    private static final class ApiResponse {
        final int status;
        final JSONObject body;

        ApiResponse(int status, JSONObject body) {
            this.status = status;
            this.body = body;
        }
    }

    private ApiResponse sendApiRequest(String route, JSONObject body, String token)
            throws IOException, JSONException {
        boolean getRequest = body == null &&
                ("tracks".equals(route) || "auth-session".equals(route));
        String endpoint = serverBaseUrl() + "/api.php?route=" +
                java.net.URLEncoder.encode(route, StandardCharsets.UTF_8);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod(getRequest ? "GET" : "POST");
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (!getRequest) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                byte[] requestBody = (body == null ? new JSONObject() : body)
                        .toString().getBytes(StandardCharsets.UTF_8);
                try (java.io.OutputStream output = connection.getOutputStream()) {
                    output.write(requestBody);
                }
            }
            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            int status = connection.getResponseCode();
            InputStream responseStream = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            if (responseStream == null) throw new IOException("Empty API response.");
            String responseBody;
            try (InputStream input = responseStream;
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 65_536) throw new IOException("API response is too large.");
                    output.write(buffer, 0, count);
                }
                responseBody = output.toString(StandardCharsets.UTF_8.name());
            }
            try {
                return new ApiResponse(status, new JSONObject(responseBody));
            } catch (JSONException exception) {
                throw new IOException("The server returned a non-JSON API response.", exception);
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void checkServerApi(TextView statusView) {
        authExecutor.execute(() -> {
            String statusText;
            int statusColor;
            try {
                ApiResponse response = sendApiRequest("tracks", null, null);
                if (response.status >= 200 && response.status < 300 &&
                        response.body.optJSONArray("tracks") != null) {
                    statusText = "Handa na ang Render API. Maaari ka nang mag-register.";
                    statusColor = GREEN;
                } else {
                    statusText = "Hindi makuha ang music API. Suriin ang Render Logs.";
                    statusColor = MUTED;
                }
            } catch (IOException | JSONException exception) {
                android.util.Log.w("Spatibai", "Render API readiness check failed.", exception);
                statusText = exception.getMessage() != null &&
                        exception.getMessage().contains("non-JSON")
                        ? "HTML ang sagot ng server, hindi JSON. Suriin ang Render URL/configuration."
                        : "Hindi maabot ang Render API. Suriin ang internet at Render service.";
                statusColor = MUTED;
            }
            String message = statusText;
            int color = statusColor;
            mainHandler.post(() -> {
                statusView.setText(message);
                statusView.setTextColor(color);
            });
        });
    }

    private void ensureWebView() {
        if (webView != null) return;
        webView = new WebView(this);
        webView.setAlpha(0f);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        CookieManager.getInstance().setAcceptCookie(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (syncInProgress) {
                    mainHandler.postDelayed(() -> inspectCatalogPage(view), 1500);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    if (syncInProgress) {
                        failOnlineSync("Hindi mabuksan ang music server. Tingnan ang internet o server URL.");
                    }
                }
            }
        });
        addContentView(webView, new ViewGroup.LayoutParams(1, 1));
    }

    private void showHome() {
        LinearLayout root = page();
        root.setPadding(0, 0, 0, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(14));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView brand = text("♫  Spatibai", 22, GREEN, true);
        LinearLayout.LayoutParams brandParams = wrap();
        brandParams.bottomMargin = dp(14);
        content.addView(brand, brandParams);

        LinearLayout filters = row();
        filters.setPadding(0, dp(16), 0, dp(20));
        TextView allFilter = chip("Lahat", !offlineOnly);
        TextView offlineFilter = chip("Offline music", offlineOnly);
        filters.addView(allFilter);
        filters.addView(offlineFilter);
        allFilter.setOnClickListener(v -> {
            offlineOnly = false;
            showHome();
        });
        offlineFilter.setOnClickListener(v -> {
            offlineOnly = true;
            showHome();
        });
        View filterSpacer = new View(this);
        filters.addView(filterSpacer, new LinearLayout.LayoutParams(0, 1, 1));
        ImageView profile = new ImageView(this);
        profile.setImageResource(R.drawable.ic_profile);
        profile.setColorFilter(BACKGROUND);
        profile.setContentDescription("Profile");
        profile.setBackground(round(GREEN, dp(24)));
        profile.setPadding(dp(10), dp(10), dp(10), dp(10));
        profile.setScaleType(ImageView.ScaleType.FIT_CENTER);
        profile.setClickable(true);
        profile.setFocusable(true);
        filters.addView(profile, new LinearLayout.LayoutParams(dp(40), dp(40)));
        profile.setOnClickListener(v -> showAccount());
        content.addView(filters);
        TextView syncLabel = text(onlineStatus, 12, MUTED, false);
        LinearLayout.LayoutParams syncParams = wrap();
        syncParams.bottomMargin = dp(14);
        content.addView(syncLabel, syncParams);
        syncLabel.setOnClickListener(v -> refreshOnlineTracks(true));

        EditText search = input("⌕  Mga kanta o artist", false);
        search.setSingleLine(true);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setText(homeSearchQuery);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        searchParams.bottomMargin = dp(24);
        content.addView(search, searchParams);

        TextView quickTitle = text("Mga kanta mo", 20, Color.WHITE, true);
        LinearLayout.LayoutParams sectionTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        sectionTitleParams.bottomMargin = dp(12);
        content.addView(quickTitle, sectionTitleParams);
        LinearLayout trackList = new LinearLayout(this);
        trackList.setOrientation(LinearLayout.VERTICAL);
        content.addView(trackList, matchWrap());
        populateHomeTracks(trackList, homeSearchQuery);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                homeSearchQuery = s.toString();
                populateHomeTracks(trackList, homeSearchQuery);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        addMiniPlayer(root);
        addBottomNav(root);
        setContentView(root);
    }

    private void showAccount() {
        LinearLayout root = page();
        root.setPadding(0, dp(20), 0, 0);

        TextView title = text("Profile", 28, Color.WHITE, true);
        title.setPadding(dp(20), 0, dp(20), 0);
        root.addView(title);
        TextView subtitle = text("Impormasyon ng account mo.", 14, MUTED, false);
        subtitle.setPadding(dp(20), dp(4), dp(20), 0);
        root.addView(subtitle);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(22), dp(20), dp(20));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView accountLabel = text("ACCOUNT", 12, MUTED, true);
        LinearLayout.LayoutParams labelParams = wrap();
        labelParams.bottomMargin = dp(10);
        content.addView(accountLabel, labelParams);

        LinearLayout profileCard = new LinearLayout(this);
        profileCard.setOrientation(LinearLayout.HORIZONTAL);
        profileCard.setGravity(Gravity.CENTER_VERTICAL);
        profileCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        profileCard.setBackground(round(SURFACE, 16));
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.ic_profile);
        avatar.setColorFilter(GREEN);
        avatar.setContentDescription("Profile picture");
        avatar.setBackground(round(Color.rgb(38, 59, 45), dp(32)));
        avatar.setPadding(dp(16), dp(16), dp(16), dp(16));
        avatar.setScaleType(ImageView.ScaleType.FIT_CENTER);
        profileCard.addView(avatar, new LinearLayout.LayoutParams(dp(64), dp(64)));

        LinearLayout accountDetails = new LinearLayout(this);
        accountDetails.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams accountDetailsParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        accountDetailsParams.leftMargin = dp(16);
        profileCard.addView(accountDetails, accountDetailsParams);
        String accountName = preferences.getString("name", "Spatibai user").trim();
        if (accountName.isEmpty()) accountName = "Spatibai user";
        accountDetails.addView(text(accountName, 20, Color.WHITE, true));
        TextView email = text(preferences.getString("email", ""), 14, MUTED, false);
        email.setSingleLine(true);
        email.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams emailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        emailParams.topMargin = dp(6);
        accountDetails.addView(email, emailParams);
        content.addView(profileCard, matchWrap());

        TextView logoutHint = text("Mag-log out sa account na ito sa device.", 13, MUTED, false);
        LinearLayout.LayoutParams logoutHintParams = wrap();
        logoutHintParams.topMargin = dp(24);
        logoutHintParams.bottomMargin = dp(10);
        content.addView(logoutHint, logoutHintParams);
        Button logout = button("Mag-log out", false);
        int danger = Color.rgb(235, 105, 105);
        logout.setTextColor(danger);
        GradientDrawable logoutBackground = round(SURFACE, 14);
        logoutBackground.setStroke(dp(1), Color.rgb(105, 53, 53));
        logout.setBackground(logoutBackground);
        logout.setElevation(0);
        LinearLayout.LayoutParams logoutParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        content.addView(logout, logoutParams);
        logout.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Mag-log out?")
                .setMessage("Maaari kang mag-log in ulit gamit ang account na ito.")
                .setNegativeButton("Kanselahin", null)
                .setPositiveButton("Mag-log out", (dialog, which) -> {
                    mainHandler.removeCallbacks(syncRunnable);
                    String token = preferences.getString("auth_token", "");
                    if (!token.isEmpty()) requestAuth("auth-logout", new JSONObject(), token,
                            (response, error) -> {
                                if (error != null) {
                                    toast("Na-log out ka sa device, pero hindi na-revoke ang session sa server.");
                                }
                            });
                    releasePlayer();
                    preferences.edit().remove("auth_token").putBoolean("logged_in", false).apply();
                    activeTab = "Home";
                    showAuth(false);
                })
                .show());

        addMiniPlayer(root);
        addBottomNav(root);
        setContentView(root);
    }

    private void showLibrary() {
        LinearLayout root = page();
        root.setPadding(0, dp(20), 0, 0);
        TextView title = text("Ang library mo", 27, Color.WHITE, true);
        title.setPadding(dp(20), 0, dp(20), 0);
        root.addView(title);
        TextView intro = text("Mga kantang handa para sa susunod mong mood.", 14, MUTED, false);
        intro.setPadding(dp(20), 0, dp(20), 0);
        LinearLayout.LayoutParams introParams = wrap();
        introParams.topMargin = dp(8);
        introParams.bottomMargin = dp(16);
        root.addView(intro, introParams);
        ArrayList<Track> library = allTracks();
        ArrayList<Integer> visibleTracks = new ArrayList<>();
        for (int i = 0; i < library.size(); i++) visibleTracks.add(i);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), 0, dp(20), dp(12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (visibleTracks.isEmpty()) {
            TextView empty = text("Wala pang kanta sa library. Lalabas dito ang mga kantang available mula sa online catalog.", 14, MUTED, false);
            empty.setPadding(dp(16), dp(18), dp(16), dp(18));
            empty.setBackground(round(SURFACE, 12));
            content.addView(empty, matchWrap());
        } else {
            for (int index : visibleTracks) content.addView(songRow(index));
        }
        addMiniPlayer(root);
        addBottomNav(root);
        setContentView(root);
    }

    private void addBottomNav(LinearLayout root) {
        LinearLayout nav = row();
        nav.setGravity(Gravity.CENTER);
        nav.setBackgroundColor(SURFACE);
        nav.setPadding(0, dp(4), 0, dp(4));
        String[] tabs = {"⌂\nHome", "▤\nLibrary", "⚙\nSettings"};
        for (String tab : tabs) {
            String label = tab.substring(tab.indexOf('\n') + 1);
            TextView item = text(tab, 12, label.equals(activeTab) ? GREEN : MUTED, true);
            item.setGravity(Gravity.CENTER);
            if (label.equals(activeTab)) item.setBackground(round(Color.rgb(38, 59, 45), 12));
            item.setContentDescription(label + (label.equals(activeTab) ? ", napili" : ""));
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(52), 1));
            item.setOnClickListener(v -> {
                if ("Settings".equals(label)) {
                    activeTab = "Settings";
                    showServerSettings();
                    return;
                }
                activeTab = label;
                if ("Library".equals(label)) showLibrary();
                else showHome();
            });
        }
        root.addView(nav);
    }

    private void populateHomeTracks(LinearLayout trackList, String query) {
        trackList.removeAllViews();
        ArrayList<Track> library = allTracks();
        String normalizedQuery = query.trim().toLowerCase(Locale.ROOT);
        int matches = 0;
        for (int i = 0; i < library.size(); i++) {
            Track track = library.get(i);
            if (offlineOnly && track.online && !track.isCached()) continue;
            if (!(track.title + " " + track.artist).toLowerCase(Locale.ROOT)
                    .contains(normalizedQuery)) continue;
            trackList.addView(songRow(i));
            matches++;
        }
        if (matches == 0) {
            String message;
            if (!normalizedQuery.isEmpty()) {
                message = "Walang kantang tumugma sa “" + query.trim() + "”. Subukan ang ibang salita.";
            } else if (offlineOnly) {
                message = "Wala pang kantang naka-save offline. Kumonekta sa internet at i-refresh ang library para ma-download ang mga kanta.";
            } else {
                message = "Wala pang kanta sa library. Maghintay ng upload ng admin o mag-import ng sarili mong music.";
            }
            TextView empty = text(message, 14, MUTED, false);
            empty.setPadding(dp(16), dp(18), dp(16), dp(18));
            empty.setBackground(round(SURFACE, 12));
            trackList.addView(empty, matchWrap());
        }
    }

    private View songRow(int index) {
        ArrayList<Track> library = allTracks();
        if (index < 0 || index >= library.size()) return new View(this);
        Track track = library.get(index);
        LinearLayout item = row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(0, dp(7), 0, dp(7));
        TextView cover = text("♫", 22, Color.WHITE, true);
        cover.setGravity(Gravity.CENTER);
        cover.setBackground(round(Color.rgb(38, 59, 45), 10));
        item.addView(cover, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.addView(text(track.title, 15, Color.WHITE, true));
        details.addView(text(track.artist + (track.isCached() ? " · Offline" : ""), 13, MUTED, false));
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0, -2, 1);
        detailsParams.leftMargin = dp(12);
        item.addView(details, detailsParams);
        TextView play = text(index == currentTrack && isPlaying ? "Ⅱ" : "▶", 16, GREEN, true);
        play.setGravity(Gravity.CENTER);
        item.addView(play, new LinearLayout.LayoutParams(dp(48), dp(48)));
        item.setOnClickListener(v -> playTrack(index));
        return item;
    }

    private void addMiniPlayer(LinearLayout root) {
        LinearLayout player = new LinearLayout(this);
        player.setOrientation(LinearLayout.VERTICAL);
        player.setPadding(dp(12), dp(5), dp(14), dp(5));
        player.setBackground(round(Color.rgb(38, 53, 44), 14));
        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER_VERTICAL);
        TextView cover = text("♫", 20, Color.WHITE, true);
        cover.setGravity(Gravity.CENTER);
        cover.setBackground(round(Color.rgb(56, 106, 73), 10));
        controls.addView(cover, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        ArrayList<Track> library = allTracks();
        String title = currentTrack >= 0 && currentTrack < library.size()
                ? library.get(currentTrack).title : "Pumili ng kanta";
        String artist = currentTrack >= 0 && currentTrack < library.size()
                ? library.get(currentTrack).artist : "Spatibai";
        details.addView(text(title, 14, Color.WHITE, true));
        details.addView(text(artist, 12, MUTED, false));
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0, -2, 1);
        detailsParams.leftMargin = dp(12);
        controls.addView(details, detailsParams);

        TextView previous = text("⏮", 18, Color.WHITE, true);
        previous.setGravity(Gravity.CENTER);
        controls.addView(previous, new LinearLayout.LayoutParams(dp(38), dp(42)));
        previous.setOnClickListener(v -> {
            if (!library.isEmpty()) playTrack((currentTrack - 1 + library.size()) % library.size());
        });
        TextView play = text(isPlaying ? "Ⅱ" : "▶", 20, Color.WHITE, true);
        play.setGravity(Gravity.CENTER);
        controls.addView(play, new LinearLayout.LayoutParams(dp(42), dp(42)));
        play.setOnClickListener(v -> togglePlayback());
        TextView next = text("⏭", 18, Color.WHITE, true);
        next.setGravity(Gravity.CENTER);
        controls.addView(next, new LinearLayout.LayoutParams(dp(38), dp(42)));
        next.setOnClickListener(v -> {
            if (!library.isEmpty()) playTrack((Math.max(currentTrack, -1) + 1) % library.size());
        });

        player.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        progress.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.rgb(76, 91, 81)));
        progress.setProgress(0);
        progress.setVisibility(currentTrack >= 0 ? View.VISIBLE : View.INVISIBLE);
        miniProgressBar = progress;
        updateMiniProgress();
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        progressParams.topMargin = dp(3);
        player.addView(progress, progressParams);
        if (mediaPlayer != null && isPlaying) {
            mainHandler.removeCallbacks(miniProgressRunnable);
            mainHandler.post(miniProgressRunnable);
        }

        LinearLayout.LayoutParams playerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        playerParams.setMargins(dp(14), dp(8), dp(14), dp(4));
        root.addView(player, playerParams);
    }

    private void updateMiniProgress() {
        MediaPlayer player = mediaPlayer;
        ProgressBar progress = miniProgressBar;
        if (player == null || progress == null || !playerPrepared) return;
        try {
            int duration = player.getDuration();
            if (duration > 0) {
                progress.setProgress((int) (player.getCurrentPosition() * 1000L / duration));
            }
        } catch (IllegalStateException ignored) {
            // The player may still be preparing after the screen is refreshed.
        }
    }

    private void playTrack(int index) {
        ArrayList<Track> library = allTracks();
        if (index < 0 || index >= library.size()) return;
        releasePlayer();
        currentTrack = index;
        isPlaying = false;
        playerPrepared = false;
        Track track = library.get(index);
        MediaPlayer nextPlayer = new MediaPlayer();
        mediaPlayer = nextPlayer;
        try {
            File cachedAudio = track.cachedFile();
            if (cachedAudio != null && cachedAudio.isFile() && cachedAudio.length() > 0) {
                nextPlayer.setDataSource(cachedAudio.getAbsolutePath());
            } else {
                Uri uri = Uri.parse(track.uri);
                java.util.HashMap<String, String> headers = new java.util.HashMap<>();
                String cookie = CookieManager.getInstance().getCookie(track.uri);
                if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);
                headers.put("User-Agent", android.webkit.WebSettings.getDefaultUserAgent(this));
                headers.put("Referer", serverBaseUrl() + "/api.php?route=tracks");
                headers.put("Accept", "audio/*,*/*;q=0.8");
                nextPlayer.setDataSource(this, uri, headers);
            }
            nextPlayer.setOnPreparedListener(preparedPlayer -> {
                if (mediaPlayer != preparedPlayer) return;
                playerPrepared = true;
                preparedPlayer.start();
                isPlaying = true;
                mainHandler.removeCallbacks(miniProgressRunnable);
                mainHandler.post(miniProgressRunnable);
                refreshCurrentScreen();
            });
            nextPlayer.setOnCompletionListener(completedPlayer -> {
                ArrayList<Track> currentLibrary = allTracks();
                if (mediaPlayer == completedPlayer && !currentLibrary.isEmpty()) {
                    playTrack((currentTrack + 1) % currentLibrary.size());
                }
            });
            nextPlayer.setOnErrorListener((failedPlayer, what, extra) -> {
                if (mediaPlayer == failedPlayer) {
                    android.util.Log.e("Spatibai", "Audio playback failed: what=" +
                            what + ", extra=" + extra);
                    releasePlayer();
                    toast("Hindi ma-play ang file na ito. Subukan ang ibang audio file.");
                    refreshCurrentScreen();
                }
                return true;
            });
            nextPlayer.prepareAsync();
            toast("Inihahanda ang " + track.title);
            refreshCurrentScreen();
        } catch (Exception exception) {
            if (mediaPlayer == nextPlayer) releasePlayer();
            toast("Hindi mabuksan ang audio file. Tingnan ang koneksyon o subukan ulit.");
            refreshCurrentScreen();
        }
    }

    private void togglePlayback() {
        if (mediaPlayer == null || currentTrack < 0) {
            if (allTracks().isEmpty()) openAudioPicker();
            else playTrack(0);
            return;
        }
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            isPlaying = false;
            refreshCurrentScreen();
        } else {
            try {
                mediaPlayer.start();
                isPlaying = true;
                refreshCurrentScreen();
            } catch (IllegalStateException exception) {
                playTrack(currentTrack);
            }
        }
    }

    private void refreshCurrentScreen() {
        if ("Library".equals(activeTab)) showLibrary();
        else showHome();
    }

    private ArrayList<Track> allTracks() {
        ArrayList<Track> library = new ArrayList<>(onlineTracks.size() + tracks.size());
        library.addAll(onlineTracks);
        library.addAll(tracks);
        return library;
    }

    private String serverBaseUrl() {
        return DEFAULT_SERVER_URL;
    }

    private void refreshOnlineTracks(boolean showMessage) {
        if (syncInProgress || !preferences.getBoolean("logged_in", false)) return;
        String baseUrl = serverBaseUrl();
        Uri serverUri = Uri.parse(baseUrl);
        String scheme = serverUri.getScheme();
        if (serverUri.getHost() == null ||
                (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            onlineStatus = "I-check ang server address sa settings.";
            if (showMessage) toast(onlineStatus);
            return;
        }
        syncInProgress = true;
        webViewChecks = 0;
        if (onlineTracks.isEmpty()) onlineStatus = "Kinukuha ang online music…";
        refreshCurrentScreen();
        ensureWebView();
        webView.loadUrl(baseUrl + "/api.php?route=tracks");
    }

    private void inspectCatalogPage(WebView view) {
        if (!syncInProgress || view != webView) return;
        webViewChecks++;
        view.evaluateJavascript("(function(){return document.body?document.body.innerText:''})()",
                result -> {
                    if (!syncInProgress || view != webView) return;
                    try {
                        String body = new JSONArray("[" + result + "]").getString(0);
                        JSONObject catalog = new JSONObject(body);
                        JSONArray items = catalog.getJSONArray("tracks");
                        ArrayList<Track> fetched = new ArrayList<>();
                        String baseUrl = serverBaseUrl();
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.getJSONObject(i);
                            String id = item.getString("id");
                            if (id.matches("[0-9a-fA-F-]{36}")) {
                                fetched.add(new Track(id,
                                        baseUrl + "/api.php?route=media&id=" + id,
                                        item.getString("title"),
                                        item.optString("artist", "Spatibai"), true,
                                        offlineFile(id).getAbsolutePath()));
                            }
                        }
                        acceptCatalog(fetched);
                    } catch (Exception exception) {
                        retryCatalogPage();
                    }
                });
    }

    private void retryCatalogPage() {
        if (webViewChecks >= 20) {
            failOnlineSync("Hindi makakonekta sa music server. Subukan ulit mamaya.");
            return;
        }
        mainHandler.postDelayed(() -> inspectCatalogPage(webView), 1000);
    }

    private void acceptCatalog(ArrayList<Track> fetched) {
        String currentId = null;
        ArrayList<Track> previous = allTracks();
        if (currentTrack >= 0 && currentTrack < previous.size()) {
            currentId = previous.get(currentTrack).id;
        }
        boolean changed = !sameOnlineTracks(onlineTracks, fetched);
        for (Track oldTrack : onlineTracks) {
            if (!containsTrack(fetched, oldTrack.id)) deleteCachedAudio(oldTrack);
        }
        onlineTracks.clear();
        onlineTracks.addAll(fetched);
        persistOnlineTracks();
        if (currentId != null) {
            int newIndex = findTrackIndex(currentId);
            if (newIndex < 0) releasePlayer();
            currentTrack = newIndex;
        }
        String cookie = CookieManager.getInstance().getCookie(serverBaseUrl());
        int missing = 0;
        for (Track track : onlineTracks) if (!track.isCached()) missing++;
        onlineStatus = onlineTracks.isEmpty()
                ? "Wala pang kanta sa server"
                : onlineTracks.size() + " kanta · " +
                (missing == 0 ? "Naka-save offline" : "Dina-download para sa offline");
        syncInProgress = false;
        if (changed || !fetched.isEmpty()) refreshCurrentScreen();
        if (missing > 0) downloadMissingTracks(new ArrayList<>(onlineTracks), cookie);
    }

    private boolean containsTrack(ArrayList<Track> source, String id) {
        for (Track track : source) if (track.id.equals(id)) return true;
        return false;
    }

    private void failOnlineSync(String message) {
        if (!syncInProgress) return;
        syncInProgress = false;
        onlineStatus = message + " · I-tap para i-refresh";
        refreshCurrentScreen();
        if (onlineTracks.isEmpty()) toast(message);
    }

    private void downloadMissingTracks(ArrayList<Track> catalog, String cookie) {
        networkExecutor.execute(() -> {
            int downloaded = 0;
            String failedTitle = null;
            for (Track track : catalog) {
                if (track.isCached()) continue;
                mainHandler.post(() -> {
                    onlineStatus = "Sine-save offline: " + track.title;
                    refreshCurrentScreen();
                });
                try {
                    downloadTrack(track, cookie);
                    downloaded++;
                } catch (Exception exception) {
                    failedTitle = track.title;
                }
            }
            final int savedCount = downloaded;
            final String failed = failedTitle;
            mainHandler.post(() -> {
                persistOnlineTracks();
                onlineStatus = failed == null
                        ? onlineTracks.size() + " kanta · Naka-save offline"
                        : "Hindi na-download: " + failed + " · I-tap para subukan ulit";
                refreshCurrentScreen();
                if (failed != null) toast("Hindi na-save offline ang " + failed + ". I-tap para subukan ulit.");
                else if (savedCount > 0) toast(savedCount + " kanta ang na-save para sa offline.");
            });
        });
    }

    private void downloadTrack(Track track, String cookie) throws Exception {
        File destination = track.cachedFile();
        if (destination == null) throw new IllegalStateException("Missing track storage path.");
        if (destination.isFile() && destination.length() > 0) return;
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IllegalStateException("Cannot create offline music folder.");
        }
        File temporary = new File(parent, destination.getName() + ".part");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(track.uri).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Accept", "audio/*");
            connection.setRequestProperty("User-Agent",
                    android.webkit.WebSettings.getDefaultUserAgent(this));
            connection.setRequestProperty("Referer", serverBaseUrl() + "/api.php?route=tracks");
            if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
            int code = connection.getResponseCode();
            String mimeType = connection.getContentType();
            if (code != HttpURLConnection.HTTP_OK ||
                    mimeType == null ||
                    !mimeType.toLowerCase(Locale.ROOT).startsWith("audio/")) {
                throw new IllegalStateException("Music server did not return audio (HTTP " +
                        code + ", " + mimeType + ").");
            }
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int count;
                long total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > 250L * 1024L * 1024L) {
                        throw new IllegalStateException("Audio file is too large to save offline.");
                    }
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (!temporary.renameTo(destination)) {
                throw new IllegalStateException("Could not save downloaded music.");
            }
        } catch (Exception exception) {
            if (temporary.exists() && !temporary.delete()) {
                throw new IllegalStateException("Download failed and temporary file could not be removed.",
                        exception);
            }
            throw exception;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private boolean sameOnlineTracks(ArrayList<Track> first, ArrayList<Track> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            Track a = first.get(i);
            Track b = second.get(i);
            if (!a.id.equals(b.id) || !a.title.equals(b.title) || !a.artist.equals(b.artist)) {
                return false;
            }
        }
        return true;
    }

    private int findTrackIndex(String id) {
        ArrayList<Track> library = allTracks();
        for (int i = 0; i < library.size(); i++) {
            if (library.get(i).id.equals(id)) return i;
        }
        return -1;
    }

    private void showServerSettings() {
        LinearLayout root = page();
        root.setPadding(0, dp(20), 0, 0);

        TextView title = text("Settings", 28, Color.WHITE, true);
        title.setPadding(dp(20), 0, dp(20), 0);
        root.addView(title);
        TextView subtitle = text("Kumonekta at mag-sync sa music site.", 14, MUTED, false);
        subtitle.setPadding(dp(20), dp(4), dp(20), 0);
        root.addView(subtitle);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(24), dp(20), dp(20));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView section = text("MUSIC SITE", 12, MUTED, true);
        LinearLayout.LayoutParams sectionParams = wrap();
        sectionParams.bottomMargin = dp(10);
        content.addView(section, sectionParams);

        LinearLayout infoCard = new LinearLayout(this);
        infoCard.setOrientation(LinearLayout.VERTICAL);
        infoCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        infoCard.setBackground(round(SURFACE, 14));
        LinearLayout.LayoutParams infoCardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        infoCardParams.topMargin = dp(14);
        content.addView(infoCard, infoCardParams);
        infoCard.addView(text("Server URL", 14, Color.WHITE, true));
        TextView maskedUrl = text("••••••••••••••••••••", 16, MUTED, true);
        maskedUrl.setSingleLine(true);
        infoCard.addView(maskedUrl);
        TextView help = text("Nakatago ang server address sa settings. Patuloy pa ring gumagana ang online sync at pag-download ng kanta para sa offline playback.",
                13, MUTED, false);
        LinearLayout.LayoutParams helpParams = wrap();
        helpParams.topMargin = dp(8);
        infoCard.addView(help, helpParams);

        Button save = button("Bumalik at mag-sync", true);
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        saveParams.topMargin = dp(18);
        content.addView(save, saveParams);
        save.setOnClickListener(v -> {
            activeTab = "Home";
            showHome();
            refreshOnlineTracks(true);
        });

        addMiniPlayer(root);
        addBottomNav(root);
        setContentView(root);
    }

    private void openAudioPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        audioPicker.launch(intent);
    }

    private void importSelectedAudio(Intent result) {
        ArrayList<Uri> selected = new ArrayList<>();
        if (result.getClipData() != null) {
            for (int i = 0; i < result.getClipData().getItemCount(); i++) {
                selected.add(result.getClipData().getItemAt(i).getUri());
            }
        } else if (result.getData() != null) {
            selected.add(result.getData());
        }

        int added = 0;
        for (Uri uri : selected) {
            try {
                getContentResolver().takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException exception) {
                toast("Hindi ma-save ang access sa napiling audio file.");
                continue;
            }
            String title = queryDisplayName(uri);
            if (title == null || title.trim().isEmpty()) title = "Audio track";
            int extension = title.lastIndexOf('.');
            if (extension > 0) title = title.substring(0, extension);

            boolean exists = false;
            for (Track track : tracks) {
                if (track.uri.equals(uri.toString())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                tracks.add(new Track(uri.toString(), title, "Offline audio"));
                added++;
            }
        }
        persistTracks();
        toast(added == 0 ? "Nasa library na ang napili mong kanta." :
                added + " audio file ang idinagdag.");
        if ("Library".equals(activeTab)) showLibrary();
        else showHome();
    }

    private String queryDisplayName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (RuntimeException exception) {
            toast("Hindi mabasa ang pangalan ng audio file.");
        }
        return null;
    }

    private void persistTracks() {
        JSONArray array = new JSONArray();
        for (Track track : tracks) {
            JSONObject item = new JSONObject();
            try {
                item.put("uri", track.uri);
                item.put("title", track.title);
                item.put("artist", track.artist);
                array.put(item);
            } catch (JSONException exception) {
                throw new IllegalStateException("Hindi ma-save ang kanta sa library.", exception);
            }
        }
        preferences.edit().putString("offline_tracks", array.toString()).apply();
    }

    private void persistOnlineTracks() {
        JSONArray array = new JSONArray();
        for (Track track : onlineTracks) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", track.id);
                item.put("uri", track.uri);
                item.put("title", track.title);
                item.put("artist", track.artist);
                array.put(item);
            } catch (JSONException exception) {
                throw new IllegalStateException("Hindi ma-save ang online music library.", exception);
            }
        }
        preferences.edit().putString("online_tracks", array.toString()).apply();
    }

    private void restoreTracks() {
        String stored = preferences.getString("offline_tracks", "[]");
        try {
            JSONArray array = new JSONArray(stored);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                tracks.add(new Track(item.getString("uri"),
                        item.getString("title"), item.getString("artist")));
            }
        } catch (JSONException exception) {
            preferences.edit().remove("offline_tracks").apply();
            toast("Hindi mabasa ang dating library. Puwede kang mag-import ulit.");
        }
    }

    private void restoreOnlineTracks() {
        String stored = preferences.getString("online_tracks", "[]");
        try {
            JSONArray array = new JSONArray(stored);
            boolean changed = false;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String id = item.getString("id");
                if (!id.matches("[0-9a-fA-F-]{36}")) continue;
                String uri = item.getString("uri");
                if (!isFromCurrentServer(uri)) {
                    deleteCachedAudio(offlineFile(id));
                    changed = true;
                    continue;
                }
                onlineTracks.add(new Track(id, uri,
                        item.getString("title"), item.optString("artist", "Spatibai"), true,
                        offlineFile(id).getAbsolutePath()));
            }
            if (changed) persistOnlineTracks();
            if (!onlineTracks.isEmpty()) {
                int cached = 0;
                for (Track track : onlineTracks) if (track.isCached()) cached++;
                onlineStatus = cached + " kanta ang naka-save para sa offline";
            }
        } catch (JSONException exception) {
            preferences.edit().remove("online_tracks").apply();
            onlineStatus = "Hindi mabasa ang dating music library.";
        }
    }

    private boolean isFromCurrentServer(String trackUri) {
        Uri track = Uri.parse(trackUri);
        Uri server = Uri.parse(serverBaseUrl());
        return track.getScheme() != null && track.getScheme().equalsIgnoreCase(server.getScheme()) &&
                track.getHost() != null && track.getHost().equalsIgnoreCase(server.getHost()) &&
                track.getPort() == server.getPort();
    }

    private void deleteCachedAudio(Track track) {
        deleteCachedAudio(track.cachedFile());
    }

    private void deleteCachedAudio(File file) {
        if (file != null && file.exists() && !file.delete()) {
            android.util.Log.w("Spatibai", "Could not delete stale offline audio: " + file.getName());
        }
    }

    private File offlineFile(String id) {
        return new File(new File(getFilesDir(), "offline_music"), id + ".audio");
    }

    private void releasePlayer() {
        mainHandler.removeCallbacks(miniProgressRunnable);
        if (miniProgressBar != null) miniProgressBar.setProgress(0);
        if (mediaPlayer != null) {
            MediaPlayer oldPlayer = mediaPlayer;
            mediaPlayer = null;
            isPlaying = false;
            playerPrepared = false;
            oldPlayer.setOnPreparedListener(null);
            oldPlayer.setOnCompletionListener(null);
            oldPlayer.setOnErrorListener(null);
            oldPlayer.release();
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(syncRunnable);
        networkExecutor.shutdownNow();
        releasePlayer();
        if (webView != null) {
            webView.stopLoading();
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private static class Track {
        final String uri;
        final String id;
        final String title;
        final String artist;
        final boolean online;
        final String cachePath;

        Track(String uri, String title, String artist) {
            this(uri, uri, title, artist, false, null);
        }

        Track(String id, String uri, String title, String artist, boolean online) {
            this(id, uri, title, artist, online, null);
        }

        Track(String id, String uri, String title, String artist, boolean online, String cachePath) {
            this.id = id;
            this.uri = uri;
            this.title = title;
            this.artist = artist;
            this.online = online;
            this.cachePath = cachePath;
        }

        File cachedFile() {
            return cachePath == null ? null : new File(cachePath);
        }

        boolean isCached() {
            File file = cachedFile();
            return file != null && file.isFile() && file.length() > 0;
        }
    }

    private TextView chip(String label, boolean selected) {
        TextView view = text(label, 13, selected ? BACKGROUND : Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(16), 0, dp(16), 0);
        view.setBackground(round(selected ? GREEN : SURFACE, 30));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, dp(36));
        params.rightMargin = dp(8);
        view.setLayoutParams(params);
        return view;
    }

    private EditText input(String hint, boolean password) {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setTextColor(Color.WHITE);
        field.setHintTextColor(MUTED);
        field.setHint(hint);
        field.setTextSize(15);
        field.setPadding(dp(16), 0, dp(16), 0);
        field.setBackground(round(SURFACE, 12));
        if (password) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return field;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        params.bottomMargin = dp(12);
        return params;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setTextColor(primary ? BACKGROUND : Color.WHITE);
        button.setBackground(round(primary ? GREEN : Color.WHITE, 28));
        return button;
    }

    private LinearLayout page() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(BACKGROUND);
        layout.setFocusableInTouchMode(true);
        final int[] basePadding = new int[4];
        final boolean[] capturedPadding = {false};
        ViewCompat.setOnApplyWindowInsetsListener(layout, (view, windowInsets) -> {
            if (!capturedPadding[0]) {
                basePadding[0] = view.getPaddingLeft();
                basePadding[1] = view.getPaddingTop();
                basePadding[2] = view.getPaddingRight();
                basePadding[3] = view.getPaddingBottom();
                capturedPadding[0] = true;
            }
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(basePadding[0] + insets.left,
                    basePadding[1] + insets.top,
                    basePadding[2] + insets.right,
                    basePadding[3] + insets.bottom);
            return windowInsets;
        });
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private GradientDrawable gradient(int start, int end, int radius) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{start, end});
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
