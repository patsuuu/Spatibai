const loginPanel = document.querySelector("#login-panel");
const dashboard = document.querySelector("#dashboard");
const loginForm = document.querySelector("#login-form");
const loginMessage = document.querySelector("#login-message");
const loginButton = document.querySelector("#login-button");
const passwordInput = document.querySelector("#admin-password");
const passwordToggle = document.querySelector("#password-toggle");
const uploadForm = document.querySelector("#upload-form");
const uploadMessage = document.querySelector("#upload-message");
const uploadButton = document.querySelector("#upload-button");
const fileInput = document.querySelector("#audio-file");
const fileHelp = document.querySelector("#file-help");
const adminList = document.querySelector("#admin-track-list");

function setAuthenticated(authenticated) {
  loginPanel.classList.toggle("hidden", authenticated);
  dashboard.classList.toggle("hidden", !authenticated);
  document.querySelector("#admin-account").classList.toggle("hidden", !authenticated);
  document.querySelector("#sidebar-guest").classList.toggle("hidden", authenticated);
}

async function requestJson(url, options = {}) {
  try {
    const response = await fetch(url, {
      ...options,
      headers: { ...(options.body instanceof FormData ? {} : { "Content-Type": "application/json" }), ...options.headers },
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) {
      if (response.status === 401 && url === "/api/admin/login") {
        throw new Error("Mali ang admin password. Tingnan at subukan ulit.");
      }
      if (response.status === 429) {
        throw new Error("Sobra na ang login attempts. Maghintay ng 15 minuto bago subukan ulit.");
      }
      throw new Error(data.error || "Hindi natuloy ang request.");
    }
    return data;
  } catch (error) {
    if (error instanceof TypeError) {
      throw new Error("Hindi makakonekta sa server. Suriin ang internet at subukan ulit.");
    }
    throw error;
  }
}

async function checkSession() {
  try {
    const result = await requestJson("/api/admin/session");
    setAuthenticated(result.authenticated);
    if (result.authenticated) await loadAdminTracks();
  } catch (error) {
    loginMessage.textContent = error.message;
    loginMessage.classList.add("is-error");
  }
}

async function loadAdminTracks() {
  adminList.replaceChildren();
  let tracks;
  try {
    ({ tracks } = await requestJson("/api/tracks"));
  } catch (error) {
    const message = document.createElement("p");
    message.className = "muted";
    message.textContent = error.message;
    adminList.append(message);
    return;
  }
  document.querySelector("#track-count").textContent = `${tracks.length} kanta`;
  if (!tracks.length) {
    const empty = document.createElement("p");
    empty.className = "muted";
    empty.textContent = "Wala pang kanta. Mag-upload ng audio para makita ito ng mga user.";
    adminList.append(empty);
    return;
  }
  for (const track of tracks) {
    const row = document.createElement("article");
    row.className = "admin-track";
    const meta = document.createElement("div");
    meta.className = "track-meta";
    const title = document.createElement("strong");
    title.textContent = track.title;
    const artist = document.createElement("span");
    artist.textContent = `${track.artist} · ${(track.size / 1024 / 1024).toFixed(1)} MB`;
    meta.append(title, artist);
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "delete-button";
    remove.textContent = "Tanggalin";
    remove.addEventListener("click", async () => {
      if (!window.confirm(`Tanggalin ang “${track.title}” sa library?`)) return;
      remove.disabled = true;
      try {
        await requestJson(`/api/admin/tracks/${encodeURIComponent(track.id)}`, { method: "DELETE" });
        await loadAdminTracks();
      } catch (error) {
        window.alert(error.message);
        remove.disabled = false;
      }
    });
    row.append(meta, remove);
    adminList.append(row);
  }
}

loginForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  loginMessage.textContent = "";
  loginMessage.classList.remove("is-error");
  const password = passwordInput.value;
  if (!password) {
    loginMessage.textContent = "Ilagay ang admin password para magpatuloy.";
    loginMessage.classList.add("is-error");
    passwordInput.focus();
    return;
  }
  loginButton.disabled = true;
  loginButton.innerHTML = "<span>Sinusuri…</span><span aria-hidden=\"true\">⋯</span>";
  try {
    await requestJson("/api/admin/login", {
      method: "POST",
      body: JSON.stringify({ password }),
    });
    loginForm.reset();
    setAuthenticated(true);
    await loadAdminTracks();
  } catch (error) {
    loginMessage.textContent = error.message;
    loginMessage.classList.add("is-error");
    passwordInput.select();
  } finally {
    loginButton.disabled = false;
    loginButton.innerHTML = "<span>Mag-log in</span><span aria-hidden=\"true\">→</span>";
  }
});

passwordToggle.addEventListener("click", () => {
  const visible = passwordInput.type === "password";
  passwordInput.type = visible ? "text" : "password";
  passwordToggle.textContent = visible ? "Itago" : "Ipakita";
  passwordToggle.setAttribute("aria-label", visible ? "Itago ang password" : "Ipakita ang password");
  passwordToggle.setAttribute("aria-pressed", String(visible));
});

uploadForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  uploadMessage.textContent = "";
  uploadButton.disabled = true;
  uploadButton.textContent = "Ina-upload…";
  try {
    await requestJson("/api/admin/tracks", {
      method: "POST",
      body: new FormData(uploadForm),
    });
    uploadForm.reset();
    fileHelp.textContent = "Pumili ng MP3, M4A, AAC, WAV, OGG, OPUS, FLAC o WEBM (max 100 MB).";
    uploadMessage.textContent = "Na-upload na! Makikita na ito sa music library.";
    await loadAdminTracks();
  } catch (error) {
    uploadMessage.textContent = error.message;
  } finally {
    uploadButton.disabled = false;
    uploadButton.innerHTML = "↑ &nbsp; I-upload sa library";
  }
});

fileInput.addEventListener("change", () => {
  const file = fileInput.files[0];
  fileHelp.textContent = file
    ? `${file.name} · ${(file.size / 1024 / 1024).toFixed(1)} MB`
    : "Pumili ng MP3, M4A, AAC, WAV, OGG, OPUS, FLAC o WEBM (max 100 MB).";
});

document.querySelector("#logout").addEventListener("click", async () => {
  const logoutButton = document.querySelector("#logout");
  logoutButton.disabled = true;
  try {
    await requestJson("/api/admin/logout", { method: "POST" });
    setAuthenticated(false);
    loginMessage.textContent = "";
  } catch (error) {
    window.alert(error.message);
  } finally {
    logoutButton.disabled = false;
  }
});

checkSession();
