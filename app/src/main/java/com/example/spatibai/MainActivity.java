package com.example.spatibai;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebChromeClient;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
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
    private final ArrayList<Track> onlineTracks = new ArrayList<>();
    private final ArrayList<Playlist> playlists = new ArrayList<>();
    private MediaPlayer mediaPlayer;
    private SeekBar miniSeekBar;
    private TextView miniElapsedTime;
    private TextView miniDurationTime;
    private TextView fullElapsedTime;
    private TextView fullDurationTime;
    private TextView miniRepeatButton;
    private TextView fullRepeatButton;
    private SeekBar fullSeekBar;
    private int currentTrack = -1;
    private int repeatMode;
    private String playbackPlaylistId;
    private boolean isPlaying = false;
    private boolean playerPrepared = false;
    private boolean isSeeking;
    private boolean offlineOnly = false;
    private String activeTab = "Home";
    private String activePlaylistId;
    private boolean fullPlayerOpen;
    private String homeSearchQuery = "";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService youtubeSearchExecutor = Executors.newSingleThreadExecutor();
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
        repeatMode = Math.max(0, Math.min(2, preferences.getInt("repeat_mode", 0)));
        restoreOnlineTracks();
        restorePlaylists();
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
                restorePlaylists();
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
                        restorePlaylists();
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

        Button youtubeSearchButton = button("Hanapin sa YouTube", true);
        youtubeSearchButton.setVisibility(offlineOnly ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams youtubeButtonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        youtubeButtonParams.bottomMargin = dp(20);
        content.addView(youtubeSearchButton, youtubeButtonParams);

        TextView quickTitle = text("Mga kanta sa library", 20, Color.WHITE, true);
        LinearLayout.LayoutParams sectionTitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        sectionTitleParams.bottomMargin = dp(12);
        content.addView(quickTitle, sectionTitleParams);
        LinearLayout trackList = new LinearLayout(this);
        trackList.setOrientation(LinearLayout.VERTICAL);
        content.addView(trackList, matchWrap());
        populateHomeTracks(trackList, homeSearchQuery);
        TextView youtubeTitle = text("Mga resulta sa YouTube", 20, Color.WHITE, true);
        youtubeTitle.setVisibility(offlineOnly ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams youtubeTitleParams = matchWrap();
        youtubeTitleParams.topMargin = dp(24);
        youtubeTitleParams.bottomMargin = dp(8);
        content.addView(youtubeTitle, youtubeTitleParams);
        TextView youtubeNote = text("Mga bagong resulta mula sa YouTube; online playback lang sa official player, hindi sine-save sa Spatibai library.", 12, MUTED, false);
        youtubeNote.setVisibility(offlineOnly ? View.GONE : View.VISIBLE);
        content.addView(youtubeNote, matchWrap());
        TextView youtubeStatus = text("Maghanap para makita ang mga kasalukuyang video sa YouTube.", 13, MUTED, false);
        youtubeStatus.setVisibility(offlineOnly ? View.GONE : View.VISIBLE);
        content.addView(youtubeStatus, matchWrap());
        LinearLayout youtubeResults = new LinearLayout(this);
        youtubeResults.setOrientation(LinearLayout.VERTICAL);
        youtubeResults.setVisibility(offlineOnly ? View.GONE : View.VISIBLE);
        content.addView(youtubeResults, matchWrap());
        youtubeSearchButton.setOnClickListener(v -> searchYouTubeVideos(
                search.getText().toString().trim(), youtubeSearchButton, youtubeStatus, youtubeResults));
        search.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                ((android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(
                        search.getWindowToken(), 0);
                searchYouTubeVideos(search.getText().toString().trim(),
                        youtubeSearchButton, youtubeStatus, youtubeResults);
                return true;
            }
            return false;
        });
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
        activeTab = "Library";
        activePlaylistId = null;
        LinearLayout root = page();
        root.setPadding(0, dp(20), 0, 0);
        TextView title = text("Ang library mo", 27, Color.WHITE, true);
        title.setPadding(dp(20), 0, dp(20), 0);
        root.addView(title);
        TextView intro = text("Mga playlist at kantang handa para sa susunod mong mood.", 14, MUTED, false);
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
        LinearLayout playlistHeading = row();
        playlistHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView playlistTitle = text("Mga playlist", 20, Color.WHITE, true);
        playlistHeading.addView(playlistTitle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView create = text("+ Gumawa", 14, GREEN, true);
        create.setGravity(Gravity.CENTER);
        playlistHeading.addView(create, wrap());
        create.setOnClickListener(v -> showCreatePlaylistDialog());
        content.addView(playlistHeading, matchWrap());
        if (playlists.isEmpty()) {
            TextView emptyPlaylists = text("Gumawa ng playlist at idagdag dito ang mga paborito mong kanta.", 13, MUTED, false);
            emptyPlaylists.setPadding(dp(14), dp(12), dp(14), dp(12));
            emptyPlaylists.setBackground(round(SURFACE, 12));
            LinearLayout.LayoutParams emptyParams = matchWrap();
            emptyParams.topMargin = dp(10);
            content.addView(emptyPlaylists, emptyParams);
        } else {
            for (Playlist playlist : new ArrayList<>(playlists)) {
                content.addView(playlistRow(playlist));
            }
        }
        TextView songsTitle = text("Mga kanta", 20, Color.WHITE, true);
        LinearLayout.LayoutParams songsTitleParams = matchWrap();
        songsTitleParams.topMargin = dp(22);
        songsTitleParams.bottomMargin = dp(8);
        content.addView(songsTitle, songsTitleParams);
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
                fullPlayerOpen = false;
                fullSeekBar = null;
                fullElapsedTime = null;
                fullDurationTime = null;
                fullRepeatButton = null;
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
                message = "Walang tugma sa Spatibai library. Puwede ring maghanap sa YouTube sa ibaba.";
            } else if (offlineOnly) {
                message = "Wala pang kantang naka-save offline. Kumonekta sa internet at i-refresh ang library para ma-download ang mga kanta.";
            } else {
                message = "Wala pang kanta sa library. Lalabas dito ang mga kantang in-upload ng admin.";
            }
            TextView empty = text(message, 14, MUTED, false);
            empty.setPadding(dp(16), dp(18), dp(16), dp(18));
            empty.setBackground(round(SURFACE, 12));
            trackList.addView(empty, matchWrap());
        }
    }

    private interface YouTubeSearchCallback {
        void onResult(JSONArray videos, String error);
    }

    private void searchYouTubeVideos(String query, Button searchButton, TextView status,
                                     LinearLayout results) {
        if (query.length() < 2) {
            status.setText("Maglagay ng kahit 2 character para maghanap.");
            return;
        }
        searchButton.setEnabled(false);
        status.setText("Naghahanap sa YouTube…");
        results.removeAllViews();
        requestYouTubeSearch(query, (videos, error) -> {
            searchButton.setEnabled(true);
            if (error != null) {
                status.setText(error);
                return;
            }
            if (videos.length() == 0) {
                status.setText("Walang nahanap na video. Subukan ang ibang search.");
                return;
            }
            status.setText("Mga kasalukuyang resulta mula sa YouTube. Pindutin ang video para mag-play.");
            for (int i = 0; i < videos.length(); i++) {
                JSONObject video = videos.optJSONObject(i);
                if (video == null) continue;
                String videoId = video.optString("videoId", "");
                String title = video.optString("title", "");
                String channel = video.optString("channelTitle", "YouTube channel");
                if (!videoId.matches("[a-zA-Z0-9_-]{11}") || title.isEmpty()) continue;
                LinearLayout item = row();
                item.setGravity(Gravity.CENTER_VERTICAL);
                item.setPadding(dp(12), dp(12), dp(12), dp(12));
                item.setBackground(round(SURFACE, 12));
                LinearLayout.LayoutParams itemParams = matchWrap();
                itemParams.bottomMargin = dp(8);
                item.setLayoutParams(itemParams);
                TextView icon = text("▶", 18, GREEN, true);
                icon.setGravity(Gravity.CENTER);
                item.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
                LinearLayout details = new LinearLayout(this);
                details.setOrientation(LinearLayout.VERTICAL);
                details.addView(text(title, 14, Color.WHITE, true));
                TextView channelLabel = text(channel, 12, MUTED, false);
                channelLabel.setMaxLines(1);
                channelLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
                details.addView(channelLabel);
                LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                detailsParams.leftMargin = dp(10);
                item.addView(details, detailsParams);
                item.setClickable(true);
                item.setFocusable(true);
                item.setOnClickListener(v -> showYouTubePlayer(title, videoId));
                results.addView(item);
            }
        });
    }

    private void requestYouTubeSearch(String query, YouTubeSearchCallback callback) {
        youtubeSearchExecutor.execute(() -> {
            JSONArray videos = new JSONArray();
            String error = null;
            HttpURLConnection connection = null;
            try {
                String endpoint = serverBaseUrl() + "/api.php?route=youtube-search&q=" +
                        java.net.URLEncoder.encode(query, StandardCharsets.UTF_8.name());
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(20_000);
                connection.setUseCaches(false);
                connection.setRequestProperty("Accept", "application/json");
                String token = preferences.getString("auth_token", "");
                if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
                int statusCode = connection.getResponseCode();
                InputStream stream = statusCode >= 400
                        ? connection.getErrorStream() : connection.getInputStream();
                if (stream == null) throw new IOException("Empty YouTube search response.");
                String responseText;
                try (InputStream input = stream;
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096];
                    int count;
                    int total = 0;
                    while ((count = input.read(buffer)) != -1) {
                        total += count;
                        if (total > 131_072) throw new IOException("YouTube search response is too large.");
                        output.write(buffer, 0, count);
                    }
                    responseText = output.toString(StandardCharsets.UTF_8.name());
                }
                JSONObject response = new JSONObject(responseText);
                if (statusCode < 200 || statusCode >= 300) {
                    error = response.optString("error", "Hindi natuloy ang YouTube search.");
                } else {
                    videos = response.optJSONArray("videos");
                    if (videos == null) throw new JSONException("Missing videos list.");
                }
            } catch (IOException | JSONException exception) {
                android.util.Log.w("Spatibai", "YouTube search failed.", exception);
                error = exception instanceof JSONException
                        ? "Hindi mabasa ang sagot ng YouTube search."
                        : "Hindi makakonekta sa YouTube search. Tingnan ang internet at subukan ulit.";
            } finally {
                if (connection != null) connection.disconnect();
            }
            JSONArray result = videos;
            String resultError = error;
            mainHandler.post(() -> callback.onResult(result, resultError));
        });
    }

    private void showYouTubePlayer(String title, String videoId) {
        if (!videoId.matches("[a-zA-Z0-9_-]{11}")) return;
        WebView player = new WebView(this);
        player.setBackgroundColor(Color.BLACK);
        player.getSettings().setJavaScriptEnabled(true);
        player.getSettings().setDomStorageEnabled(true);
        player.getSettings().setMediaPlaybackRequiresUserGesture(true);
        player.setWebChromeClient(new WebChromeClient());
        player.setWebViewClient(new WebViewClient());
        String html = "<!doctype html><html><head><meta name=\"viewport\" " +
                "content=\"width=device-width,initial-scale=1\"><style>" +
                "html,body{height:100%;margin:0;background:#000}iframe{" +
                "width:100%;height:100%;border:0}</style></head><body>" +
                "<iframe src=\"https://www.youtube.com/embed/" + videoId +
                "?playsinline=1\" title=\"YouTube video player\" " +
                "allow=\"accelerometer; autoplay; clipboard-write; encrypted-media; " +
                "gyroscope; picture-in-picture; web-share\" referrerpolicy=\"" +
                "strict-origin-when-cross-origin\" allowfullscreen></iframe></body></html>";
        player.loadDataWithBaseURL("https://www.youtube.com/", html, "text/html", "UTF-8", null);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(player, dp(16), dp(8), dp(16), dp(8))
                .setPositiveButton("Isara", null)
                .create();
        dialog.setOnDismissListener(ignored -> {
            player.stopLoading();
            player.destroy();
        });
        dialog.show();
    }

    private View songRow(int index) {
        return songRow(index, null);
    }

    private View songRow(int index, Playlist playlist) {
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
        String trackSubtitle = track.artist + (track.isCached() ? " · Offline" :
                track.isAudius() ? " · Audius · Online lang" : "");
        details.addView(text(trackSubtitle, 13, MUTED, false));
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0, -2, 1);
        detailsParams.leftMargin = dp(12);
        item.addView(details, detailsParams);
        TextView play = text(index == currentTrack && isPlaying ? "Ⅱ" : "▶", 16, GREEN, true);
        play.setGravity(Gravity.CENTER);
        item.addView(play, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView action = text(playlist == null ? "+" : "−", 22,
                playlist == null ? MUTED : Color.rgb(235, 105, 105), true);
        action.setGravity(Gravity.CENTER);
        action.setContentDescription(playlist == null ? "Idagdag sa playlist" : "Alisin sa playlist");
        item.addView(action, new LinearLayout.LayoutParams(dp(38), dp(48)));
        action.setOnClickListener(v -> {
            if (playlist == null) addTrackToPlaylistDialog(track);
            else removeTrackFromPlaylist(playlist, track);
        });
        item.setOnClickListener(v -> {
            if (playlist == null) playTrack(index);
            else playPlaylist(playlist, track.id);
        });
        return item;
    }

    private View playlistRow(Playlist playlist) {
        LinearLayout card = row();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(10), dp(8), dp(10));
        card.setBackground(round(SURFACE, 12));
        TextView cover = text("♫", 22, GREEN, true);
        cover.setGravity(Gravity.CENTER);
        cover.setBackground(round(Color.rgb(38, 59, 45), 10));
        card.addView(cover, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.addView(text(playlist.name, 15, Color.WHITE, true));
        details.addView(text(playlist.tracks.size() + " kanta", 12, MUTED, false));
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        detailsParams.leftMargin = dp(12);
        card.addView(details, detailsParams);
        TextView more = text("⋮", 22, MUTED, true);
        more.setGravity(Gravity.CENTER);
        card.addView(more, new LinearLayout.LayoutParams(dp(40), dp(48)));
        more.setOnClickListener(v -> showPlaylistOptions(playlist));
        card.setOnClickListener(v -> showPlaylist(playlist));
        LinearLayout.LayoutParams cardParams = matchWrap();
        cardParams.topMargin = dp(8);
        card.setLayoutParams(cardParams);
        return card;
    }

    private void showCreatePlaylistDialog() {
        EditText nameInput = input("Pangalan ng playlist", false);
        nameInput.setPadding(dp(16), 0, dp(16), 0);
        new AlertDialog.Builder(this)
                .setTitle("Bagong playlist")
                .setView(nameInput)
                .setNegativeButton("Kanselahin", null)
                .setPositiveButton("Gumawa", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty() || name.length() > 40) {
                        toast("Ilagay ang pangalan na 1–40 character.");
                        return;
                    }
                    Playlist playlist = new Playlist(
                            java.util.UUID.randomUUID().toString(), name, new ArrayList<>());
                    playlists.add(playlist);
                    persistPlaylists();
                    activeTab = "Library";
                    showLibrary();
                    showPlaylist(playlist);
                })
                .show();
    }

    private void showPlaylist(Playlist playlist) {
        if (!playlists.contains(playlist)) return;
        activeTab = "Library";
        activePlaylistId = playlist.id;
        LinearLayout root = page();
        root.setPadding(0, dp(18), 0, 0);
        LinearLayout heading = row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = text("‹", 32, Color.WHITE, true);
        back.setGravity(Gravity.CENTER);
        heading.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        back.setOnClickListener(v -> showLibrary());
        TextView title = text(playlist.name, 24, Color.WHITE, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heading.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView more = text("⋮", 24, MUTED, true);
        more.setGravity(Gravity.CENTER);
        heading.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        more.setOnClickListener(v -> showPlaylistOptions(playlist));
        root.addView(heading, matchWrap());

        ArrayList<Integer> trackIndexes = playlistTrackIndexes(playlist);
        LinearLayout actions = row();
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(18), dp(12), dp(18), dp(12));
        Button playAll = button("▶  I-play ang playlist", true);
        actions.addView(playAll, new LinearLayout.LayoutParams(0, dp(48), 1));
        playAll.setOnClickListener(v -> {
            if (trackIndexes.isEmpty()) {
                toast("Wala pang kantang available sa playlist na ito.");
            } else {
                playPlaylist(playlist, null);
            }
        });
        root.addView(actions, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), 0, dp(20), dp(12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        TextView addSongs = text("+  Magdagdag ng mga kanta", 14, GREEN, true);
        addSongs.setPadding(dp(4), dp(10), 0, dp(12));
        content.addView(addSongs, matchWrap());
        addSongs.setOnClickListener(v -> showAddSongsDialog(playlist));
        if (trackIndexes.isEmpty()) {
            TextView empty = text("Wala pang kanta rito. Pindutin ang “Magdagdag ng mga kanta” para pumili sa library.", 14, MUTED, false);
            empty.setPadding(dp(14), dp(14), dp(14), dp(14));
            empty.setBackground(round(SURFACE, 12));
            content.addView(empty, matchWrap());
        } else {
            ArrayList<Track> library = allTracks();
            for (int index : trackIndexes) content.addView(songRow(index, playlist));
        }
        addMiniPlayer(root);
        addBottomNav(root);
        setContentView(root);
    }

    private void showAddSongsDialog(Playlist playlist) {
        ArrayList<Track> library = allTracks();
        if (library.isEmpty()) {
            toast("Wala pang kantang idaragdag.");
            return;
        }
        String[] titles = new String[library.size()];
        boolean[] selected = new boolean[library.size()];
        for (int i = 0; i < library.size(); i++) {
            Track track = library.get(i);
            titles[i] = track.title + " — " + track.artist +
                    (playlist.tracks.contains(track.id) ? "  ✓" : "");
            selected[i] = playlist.tracks.contains(track.id);
        }
        new AlertDialog.Builder(this)
                .setTitle("Pumili ng kanta")
                .setMultiChoiceItems(titles, selected, (dialog, which, checked) -> {
                    selected[which] = checked;
                })
                .setNegativeButton("Kanselahin", null)
                .setPositiveButton("Idagdag", (dialog, which) -> {
                    for (int i = 0; i < selected.length; i++) {
                        String id = library.get(i).id;
                        if (selected[i] && !playlist.tracks.contains(id)) playlist.tracks.add(id);
                    }
                    persistPlaylists();
                    showPlaylist(playlist);
                })
                .show();
    }

    private void addTrackToPlaylistDialog(Track track) {
        if (playlists.isEmpty()) {
            showCreatePlaylistDialog();
            return;
        }
        String[] names = new String[playlists.size() + 1];
        for (int i = 0; i < playlists.size(); i++) names[i] = playlists.get(i).name;
        names[names.length - 1] = "+ Gumawa ng playlist";
        new AlertDialog.Builder(this)
                .setTitle("Idagdag sa playlist")
                .setItems(names, (dialog, which) -> {
                    if (which == playlists.size()) {
                        showCreatePlaylistDialog();
                        return;
                    }
                    Playlist playlist = playlists.get(which);
                    if (playlist.tracks.contains(track.id)) {
                        toast("Nasa playlist na ang kantang ito.");
                        return;
                    }
                    playlist.tracks.add(track.id);
                    persistPlaylists();
                    toast("Idinagdag sa " + playlist.name + ".");
                    if (activePlaylistId != null && activePlaylistId.equals(playlist.id)) {
                        showPlaylist(playlist);
                    } else if ("Library".equals(activeTab)) {
                        showLibrary();
                    }
                })
                .show();
    }

    private void removeTrackFromPlaylist(Playlist playlist, Track track) {
        playlist.tracks.remove(track.id);
        persistPlaylists();
        showPlaylist(playlist);
    }

    private void showPlaylistOptions(Playlist playlist) {
        String[] options = {"Palitan ang pangalan", "Burahin ang playlist"};
        new AlertDialog.Builder(this)
                .setTitle(playlist.name)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) showRenamePlaylistDialog(playlist);
                    else new AlertDialog.Builder(this)
                            .setTitle("Burahin ang playlist?")
                            .setMessage("Hindi mabubura ang mga kanta sa library.")
                            .setNegativeButton("Kanselahin", null)
                            .setPositiveButton("Burahin", (confirm, selected) -> {
                                playlists.remove(playlist);
                                if (playlist.id.equals(playbackPlaylistId)) playbackPlaylistId = null;
                                if (playlist.id.equals(activePlaylistId)) activePlaylistId = null;
                                persistPlaylists();
                                showLibrary();
                            })
                            .show();
                })
                .show();
    }

    private void showRenamePlaylistDialog(Playlist playlist) {
        EditText nameInput = input("Pangalan ng playlist", false);
        nameInput.setText(playlist.name);
        nameInput.setSelection(nameInput.length());
        new AlertDialog.Builder(this)
                .setTitle("Palitan ang pangalan")
                .setView(nameInput)
                .setNegativeButton("Kanselahin", null)
                .setPositiveButton("I-save", (dialog, which) -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty() || name.length() > 40) {
                        toast("Ilagay ang pangalan na 1–40 character.");
                        return;
                    }
                    playlist.name = name;
                    persistPlaylists();
                    showPlaylist(playlist);
                })
                .show();
    }

    private ArrayList<Integer> playlistTrackIndexes(Playlist playlist) {
        ArrayList<Integer> indexes = new ArrayList<>();
        ArrayList<Track> library = allTracks();
        for (String id : playlist.tracks) {
            for (int i = 0; i < library.size(); i++) {
                if (id.equals(library.get(i).id)) {
                    indexes.add(i);
                    break;
                }
            }
        }
        return indexes;
    }

    private void showNowPlaying() {
        fullPlayerOpen = true;
        ArrayList<Track> library = allTracks();
        Track track = currentTrack >= 0 && currentTrack < library.size()
                ? library.get(currentTrack) : null;
        LinearLayout root = page();
        root.setPadding(0, dp(12), 0, 0);

        LinearLayout toolbar = row();
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        TextView minimize = text("⌄", 30, Color.WHITE, true);
        minimize.setGravity(Gravity.CENTER);
        toolbar.addView(minimize, new LinearLayout.LayoutParams(dp(54), dp(48)));
        minimize.setContentDescription("I-minimize ang player");
        minimize.setOnClickListener(v -> minimizeNowPlaying());
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.VERTICAL);
        heading.setGravity(Gravity.CENTER);
        heading.addView(text("NOW PLAYING", 11, MUTED, true));
        Playlist queue = findPlaylist(playbackPlaylistId);
        heading.addView(text(queue == null ? "Spatibai" : queue.name, 12, Color.WHITE, true));
        toolbar.addView(heading, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView more = text("⋮", 22, Color.WHITE, true);
        more.setGravity(Gravity.CENTER);
        toolbar.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        more.setOnClickListener(v -> {
            if (track != null) addTrackToPlaylistDialog(track);
        });
        root.addView(toolbar, matchWrap());

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(18), dp(28), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView artwork = text("♫", 80, Color.WHITE, true);
        artwork.setGravity(Gravity.CENTER);
        artwork.setBackground(gradient(Color.rgb(38, 91, 56), Color.rgb(17, 39, 27), 18));
        LinearLayout.LayoutParams artworkParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280));
        artworkParams.bottomMargin = dp(24);
        content.addView(artwork, artworkParams);

        TextView title = text(track == null ? "Walang pinapatugtog" : track.title,
                24, Color.WHITE, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        content.addView(title, matchWrap());
        LinearLayout songRow = row();
        songRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView artist = text(track == null ? "Pumili ng kanta mula sa library" : track.artist,
                15, MUTED, false);
        artist.setSingleLine(true);
        artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
        songRow.addView(artist, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView add = text("+", 24, GREEN, true);
        add.setGravity(Gravity.CENTER);
        add.setContentDescription("Idagdag ang kanta sa playlist");
        songRow.addView(add, new LinearLayout.LayoutParams(dp(48), dp(48)));
        add.setEnabled(track != null);
        add.setOnClickListener(v -> {
            if (track != null) addTrackToPlaylistDialog(track);
        });
        content.addView(songRow, matchWrap());

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(GREEN));
        seekBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.rgb(76, 91, 81)));
        seekBar.setEnabled(track != null && playerPrepared);
        seekBar.setProgress(currentProgress());
        fullSeekBar = seekBar;
        LinearLayout.LayoutParams fullSeekParams = matchWrap();
        fullSeekParams.topMargin = dp(10);
        content.addView(seekBar, fullSeekParams);
        LinearLayout timeRow = row();
        TextView elapsed = text("0:00", 11, MUTED, false);
        TextView duration = text("0:00", 11, MUTED, false);
        fullElapsedTime = elapsed;
        fullDurationTime = duration;
        timeRow.addView(elapsed, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        duration.setGravity(Gravity.RIGHT);
        timeRow.addView(duration);
        content.addView(timeRow, matchWrap());
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser || !playerPrepared || mediaPlayer == null) return;
                try {
                    elapsed.setText(formatTime(mediaPlayer.getDuration() * value / 1000));
                } catch (IllegalStateException ignored) {
                    // The player may be transitioning between playback states.
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
                isSeeking = true;
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                MediaPlayer player = mediaPlayer;
                if (player != null && playerPrepared) {
                    try {
                        player.seekTo((int) (player.getDuration() * (long) bar.getProgress() / 1000));
                    } catch (IllegalStateException ignored) {
                        // The player may be transitioning between playback states.
                    }
                }
                isSeeking = false;
                updateMiniProgress();
            }
        });
        if (playerPrepared && mediaPlayer != null) {
            try {
                elapsed.setText(formatTime(mediaPlayer.getCurrentPosition()));
                duration.setText(formatTime(mediaPlayer.getDuration()));
                seekBar.setProgress((int) (mediaPlayer.getCurrentPosition() * 1000L /
                        Math.max(1, mediaPlayer.getDuration())));
            } catch (IllegalStateException ignored) {
                // The player may be transitioning between playback states.
            }
        }

        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        TextView previous = text("⏮", 24, Color.WHITE, true);
        previous.setGravity(Gravity.CENTER);
        controls.addView(previous, new LinearLayout.LayoutParams(0, dp(64), 1));
        previous.setOnClickListener(v -> navigatePlayback(-1));
        TextView play = text(isPlaying ? "Ⅱ" : "▶", 32, Color.WHITE, true);
        play.setGravity(Gravity.CENTER);
        controls.addView(play, new LinearLayout.LayoutParams(0, dp(64), 1));
        play.setOnClickListener(v -> togglePlayback());
        TextView next = text("⏭", 24, Color.WHITE, true);
        next.setGravity(Gravity.CENTER);
        controls.addView(next, new LinearLayout.LayoutParams(0, dp(64), 1));
        next.setOnClickListener(v -> navigatePlayback(1));
        TextView repeat = text("", 22, MUTED, true);
        repeat.setGravity(Gravity.CENTER);
        fullRepeatButton = repeat;
        updateRepeatButton();
        controls.addView(repeat, new LinearLayout.LayoutParams(0, dp(64), 1));
        repeat.setOnClickListener(v -> {
            repeatMode = (repeatMode + 1) % 3;
            preferences.edit().putInt("repeat_mode", repeatMode).apply();
            updateRepeatButton();
        });
        content.addView(controls, matchWrap());

        LinearLayout lyricsCard = new LinearLayout(this);
        lyricsCard.setOrientation(LinearLayout.VERTICAL);
        lyricsCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        lyricsCard.setBackground(round(SURFACE, 16));
        lyricsCard.addView(text("Lyrics", 20, Color.WHITE, true), matchWrap());
        boolean hasLyrics = track != null && !track.lyrics.trim().isEmpty();
        TextView lyrics = text(hasLyrics ? track.lyrics :
                "Wala pang lyrics na inilagay para sa kantang ito. Maaaring idagdag ng admin ang lyrics sa song details.",
                16, hasLyrics ? Color.WHITE : MUTED, false);
        lyrics.setLineSpacing(dp(5), 1f);
        lyrics.setTextIsSelectable(true);
        LinearLayout.LayoutParams lyricsParams = matchWrap();
        lyricsParams.topMargin = dp(12);
        lyricsCard.addView(lyrics, lyricsParams);
        LinearLayout.LayoutParams lyricsCardParams = matchWrap();
        lyricsCardParams.topMargin = dp(26);
        content.addView(lyricsCard, lyricsCardParams);

        addBottomNav(root);
        setContentView(root);
        updateMiniProgress();
    }

    private void minimizeNowPlaying() {
        fullPlayerOpen = false;
        fullSeekBar = null;
        fullElapsedTime = null;
        fullDurationTime = null;
        fullRepeatButton = null;
        refreshCurrentScreen();
    }

    @Override
    public void onBackPressed() {
        if (fullPlayerOpen) {
            minimizeNowPlaying();
            return;
        }
        super.onBackPressed();
    }

    private void addMiniPlayer(LinearLayout root) {
        LinearLayout player = new LinearLayout(this);
        player.setOrientation(LinearLayout.VERTICAL);
        player.setPadding(dp(12), dp(5), dp(14), dp(5));
        player.setBackground(round(Color.rgb(38, 53, 44), 14));
        player.setClickable(true);
        player.setOnClickListener(v -> showNowPlaying());
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
        previous.setOnClickListener(v -> navigatePlayback(-1));
        TextView play = text(isPlaying ? "Ⅱ" : "▶", 20, Color.WHITE, true);
        play.setGravity(Gravity.CENTER);
        controls.addView(play, new LinearLayout.LayoutParams(dp(42), dp(42)));
        play.setOnClickListener(v -> togglePlayback());
        TextView next = text("⏭", 18, Color.WHITE, true);
        next.setGravity(Gravity.CENTER);
        controls.addView(next, new LinearLayout.LayoutParams(dp(38), dp(42)));
        next.setOnClickListener(v -> navigatePlayback(1));
        TextView repeat = text("", 19, MUTED, true);
        repeat.setGravity(Gravity.CENTER);
        miniRepeatButton = repeat;
        updateRepeatButton();
        controls.addView(repeat, new LinearLayout.LayoutParams(dp(36), dp(42)));
        repeat.setOnClickListener(v -> {
            repeatMode = (repeatMode + 1) % 3;
            preferences.edit().putInt("repeat_mode", repeatMode).apply();
            updateRepeatButton();
        });

        player.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        LinearLayout seekRow = row();
        seekRow.setGravity(Gravity.CENTER_VERTICAL);
        miniElapsedTime = text("0:00", 10, MUTED, false);
        miniElapsedTime.setGravity(Gravity.CENTER_VERTICAL);
        seekRow.addView(miniElapsedTime, new LinearLayout.LayoutParams(dp(32), dp(20)));
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(GREEN));
        seekBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.rgb(76, 91, 81)));
        seekBar.setProgress(0);
        seekBar.setEnabled(currentTrack >= 0 && playerPrepared);
        miniSeekBar = seekBar;
        seekRow.addView(seekBar, new LinearLayout.LayoutParams(0, dp(22), 1));
        miniDurationTime = text("0:00", 10, MUTED, false);
        miniDurationTime.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        seekRow.addView(miniDurationTime, new LinearLayout.LayoutParams(dp(32), dp(20)));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && playerPrepared && mediaPlayer != null) {
                    try {
                        miniElapsedTime.setText(formatTime(
                                mediaPlayer.getDuration() * value / 1000));
                    } catch (IllegalStateException ignored) {
                        // The player may be transitioning between playback states.
                    }
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
                isSeeking = true;
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                MediaPlayer player = mediaPlayer;
                if (player != null && playerPrepared) {
                    try {
                        player.seekTo((int) (player.getDuration() * (long) bar.getProgress() / 1000));
                    } catch (IllegalStateException ignored) {
                        // The player may be transitioning between playback states.
                    }
                }
                isSeeking = false;
                updateMiniProgress();
            }
        });
        updateMiniProgress();
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(22));
        seekParams.topMargin = dp(1);
        player.addView(seekRow, seekParams);
        if (mediaPlayer != null && isPlaying) {
            mainHandler.removeCallbacks(miniProgressRunnable);
            mainHandler.post(miniProgressRunnable);
        }

        LinearLayout.LayoutParams playerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(74));
        playerParams.setMargins(dp(14), dp(8), dp(14), dp(4));
        root.addView(player, playerParams);
    }

    private void updateMiniProgress() {
        MediaPlayer player = mediaPlayer;
        SeekBar seekBar = miniSeekBar;
        if (player == null || !playerPrepared || isSeeking) return;
        try {
            int duration = player.getDuration();
            if (duration > 0) {
                int progress = (int) (player.getCurrentPosition() * 1000L / duration);
                if (seekBar != null) seekBar.setProgress(progress);
                if (fullSeekBar != null) fullSeekBar.setProgress(progress);
                if (miniElapsedTime != null) {
                    miniElapsedTime.setText(formatTime(player.getCurrentPosition()));
                }
                if (miniDurationTime != null) miniDurationTime.setText(formatTime(duration));
                if (fullElapsedTime != null) {
                    fullElapsedTime.setText(formatTime(player.getCurrentPosition()));
                }
                if (fullDurationTime != null) fullDurationTime.setText(formatTime(duration));
            }
        } catch (IllegalStateException ignored) {
            // The player may still be preparing after the screen is refreshed.
        }
    }

    private int currentProgress() {
        MediaPlayer player = mediaPlayer;
        if (player == null || !playerPrepared) return 0;
        try {
            int duration = player.getDuration();
            return duration <= 0 ? 0 : (int) (player.getCurrentPosition() * 1000L / duration);
        } catch (IllegalStateException ignored) {
            return 0;
        }
    }

    private void updateRepeatButton() {
        TextView button = miniRepeatButton;
        String description = repeatMode == 0 ? "Repeat off" :
                repeatMode == 1 ? "Repeat all" : "Repeat one";
        for (TextView repeat : new TextView[]{button, fullRepeatButton}) {
            if (repeat == null) continue;
            repeat.setText(repeatMode == 2 ? "↻¹" : "↻");
            repeat.setTextColor(repeatMode == 0 ? MUTED : GREEN);
            repeat.setContentDescription(description);
        }
    }

    private String formatTime(int milliseconds) {
        int totalSeconds = Math.max(0, milliseconds / 1000);
        return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private void playTrack(int index) {
        playTrack(index, false);
    }

    private void playTrack(int index, boolean keepPlaylistQueue) {
        ArrayList<Track> library = allTracks();
        if (index < 0 || index >= library.size()) return;
        if (!keepPlaylistQueue) playbackPlaylistId = null;
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
                    if (repeatMode == 2) {
                        completedPlayer.seekTo(0);
                        completedPlayer.start();
                        isPlaying = true;
                        updateMiniProgress();
                    } else {
                        advancePlayback(false);
                    }
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

    private void playPlaylist(Playlist playlist, String startTrackId) {
        ArrayList<Integer> indexes = playlistTrackIndexes(playlist);
        if (indexes.isEmpty()) {
            toast("Wala pang kantang available sa playlist na ito.");
            return;
        }
        int selected = indexes.get(0);
        if (startTrackId != null) {
            ArrayList<Track> library = allTracks();
            for (int index : indexes) {
                if (startTrackId.equals(library.get(index).id)) {
                    selected = index;
                    break;
                }
            }
        }
        playbackPlaylistId = playlist.id;
        playTrack(selected, true);
    }

    private void navigatePlayback(int direction) {
        ArrayList<Integer> indexes = new ArrayList<>();
        Playlist playlist = findPlaylist(playbackPlaylistId);
        if (playlist != null) indexes = playlistTrackIndexes(playlist);
        else {
            ArrayList<Track> library = allTracks();
            for (int i = 0; i < library.size(); i++) indexes.add(i);
        }
        if (indexes.isEmpty()) return;
        int position = indexes.indexOf(currentTrack);
        if (position < 0) position = direction > 0 ? -1 : 0;
        int nextPosition = (position + direction + indexes.size()) % indexes.size();
        playTrack(indexes.get(nextPosition), playlist != null);
    }

    private void advancePlayback(boolean manual) {
        ArrayList<Integer> indexes = new ArrayList<>();
        Playlist playlist = findPlaylist(playbackPlaylistId);
        if (playlist != null) indexes = playlistTrackIndexes(playlist);
        else {
            ArrayList<Track> library = allTracks();
            for (int i = 0; i < library.size(); i++) indexes.add(i);
        }
        if (indexes.isEmpty()) {
            isPlaying = false;
            refreshCurrentScreen();
            return;
        }
        int position = indexes.indexOf(currentTrack);
        if (position < 0) position = 0;
        if (position + 1 < indexes.size()) {
            playTrack(indexes.get(position + 1), playlist != null);
        } else if (manual) {
            playTrack(indexes.get(0), playlist != null);
        } else if (repeatMode == 1) {
            playTrack(indexes.get(0), playlist != null);
        } else {
            isPlaying = false;
            refreshCurrentScreen();
        }
    }

    private void togglePlayback() {
        if (mediaPlayer == null || currentTrack < 0) {
            if (allTracks().isEmpty()) {
                toast("Wala pang kantang in-upload ang admin.");
            } else {
                playTrack(0);
            }
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
        if (fullPlayerOpen) {
            showNowPlaying();
            return;
        }
        if ("Library".equals(activeTab)) {
            Playlist active = findPlaylist(activePlaylistId);
            if (active == null) showLibrary();
            else showPlaylist(active);
        }
        else showHome();
    }

    private ArrayList<Track> allTracks() {
        return new ArrayList<>(onlineTracks);
    }

    private Playlist findPlaylist(String id) {
        if (id == null) return null;
        for (Playlist playlist : playlists) {
            if (id.equals(playlist.id)) return playlist;
        }
        return null;
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
                                        offlineFile(id).getAbsolutePath(),
                                        item.optString("lyrics", ""),
                                        item.optString("source", ""),
                                        item.optString("providerTrackId", "")));
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
        int providerTracks = 0;
        for (Track track : onlineTracks) {
            if (track.isAudius()) providerTracks++;
            else if (!track.isCached()) missing++;
        }
        onlineStatus = onlineTracks.isEmpty()
                ? "Wala pang kanta sa server"
                : onlineTracks.size() + " kanta · " + (missing > 0
                ? "Dina-download para sa offline"
                : providerTracks > 0 ? providerTracks + " Audius · online streaming"
                : "Naka-save offline");
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
                if (track.isAudius()) continue;
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
                int providerCount = 0;
                for (Track track : onlineTracks) if (track.isAudius()) providerCount++;
                onlineStatus = failed != null
                        ? "Hindi na-download: " + failed + " · I-tap para subukan ulit"
                        : providerCount > 0 ? onlineTracks.size() + " kanta · " +
                        providerCount + " Audius online"
                        : onlineTracks.size() + " kanta · Naka-save offline";
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
            if (!a.id.equals(b.id) || !a.title.equals(b.title) || !a.artist.equals(b.artist) ||
                    !a.lyrics.equals(b.lyrics) || !a.source.equals(b.source) ||
                    !a.providerTrackId.equals(b.providerTrackId)) {
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

    private void persistOnlineTracks() {
        JSONArray array = new JSONArray();
        for (Track track : onlineTracks) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", track.id);
                item.put("uri", track.uri);
                item.put("title", track.title);
                item.put("artist", track.artist);
                item.put("lyrics", track.lyrics);
                item.put("source", track.source);
                item.put("providerTrackId", track.providerTrackId);
                array.put(item);
            } catch (JSONException exception) {
                throw new IllegalStateException("Hindi ma-save ang online music library.", exception);
            }
        }
        preferences.edit().putString("online_tracks", array.toString()).apply();
    }

    private String playlistStorageKey() {
        String email = preferences.getString("email", "").trim().toLowerCase(Locale.ROOT);
        return "playlists_" + (email.isEmpty() ? "guest" : email);
    }

    private void persistPlaylists() {
        JSONArray array = new JSONArray();
        for (Playlist playlist : playlists) {
            JSONObject item = new JSONObject();
            JSONArray trackIds = new JSONArray();
            try {
                item.put("id", playlist.id);
                item.put("name", playlist.name);
                for (String trackId : playlist.tracks) trackIds.put(trackId);
                item.put("tracks", trackIds);
                array.put(item);
            } catch (JSONException exception) {
                throw new IllegalStateException("Hindi ma-save ang mga playlist.", exception);
            }
        }
        preferences.edit().putString(playlistStorageKey(), array.toString()).apply();
    }

    private void restorePlaylists() {
        playlists.clear();
        String stored = preferences.getString(playlistStorageKey(), "[]");
        try {
            JSONArray array = new JSONArray(stored);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String id = item.getString("id");
                String name = item.getString("name").trim();
                if (id.isEmpty() || name.isEmpty() || name.length() > 40) continue;
                ArrayList<String> trackIds = new ArrayList<>();
                JSONArray tracks = item.optJSONArray("tracks");
                if (tracks != null) {
                    for (int j = 0; j < tracks.length(); j++) {
                        String trackId = tracks.optString(j, "");
                        if (!trackId.isEmpty() && !trackIds.contains(trackId)) trackIds.add(trackId);
                    }
                }
                playlists.add(new Playlist(id, name, trackIds));
            }
        } catch (JSONException exception) {
            android.util.Log.e("Spatibai", "Could not read saved playlists.", exception);
            preferences.edit().remove(playlistStorageKey()).apply();
        }
        if (findPlaylist(activePlaylistId) == null) activePlaylistId = null;
        if (findPlaylist(playbackPlaylistId) == null) playbackPlaylistId = null;
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
                        offlineFile(id).getAbsolutePath(), item.optString("lyrics", ""),
                        item.optString("source", ""), item.optString("providerTrackId", "")));
            }
            if (changed) persistOnlineTracks();
            if (!onlineTracks.isEmpty()) {
                int cached = 0;
                int providerCount = 0;
                for (Track track : onlineTracks) {
                    if (track.isAudius()) providerCount++;
                    else if (track.isCached()) cached++;
                }
                onlineStatus = cached + " offline · " + providerCount + " Audius online";
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
        if (miniSeekBar != null) miniSeekBar.setProgress(0);
        isSeeking = false;
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
        youtubeSearchExecutor.shutdownNow();
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
        final String lyrics;
        final String source;
        final String providerTrackId;

        Track(String uri, String title, String artist) {
            this(uri, uri, title, artist, false, null, "", "", "");
        }

        Track(String id, String uri, String title, String artist, boolean online) {
            this(id, uri, title, artist, online, null, "", "", "");
        }

        Track(String id, String uri, String title, String artist, boolean online, String cachePath) {
            this(id, uri, title, artist, online, cachePath, "", "", "");
        }

        Track(String id, String uri, String title, String artist, boolean online,
              String cachePath, String lyrics) {
            this(id, uri, title, artist, online, cachePath, lyrics, "", "");
        }

        Track(String id, String uri, String title, String artist, boolean online,
              String cachePath, String lyrics, String source, String providerTrackId) {
            this.id = id;
            this.uri = uri;
            this.title = title;
            this.artist = artist;
            this.online = online;
            this.cachePath = cachePath;
            this.lyrics = lyrics == null ? "" : lyrics;
            this.source = source == null ? "" : source;
            this.providerTrackId = providerTrackId == null ? "" : providerTrackId;
        }

        File cachedFile() {
            return cachePath == null ? null : new File(cachePath);
        }

        boolean isCached() {
            if (isAudius()) return false;
            File file = cachedFile();
            return file != null && file.isFile() && file.length() > 0;
        }

        boolean isAudius() {
            return "audius".equals(source);
        }
    }

    private static class Playlist {
        final String id;
        String name;
        final ArrayList<String> tracks;

        Playlist(String id, String name, ArrayList<String> tracks) {
            this.id = id;
            this.name = name;
            this.tracks = tracks;
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
