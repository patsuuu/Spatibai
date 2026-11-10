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
const storageNotice = document.querySelector("#storage-notice");
const fileInput = document.querySelector("#audio-file");
const fileHelp = document.querySelector("#file-help");
const adminList = document.querySelector("#admin-track-list");
let storageReady = false;
let storageProvider = "local";

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
    if (result.authenticated) await Promise.all([loadAdminTracks(), loadAdminStorage()]);
  } catch (error) {
    loginMessage.textContent = error.message;
    loginMessage.classList.add("is-error");
  }
}

async function loadAdminStorage() {
  uploadButton.disabled = true;
  try {
    const storage = await requestJson("/api/admin/storage");
    storageProvider = storage.provider;
    storageReady = storage.available;
    storageNotice.classList.toggle("is-warning", !storageReady);
    storageNotice.textContent = storage.provider === "cloudflare-r2"
      ? storageReady
        ? "Nakakonekta ang Cloudflare R2. Doon mase-save ang audio at listahan ng kanta kahit mag-deploy o mag-restart ang Render."
        : "Hindi ma-access ang Cloudflare R2. Tingnan ang bucket name at R2 credentials sa Render bago mag-upload."
      : storageReady
        ? "Ang mga kanta at detalye ay sine-save sa lokal na server data folder."
        : "Hindi ligtas mag-upload: i-configure ang Cloudflare R2 sa Render bago mag-upload.";
  } catch (error) {
    storageReady = false;
    storageNotice.classList.add("is-warning");
    storageNotice.textContent = `Hindi ma-verify ang saved-file storage: ${error.message}`;
  } finally {
    uploadButton.disabled = !storageReady;
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
    artist.textContent = track.source === "audius"
      ? `${track.artist} · Audius · Online streaming`
      : `${track.artist} · ${(track.size / 1024 / 1024).toFixed(1)} MB`;
    meta.append(title, artist);
    const actions = document.createElement("div");
    actions.className = "track-actions";
    const editLyrics = document.createElement("button");
    editLyrics.type = "button";
    editLyrics.className = "text-button";
    editLyrics.textContent = "Lyrics";
    editLyrics.addEventListener("click", async () => {
      const lyrics = window.prompt(
        "Ilagay o i-edit ang lyrics (hanggang 12,000 character). I-publish lamang ang lyrics na may pahintulot ka.",
        track.lyrics || "",
      );
      if (lyrics === null) return;
      if (lyrics.length > 12000) {
        window.alert("Hanggang 12,000 character lang ang lyrics.");
        return;
      }
      editLyrics.disabled = true;
      try {
        await requestJson(`/api/admin/tracks/${encodeURIComponent(track.id)}`, {
          method: "PATCH",
          body: JSON.stringify({ lyrics }),
        });
        await loadAdminTracks();
      } catch (error) {
        window.alert(error.message);
        editLyrics.disabled = false;
      }
    });
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
    actions.append(editLyrics, remove);
    row.append(meta, actions);
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
    await Promise.all([loadAdminTracks(), loadAdminStorage()]);
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
  const files = Array.from(fileInput.files);
  const totalBytes = files.reduce((total, file) => total + file.size, 0);
  if (!files.length || files.length > 20 || files.some((file) => file.size > 100 * 1024 * 1024) ||
      totalBytes > 500 * 1024 * 1024) {
    uploadMessage.textContent = "Pumili ng 1–20 audio file. Max 100 MB bawat kanta at 500 MB ang kabuuan bawat batch.";
    uploadMessage.classList.add("is-error");
    return;
  }
  if (!storageReady) {
    uploadMessage.textContent = "Hindi muna nag-upload para maiwasan ang pagkawala ng file. Ayusin muna ang persistent storage.";
    uploadMessage.classList.add("is-error");
    return;
  }
  uploadMessage.textContent = "";
  uploadMessage.classList.remove("is-error");
  uploadButton.disabled = true;
  uploadButton.textContent = "Ina-upload…";
  try {
    const result = await requestJson("/api/admin/tracks", {
      method: "POST",
      body: new FormData(uploadForm),
    });
    uploadForm.reset();
    updateSelectedFiles();
    uploadMessage.replaceChildren();
    uploadMessage.classList.toggle("is-error", result.uploaded.length === 0);
    const summary = document.createElement("p");
    const storageName = storageProvider === "cloudflare-r2" ? "Cloudflare R2" : "server storage";
    summary.textContent = `${result.uploaded.length} kanta ang na-save sa ${storageName}.` +
      (result.failed.length ? ` ${result.failed.length} file ang hindi na-upload.` : "");
    uploadMessage.append(summary);
    for (const track of result.uploaded) {
      const item = document.createElement("p");
      item.textContent = `✓ ${track.title} — ${track.artist}` +
        (track.metadataNote ? ` · ${track.metadataNote}` : "");
      uploadMessage.append(item);
    }
    for (const failure of result.failed) {
      const item = document.createElement("p");
      item.textContent = `✕ ${failure.file}: ${failure.error}`;
      uploadMessage.append(item);
    }
    await loadAdminTracks();
  } catch (error) {
    uploadMessage.textContent = error.message;
  } finally {
    uploadButton.disabled = !storageReady;
    uploadButton.innerHTML = "↑ &nbsp; I-upload sa library";
  }
});

function updateSelectedFiles() {
  const files = Array.from(fileInput.files);
  if (!files.length) {
    fileHelp.textContent = "Pumili ng hanggang 20 kanta nang sabay-sabay. Awtomatikong babasahin ang title at artist tags; kung wala, gagamitin ang filename (hal. Artist - Title.mp3). Max 100 MB bawat kanta, 500 MB bawat batch.";
    return;
  }
  const totalBytes = files.reduce((total, file) => total + file.size, 0);
  fileHelp.textContent = `${files.length} file · ${(totalBytes / 1024 / 1024).toFixed(1)} MB total` +
    (files.length > 20 ? " · Hanggang 20 kanta lang bawat batch." : "");
}

fileInput.addEventListener("change", updateSelectedFiles);

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
