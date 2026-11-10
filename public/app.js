const audio = document.querySelector("#audio");
const list = document.querySelector("#track-list");
const notice = document.querySelector("#notice");
const search = document.querySelector("#search");
const playButton = document.querySelector("#play");
const seek = document.querySelector("#seek");
const adminAccount = document.querySelector("#library-admin-account");
const guestAccount = document.querySelector("#library-guest-account");
const logoutButton = document.querySelector("#library-logout");
let tracks = [];
let currentIndex = -1;

function formatTime(seconds) {
  if (!Number.isFinite(seconds)) return "0:00";
  const minutes = Math.floor(seconds / 60);
  return `${minutes}:${String(Math.floor(seconds % 60)).padStart(2, "0")}`;
}

function safeText(value) {
  return String(value || "");
}

function renderTracks() {
  const query = search.value.trim().toLocaleLowerCase();
  const matches = tracks
    .map((track, index) => ({ track, index }))
    .filter(({ track }) => `${track.title} ${track.artist}`.toLocaleLowerCase().includes(query));
  list.replaceChildren();
  if (!tracks.length) {
    notice.textContent = "Wala pang kanta sa library. Bumalik kapag may bagong upload.";
    notice.classList.remove("hidden");
    return;
  }
  notice.classList.toggle("hidden", matches.length > 0);
  if (!matches.length) notice.textContent = "Walang tugmang kanta.";
  for (const { track, index } of matches) {
    const row = document.createElement("article");
    row.className = "track-row";
    const artwork = document.createElement("div");
    artwork.className = "cover";
    artwork.textContent = "♫";
    const metadata = document.createElement("div");
    metadata.className = "track-meta";
    const title = document.createElement("strong");
    title.textContent = safeText(track.title);
    const artist = document.createElement("span");
    artist.textContent = safeText(track.artist);
    metadata.append(title, artist);
    const duration = document.createElement("span");
    duration.className = "track-date";
    duration.textContent = new Date(track.uploadedAt).toLocaleDateString();
    const button = document.createElement("button");
    button.className = "row-play";
    button.type = "button";
    button.setAttribute("aria-label", `I-play ang ${track.title}`);
    button.textContent = index === currentIndex && !audio.paused ? "Ⅱ" : "▶";
    button.addEventListener("click", () => playTrack(index));
    row.append(artwork, metadata, duration, button);
    list.append(row);
  }
}

async function loadTracks() {
  try {
    const response = await fetch("/api.php?route=tracks");
    if (!response.ok) throw new Error("Hindi makuha ang library.");
    const data = await response.json();
    tracks = data.tracks;
    renderTracks();
  } catch (error) {
    notice.textContent = error.message || "Hindi makakonekta sa Spatibai.";
    notice.classList.remove("hidden");
  }
}

async function checkAdminSession() {
  try {
    const response = await fetch("/api/admin/session", { cache: "no-store" });
    if (!response.ok) throw new Error("Hindi masuri ang admin session.");
    const session = await response.json();
    adminAccount.classList.toggle("hidden", !session.authenticated);
    guestAccount.classList.toggle("hidden", session.authenticated);
  } catch (error) {
    console.warn("Could not check admin session:", error);
  }
}

logoutButton.addEventListener("click", async () => {
  logoutButton.disabled = true;
  try {
    const response = await fetch("/api/admin/logout", { method: "POST" });
    if (!response.ok) throw new Error("Hindi naisagawa ang pag-log out.");
    adminAccount.classList.add("hidden");
    guestAccount.classList.remove("hidden");
  } catch (error) {
    window.alert(error.message);
  } finally {
    logoutButton.disabled = false;
  }
});

async function playTrack(index) {
  if (!tracks[index]) return;
  currentIndex = index;
  const track = tracks[index];
  audio.src = `/api.php?route=media&id=${encodeURIComponent(track.id)}`;
  document.querySelector("#player-title").textContent = track.title;
  document.querySelector("#player-artist").textContent = track.artist;
  playButton.disabled = false;
  try {
    await audio.play();
    playButton.textContent = "Ⅱ";
    playButton.setAttribute("aria-label", "Pause");
  } catch {
    document.querySelector("#player-artist").textContent = "Hindi ma-play ang audio file na ito.";
    playButton.textContent = "▶";
  }
  renderTracks();
}

function togglePlayback() {
  if (!audio.src) {
    if (tracks.length) playTrack(0);
    return;
  }
  if (audio.paused) audio.play().catch(() => {});
  else audio.pause();
}

function skip(direction) {
  if (!tracks.length) return;
  const index = currentIndex < 0
    ? (direction > 0 ? 0 : tracks.length - 1)
    : (currentIndex + direction + tracks.length) % tracks.length;
  playTrack(index);
}

search.addEventListener("input", renderTracks);
playButton.addEventListener("click", togglePlayback);
document.querySelector("#previous").addEventListener("click", () => skip(-1));
document.querySelector("#next").addEventListener("click", () => skip(1));
audio.addEventListener("play", () => {
  playButton.textContent = "Ⅱ";
  playButton.setAttribute("aria-label", "Pause");
  renderTracks();
});
audio.addEventListener("pause", () => {
  playButton.textContent = "▶";
  playButton.setAttribute("aria-label", "Play");
  renderTracks();
});
audio.addEventListener("ended", () => skip(1));
audio.addEventListener("loadedmetadata", () => {
  document.querySelector("#duration").textContent = formatTime(audio.duration);
  seek.disabled = !Number.isFinite(audio.duration);
});
audio.addEventListener("timeupdate", () => {
  document.querySelector("#elapsed").textContent = formatTime(audio.currentTime);
  if (Number.isFinite(audio.duration) && audio.duration > 0 && !seek.matches(":active")) {
    seek.value = String(Math.round(audio.currentTime / audio.duration * 1000));
  }
});
seek.addEventListener("input", () => {
  if (Number.isFinite(audio.duration)) audio.currentTime = Number(seek.value) / 1000 * audio.duration;
});

loadTracks();
checkAdminSession();
window.setInterval(loadTracks, 30000);
