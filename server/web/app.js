"use strict";

const app = document.querySelector("#app"), header = document.querySelector("#header"),
  nav = document.querySelector("#tabs"), status = document.querySelector("#status");
const updateNotice = document.querySelector("#app-update");
const loadedVersion = document.querySelector('meta[name="cue-club-version"]')?.content;
const UPDATE_CHECK_INTERVAL_MS = 60_000;
let nextUpdateCheck = 0;
const base = "/cue-club/";
const accountDeletionPath = "/profiles/me/account-deletion";
const tabs = ["Home", "Results", "Players", "Table", "Profile"];
let token = localStorage.getItem("cue-token") || "", uid = localStorage.getItem("cue-uid") || "";
let data = null, online = false, page = "Home", filter = "All", signingUp = false;
let syncBusy = false, writing = false, clockOffset = 0, timerId = "", lastKey = "";
let messageTimer, notificationState = "", snapshotRevision = 0, settingsConnection;
let viewedProfile = "", profileReturn = "Players", introPendingId = "";
let notificationGameId = new URL(location.href).searchParams.get("match") || "";
let lastSyncAt = 0;
const seenTimers = new Set(), photoUrls = new Map();
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)");
const motion = {results:new Set(), bracket:[], table:false};
const self = () => data?.members.find(member => member.uid === uid);
const own = game => game && [game.playerAUid, game.playerBUid].includes(uid);
const league = game => !!game?.tournamentMatchId;
const activeStates = ["active", "awaiting_confirmation", "disputed"];
const myReport = game => game.playerAUid === uid ? game.reportAWinnerUid : game.reportBWinnerUid;
const opponentReport = game => game.playerAUid === uid ? game.reportBWinnerUid : game.reportAWinnerUid;
const byGame = id => data?.games.find(game => game.id === id);
const byMember = id => data?.members.find(member => member.uid === id);
const formatTime = seconds => [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
  .map(value => String(value).padStart(2, "0")).join(":");
const date = value => value && Number.isFinite(new Date(value).valueOf()) ? new Date(value).toLocaleString() : "";
const first = name => name.trim().split(/\s+/)[0] || "Pending";

function el(tag, attributes = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attributes)) {
    if (value == null || value === false) continue;
    if (key.startsWith("on")) node.addEventListener(key.slice(2), value);
    else if (key === "class") node.className = value;
    else node.setAttribute(key, value === true ? "" : value);
  }
  node.append(...children.flat().filter(value => value != null && value !== false));
  return node;
}
const button = (label, action, primary = false, attrs = {}) =>
  el("button", {type:"button", class:primary ? "primary" : null, onclick:action, ...attrs}, label);
const card = (...children) => el("section", {class:"card"}, ...children);
const muted = text => el("p", {class:"muted"}, text);

function toast(message) {
  status.textContent = message;
  clearTimeout(messageTimer);
  messageTimer = setTimeout(() => status.textContent = "", 4500);
}
async function api(path, method = "GET", body = undefined) {
  const headers = {};
  if (token) headers.Authorization = "Bearer " + token;
  if (body instanceof Blob) headers["Content-Type"] = body.type;
  else if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(body);
  }
  const response = await fetch(path, {method, headers, body, cache:"no-store"});
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw Object.assign(new Error(payload.error || "Server returned " + response.status), {code:response.status});
  return payload;
}
async function checkForWebUpdate() {
  if (!loadedVersion || !updateNotice || updateNotice.childElementCount) return;
  const now = Date.now();
  if (now < nextUpdateCheck) return;
  nextUpdateCheck = now + UPDATE_CHECK_INTERVAL_MS;
  try {
    const response = await fetch("/updates/web", {cache:"no-store"});
    if (!response.ok) return;
    const {version} = await response.json();
    if (!version || version === loadedVersion) return;
    updateNotice.append(
      el("span", {}, "A Cue Club update is available."),
      button("Reload", () => {
        if (writing) {
          toast("Wait for your change to finish before reloading.");
          return;
        }
        window.location.reload();
      }, true)
    );
  } catch { /* Check again after the connection recovers. */ }
}
function acceptSnapshot(snapshot) {
  if (snapshot.serverTimeMs) clockOffset = snapshot.serverTimeMs - Date.now();
  const key = JSON.stringify([snapshot.members, snapshot.games, snapshot.season, snapshot.matches, snapshot.isAdmin, snapshot.ratingHistory, snapshot.playingNow, snapshot.weeklyHighlights, snapshot.nextUp]);
  const changed = key !== lastKey || !online;
  if (data && changed) {
    const known = new Set(data.matches.map(match => match.id));
    snapshot.matches.forEach(match => { if (!known.has(match.id)) motion.results.add(match.id); });
    const oldBracket = data.season?.tournament, nextBracket = snapshot.season?.tournament;
    if (oldBracket?.id && oldBracket.id === nextBracket?.id) {
      const previous = new Set(oldBracket.matches.filter(match => match.status === "completed").map(match => match.id));
      nextBracket.matches.filter(match => match.status === "completed" && !previous.has(match.id)).forEach(match => {
        const next = nextBracket.matches.find(tile => tile.round === match.round + 1 && tile.slot === Math.floor(match.slot / 2));
        if (next) motion.bracket.push([match.id, next.id]);
      });
    }
    motion.table ||= JSON.stringify(data.playingNow?.map(game => game.id)) !==
      JSON.stringify(snapshot.playingNow?.map(game => game.id));
  }
  data = snapshot;
  online = true;
  lastSyncAt = Date.now();
  lastKey = key;
  if (!timerId && !notificationGameId) {
    const game = data.games.find(game => own(game) && game.startedAt &&
      activeStates.includes(game.status) && !seenTimers.has(game.id));
    if (game) {
      timerId = game.id;
      markTimerSeen(game);
    }
  }
  if (timerId && (!byGame(timerId) || byGame(timerId).status === "declined")) timerId = "";
  if (changed) {
    render(true);
    if (navigator.standalone || matchMedia("(display-mode: standalone)").matches) loadPushKey();
  }
  updateConnectionLabel();
}
async function sync() {
  if (!token || syncBusy || writing) return;
  syncBusy = true;
  const revision = snapshotRevision, session = token;
  try {
    const snapshot = await api("/sync");
    if (!writing && revision === snapshotRevision && session === token) acceptSnapshot(snapshot);
  } catch (error) {
    if (session !== token || revision !== snapshotRevision) return;
    if (error.code === 401) {
      clearSession();
      toast("Sign in again to continue.");
    } else if (online) {
      online = false;
      render(true);
    }
  } finally { syncBusy = false; }
}
async function change(path, body, success, method = "POST") {
  if (writing) return false;
  writing = true;
  snapshotRevision++;
  try {
    const result = await api(path, method, body);
    acceptSnapshot(result.members ? result : await api("/sync"));
    if (success) toast(success);
    return true;
  } catch (error) {
    toast(error.code ? error.message : "Couldn’t verify your change. Reconnect and check before trying again.");
    return false;
  } finally { writing = false; }
}
function clearSession() {
  token = uid = timerId = lastKey = viewedProfile = introPendingId = "";
  data = null;
  notificationState = "";
  lastSyncAt = 0;
  notificationGameId = "";
  snapshotRevision++;
  online = false;
  page = "Home";
  signingUp = false;
  localStorage.removeItem("cue-token");
  localStorage.removeItem("cue-uid");
  for (const url of photoUrls.values()) url.then(value => value && URL.revokeObjectURL(value));
  photoUrls.clear();
  seenTimers.clear();
  motion.results.clear();
  motion.bracket = [];
  motion.table = false;
  render();
}
function prompt(title, ...content) {
  const dialog = el("dialog", {}, el("h2", {}, title), ...content);
  dialog.append(button("Close", () => dialog.close()));
  dialog.addEventListener("close", () => dialog.remove(), {once:true});
  document.body.append(dialog);
  dialog.showModal();
  return dialog;
}
function field(label, attrs) {
  const input = el("input", attrs);
  return [el("label", {}, label, input), input];
}
function signInScreen() {
  const [emailField, email] = field("Work email", {type:"email", required:true, autocomplete:"email"});
  const [passwordField, password] = field("Password", {type:"password", required:true,
    autocomplete:signingUp ? "new-password" : "current-password", minlength:signingUp ? 8 : null});
  const [nameField, name] = field("Display name", {required:true, maxlength:48, autocomplete:"name"});
  const [codeField, code] = field("Office join code", {required:true, autocomplete:"off"});
  const submit = el("button", {class:"primary", type:"submit"}, signingUp ? "Create profile" : "Sign in");
  const form = el("form", {onsubmit:async event => {
    event.preventDefault();
    submit.disabled = true;
    try {
      const credentials = {email:email.value.trim(), password:password.value};
      if (signingUp) Object.assign(credentials, {displayName:name.value.trim(), joinCode:code.value.trim()});
      const result = await api(signingUp ? "/auth/register" : "/auth/login", "POST", credentials);
      token = result.token;
      uid = result.member.uid;
      localStorage.setItem("cue-token", token);
      localStorage.setItem("cue-uid", uid);
      render();
      await sync();
    } catch (error) { toast(error.message); } finally { submit.disabled = false; }
  }}, emailField, signingUp && nameField, signingUp && codeField, passwordField, submit);
  app.append(el("h1", {}, signingUp ? "Join Cue Club" : "Welcome to Cue Club"),
    muted("Use your office account to play across iPhone and Android."), card(form),
    button(signingUp ? "Already registered? Sign in" : "Create an office profile", () => { signingUp = !signingUp; render(); }));
}
function avatar(person, fallback = "?") {
  const name = person?.displayName || fallback;
  const initials = name.split(/\s+/).slice(0,2).map(word => word[0]).join("").toUpperCase();
  const circle = el("span", {class:"avatar", "aria-hidden":"true"}, initials || "?");
  if (person?.photoPath && token) {
    const key = person.uid + ":" + person.photoPath;
    if (!photoUrls.has(key)) {
      photoUrls.set(key, fetch("/photos/" + encodeURIComponent(person.uid) + "?revision=" +
        encodeURIComponent(person.photoPath), {headers:{Authorization:"Bearer " + token}})
        .then(response => { if (!response.ok) throw new Error(); return response.blob(); })
        .then(blob => URL.createObjectURL(blob)).catch(() => { photoUrls.delete(key); return null; }));
    }
    photoUrls.get(key).then(url => {
      if (url) {
        const image = el("img", {src:url, alt:""});
        image.addEventListener("load", () => circle.replaceChildren(image), {once:true});
      }
    });
  }
  return circle;
}
function openProfile(member) {
  if (!member) return;
  if (!viewedProfile) profileReturn = page === "Profile" ? "Players" : page;
  viewedProfile = member.uid === uid ? "" : member.uid;
  page = "Profile";
  render();
}
function profilePhoto(member, fallback) {
  if (!member) return avatar(member, fallback);
  const photo = button("", () => openProfile(member), false,
    {class:"photo-button", "aria-label":"View profile, " + member.displayName});
  photo.replaceChildren(avatar(member));
  return photo;
}
function invite(opponent, matchId = "") {
  const title = matchId ? "Play match?" : "Send 1v1 invitation?";
  const dialog = prompt(title, muted("Send an invitation to " + opponent.displayName + ". The match starts when they accept."));
  const send = button(matchId ? "Play match" : "Send invite", async () => {
    send.disabled = true;
    if (await change("/games/invite", {id:crypto.randomUUID(), opponentUid:opponent.uid,
      tournamentMatchId:matchId}, "Invitation sent.")) dialog.close();
    else send.disabled = false;
  }, true);
  dialog.insertBefore(send, dialog.lastChild);
}
function challenge() {
  const people = data.members.filter(member => member.uid !== uid);
  if (!people.length) { toast("Coworkers appear here after registering."); return; }
  const select = el("select", {}, people.map(member => el("option", {value:member.uid}, member.displayName)));
  const dialog = prompt("Challenge a player to 1v1", el("label", {}, "Opponent", select),
    button("Continue", () => { dialog.close(); invite(byMember(select.value)); }, true));
}
function claimNextUp() {
  const people = data.members.filter(member => member.uid !== uid);
  if (!people.length) { toast("Coworkers appear here after registering."); return; }
  const select = el("select", {}, people.map(member => el("option", {value:member.uid}, member.displayName)));
  const dialog = prompt("Claim next up", el("label", {}, "Partner", select),
    button("Claim slot", async () => {
      if (await change("/table/next-up", {opponentUid:select.value}, "You're next up.")) dialog.close();
    }, true));
}
function gameStatus(game) {
  if (!game) return "Ready to play";
  if (game.status === "invited") return game.playerBUid === uid ? "Invitation received" : "Waiting for acceptance";
  if (game.status === "active") return "Match in progress";
  if (game.status === "awaiting_confirmation") {
    const opponent = game.playerAUid === uid ? game.playerBName : game.playerAName;
    const winner = opponentReport(game) === game.playerAUid ? game.playerAName : game.playerBName;
    return myReport(game) ? "Waiting for " + opponent + " to confirm the result" : opponent + " reported " + winner + " as the winner";
  }
  if (game.status === "disputed") return "Waiting for admin review";
  if (game.status === "confirmed") return "Winner · " + game.winnerName;
  return "Match closed";
}
function report(game, resolve = false) {
  const dialog = prompt(resolve ? "Confirm official winner" : "Who won?");
  const actions = el("div", {class:"actions"}, [[game.playerAUid,game.playerAName],[game.playerBUid,game.playerBName]].map(([winnerUid,name]) =>
    button(name, async () => {
      const path = resolve ? "/admin/games/" + game.id + "/resolve" : "/games/" + game.id + "/report";
      if (await change(path, {winnerUid}, resolve ? "Result resolved." : "Result recorded.")) dialog.close();
    }, true)));
  dialog.insertBefore(actions, dialog.lastChild);
}
function markTimerSeen(game) {
  if (!seenTimers.has(game.id) && game.status === "active") introPendingId = game.id;
  seenTimers.add(game.id);
}
function openTimer(game) {
  if (!game) return;
  timerId = game.id;
  markTimerSeen(game);
  render();
}
function gameActions(game) {
  const buttons = [];
  if (own(game) && game.status === "invited" && game.playerBUid === uid) {
    buttons.push(button("Accept · start timer", async () => {
      await change("/games/" + game.id + "/respond", {accept:true}, "Match started.");
    }, true), button("Decline", () => change("/games/" + game.id + "/respond", {accept:false}, "Invitation declined.")));
  }
  if (own(game) && activeStates.includes(game.status) && game.startedAt) {
    if (game.status === "awaiting_confirmation" && !myReport(game) && opponentReport(game)) {
      const reported = opponentReport(game);
      const alternative = reported === game.playerAUid ? game.playerBUid : game.playerAUid;
      const name = alternative === game.playerAUid ? game.playerAName : game.playerBName;
      const send = winnerUid => change("/games/" + game.id + "/report",
        {winnerUid, expectedOpponentWinnerUid:reported}, "Result sent.");
      buttons.push(button("Confirm winner", () => send(reported), true), button("Dispute", () => {
        const dialog = prompt("Dispute result?",
          muted("Report " + name + " as the winner and send the result for admin review."),
          button("Send dispute", async () => { if (await send(alternative)) dialog.close(); }, true));
      }));
    } else if (game.id !== timerId && game.id !== notificationGameId) {
      buttons.push(button("Open match timer", () => openTimer(game), true));
    } else if (game.status === "active" && !myReport(game)) {
      buttons.push(button("Report winner", () => report(game), true));
    }
  }
  if (data.isAdmin && game.status === "disputed") buttons.push(button("Resolve result", () => report(game, true), true));
  return buttons.length ? el("div", {class:"actions"}, buttons) : null;
}
function gameCard(game) {
  return card(el("h3", {}, game.playerAName + " vs " + game.playerBName), muted(gameStatus(game)), gameActions(game));
}
function timerScreen(game) {
  const complete = game.status === "confirmed";
  const result = complete && data.matches.find(match => match.id === game.statsMatchId);
  const intro = introPendingId === game.id && game.status === "active";
  introPendingId = "";
  function player(side, direction) {
    const name = game["player" + side + "Name"];
    return el("div", {class:"versus-player " + direction},
      avatar(byMember(game["player" + side + "Uid"]), name), el("strong", {}, name));
  }
  app.append(el("h1", {}, complete ? "Match complete" : league(game) ? "League match" : "1v1 match"));
  if (result) app.append(resultCard(result));
  else app.append(el("div", {class:intro ? "match-intro" : "matchup"},
      el("div", {class:"versus-line"}, player("A","versus-left"),
        el("div", {class:"versus-center"}, intro && poolBall("break-ball"),
          el("strong", {class:"versus-word"}, "VS")), player("B","versus-right")),
      card(el("div", {class:"kicker"}, complete ? "MATCH DURATION" : "ELAPSED TIME"),
        el("div", {class:"clock", id:"clock"}, "00:00:00"), muted(gameStatus(game)), gameActions(game))));
  app.append(button("Back to " + (league(game) ? "bracket" : "results"), () => {
      page = league(game) ? "Home" : "Results";
      notificationGameId = "";
      timerId = "";
      render();
    }));
  updateClock();
}
function updateClock() {
  for (const clock of app.querySelectorAll("[data-table-started-at]")) {
    const seconds = Math.floor((Date.now() + clockOffset - Date.parse(clock.dataset.tableStartedAt)) / 1000);
    clock.textContent = Number.isFinite(seconds) ? formatTime(Math.max(0, seconds)) : "";
  }
  const clock = document.querySelector("#clock"), game = byGame(timerId);
  if (!clock || !game) return;
  const finished = game.finishedAt ? Date.parse(game.finishedAt) : Date.now() + clockOffset;
  const duration = Math.max(0, Math.floor((finished - Date.parse(game.startedAt)) / 1000));
  clock.textContent = Number.isFinite(duration) ? formatTime(duration) : "—";
}
function leagueTile(match, active) {
  const yours = [match.playerAUid,match.playerBUid].includes(uid);
  const ready = active && yours && match.status === "pending";
  function person(side) {
    const id = match["player" + side + "Uid"];
    const name = match["player" + side + "Name"] || (match.status === "bye" ? "Bye" : "Pending");
    const member = byMember(id);
    const icon = avatar(member, id ? name : "?");
    const opponent = ready && id && id !== uid;
    const clickable = opponent || !!member;
    return el(clickable ? "button" : "div", {type:clickable ? "button" : null,
      class:"person" + (id && id === match.winnerUid ? " winner" : ""),
      "aria-label":opponent ? "Play match against " + name : member ? "View profile, " + name : null,
      onclick:opponent ? () => invite(member || {uid:id,displayName:name}, match.id) :
        member ? () => openProfile(member) : null},
      icon, el("span", {}, first(name)));
  }
  const game = byGame(match.gameId);
  return el("div", {id:"league-" + match.id, tabindex:"-1", class:"pair" + (yours || match.onMyRoute ? " mine" : "")},
    el("div", {class:"row"}, person("A"), el("small", {}, "VS"), person("B")),
    el("small", {}, match.winnerName ? "Winner · " + first(match.winnerName) :
      match.status === "bye" ? "Advances with a bye" : !active ? "Closed" :
      ready ? "Tap opponent to play" : match.status === "waiting" ? "Waiting for previous round" :
      game ? gameStatus(game) : match.status === "in_progress" ? "In progress" : "Ready to play"));
}
function bracket(season) {
  const tournament = season?.tournament;
  if (!tournament) return;
  const active = tournament.status === "active";
  const rounds = [];
  for (let i = 1; i <= tournament.roundCount; i++) {
    rounds.push(el("div", {class:"round"}, el("h3", {}, i === tournament.roundCount ? "Final" : "Round " + i),
      tournament.matches.filter(match => match.round === i).map(match => leagueTile(match, active))));
  }
  app.append(card(el("small", {class:"kicker"}, "OFFICE LEAGUE"), el("h2", {}, season.name),
    tournament.championName ? el("p", {class:"champion"}, "Champion · " + tournament.championName) : !active ? muted("League closed") :
      season.deadlineMs ? el("p", {}, "Deadline · " + new Date(season.deadlineMs - 1).toLocaleDateString()) : null,
    tournament.myMatchId && button("Jump to my match", () => {
      const tile = document.getElementById("league-" + tournament.myMatchId);
      tile?.focus({preventScroll:true});
      tile?.scrollIntoView({block:"center", inline:"center"});
    }),
    el("div", {class:"tree", tabindex:"0", "aria-label":"Tournament bracket"}, rounds)));
  const advances = motion.bracket.splice(0);
  if (!reducedMotion.matches && advances.length) {
    const tree = app.querySelector(".tree");
    requestAnimationFrame(() => { if (tree.isConnected) drawBracketAdvances(tree, advances); });
  }
}
function drawBracketAdvances(tree, advances) {
  const bounds = tree.getBoundingClientRect();
  for (const [sourceId, targetId] of advances) {
    const source = document.getElementById("league-" + sourceId);
    const target = document.getElementById("league-" + targetId);
    if (!source || !target) continue;
    const from = source.getBoundingClientRect(), to = target.getBoundingClientRect();
    const left = from.right - bounds.left + tree.scrollLeft;
    const right = to.left - bounds.left + tree.scrollLeft;
    const fromY = from.top + from.height / 2 - bounds.top + tree.scrollTop;
    const toY = to.top + to.height / 2 - bounds.top + tree.scrollTop;
    const top = Math.min(fromY, toY);
    const canvas = el("canvas", {class:"bracket-trace", width:Math.ceil(right-left)+2,
      height:Math.ceil(Math.abs(fromY-toY))+4, "aria-hidden":"true"});
    canvas.style.left = left + "px";
    canvas.style.top = (top - 2) + "px";
    const ctx = canvas.getContext("2d");
    if (!ctx) continue;
    tree.append(canvas);
    target.classList.add("bracket-advance");
    ctx.strokeStyle = getComputedStyle(tree).getPropertyValue("--good");
    ctx.lineWidth = 2;
    const width = right - left, start = fromY - top + 2, finish = toY - top + 2;
    const length = width + Math.abs(finish - start);
    const began = performance.now();
    function trace(now) {
      if (!canvas.isConnected) return;
      const distance = Math.min(1,Math.max(0,(now-began)/480)) * length;
      ctx.clearRect(0,0,canvas.width,canvas.height);
      ctx.beginPath();
      ctx.moveTo(0,start);
      ctx.lineTo(Math.min(distance,width/2),start);
      if (distance > width/2) {
        ctx.lineTo(width/2,start + Math.sign(finish-start) * Math.min(distance-width/2,Math.abs(finish-start)));
      }
      if (distance > length-width/2) ctx.lineTo(width/2 + distance-(length-width/2),finish);
      ctx.stroke();
      if (distance < length) requestAnimationFrame(trace);
      else setTimeout(() => canvas.remove(), 900);
    }
    requestAnimationFrame(trace);
  }
}
function poolTable() {
  const known = online && Array.isArray(data.playingNow);
  const games = known ? data.playingNow : [];
  const label = !known ? "Availability unknown" : games.length ? "In use · Playing now" : "Available";
  const status = el("span", {class:"pill" + (!known || games.length ? " offline" : "")}, label);
  const panel = card(el("div", {class:"row between"}, el("h2", {}, "Pool table"), status),
    games.map(game => {
      const player = side => {
        const name = game["player" + side + "Name"];
        return el("div", {class:"row table-player"}, profilePhoto(byMember(game["player" + side + "Uid"]),name),
          el("strong", {class:"fill"}, name));
      };
      return el("div", {class:"table-game"}, player("A"), el("small", {}, "VS"), player("B"),
        game.startedAt && el("strong", {class:"table-clock", "data-table-started-at":game.startedAt}));
    }));
  if (motion.table) panel.classList.add("table-transition");
  motion.table = false;
  return panel;
}
function home() {
  app.append(el("h1", {}, "Your cue, your call."), poolTable());
  if (data.nextUp !== undefined) {
    const slot = data.nextUp;
    const a = byMember(slot?.playerAUid), b = byMember(slot?.playerBUid);
    app.append(card(el("h2", {}, "Next up"), slot
      ? el("div", {class:"row between"}, el("strong", {}, (a?.displayName || "Player") + " vs " + (b?.displayName || "Player")),
        online && (data.isAdmin || own(slot)) && button("Clear slot", () =>
          change("/table/next-up", {cancel:true}, "Next-up slot cleared.")))
      : online ? button("Claim next up", claimNextUp) : muted("Connect to claim the next slot.")));
  }
  app.append(el("p", {class:"muted", "data-sync-label":true}, connectionText()));
  const needsAction = data.games.filter(game => own(game) && (
    game.status === "awaiting_confirmation" && !myReport(game) ||
    game.status === "invited" && game.playerBUid === uid));
  const nextAction = needsAction.find(game => game.status === "awaiting_confirmation") || needsAction[0] ||
    data.games.find(game => own(game) && game.status === "active");
  if (nextAction) app.append(gameCard(nextAction));
  app.append(el("div", {class:"actions"}, button("Challenge a player to 1v1", challenge, !nextAction),
    data.isAdmin && button("Manage office league", adminPanel)));
  const tournament = data.season?.tournament;
  const next = tournament?.status === "active" && tournament.matches.find(match =>
    match.playerAUid && match.playerBUid && [match.playerAUid,match.playerBUid].includes(uid) &&
    ["pending","in_progress"].includes(match.status));
  if (next && next.gameId !== nextAction?.id) {
    const opponentId = next.playerAUid === uid ? next.playerBUid : next.playerAUid;
    const opponent = byMember(opponentId) || {uid:opponentId,displayName:next.playerAUid === uid ? next.playerBName : next.playerAName};
    const game = byGame(next.gameId);
    app.append(card(el("small", {class:"kicker"}, "MY NEXT LEAGUE MATCH"),
      el("div", {class:"row"}, profilePhoto(opponent), el("h2", {}, opponent.displayName)),
      muted(gameStatus(game)), game ? gameActions(game) :
        next.status === "pending" ? button("Play match", () => invite(opponent,next.id), true) : null));
  }
  const invitations = needsAction.filter(game => game !== nextAction && !league(game));
  if (invitations.length) app.append(el("h2", {}, "Incoming 1v1 invitations"), el("div", {}, invitations.map(gameCard)));
  if (data.weeklyHighlights?.length) app.append(card(el("h2", {}, "This week at the table"),
    data.weeklyHighlights.map(highlight => el("div", {class:"weekly-highlight"},
      el("strong", {}, highlight.title), muted(highlight.detail)))));
  bracket(data.season);
  if (!matchMedia("(display-mode: standalone)").matches && !navigator.standalone) {
    app.append(card(el("h2", {}, "Add Cue Club to your iPhone"), muted("In Safari, tap Share, then Add to Home Screen and Open as Web App.")));
  }
  app.append(notificationButton());
  updateClock();
}
function resultCard(match) {
  const winner = match.winnerUid === match.playerAUid ? match.playerAName : match.playerBName;
  const opponentId = match.playerAUid === uid ? match.playerBUid : match.playerBUid === uid ? match.playerAUid : "";
  const opponent = byMember(opponentId);
  const player = side => {
    const id = match["player" + side + "Uid"], name = match["player" + side + "Name"];
    return el("div", {class:"versus-player"}, profilePhoto(byMember(id), name),
      el("strong", {class:id === match.winnerUid ? "champion" : null}, name));
  };
  const animate = motion.results.delete(match.id) && !reducedMotion.matches;
  const champion = match.isLeagueMatch && data.season?.tournament?.matches.some(tile =>
    tile.round === data.season.tournament.roundCount && tile.statsMatchId === match.id);
  const upset = data.weeklyHighlights?.some(highlight => highlight.matchId === match.id);
  const rating = el("strong", {}, match.winnerEloAfter);
  const details = el("div", {class:"result-details"},
    el("div", {class:"row between"}, el("span", {class:"pill"}, match.isLeagueMatch ? "League" : "1v1"),
    el("small", {}, date(match.createdAt))),
    el("div", {class:"versus-line result-pair"}, player("A"), el("small", {}, "VS"), player("B")),
    el("div", {class:"row between"}, el("strong", {}, "Winner · " + winner),
      match.durationSeconds != null ? el("span", {class:"table-clock"}, formatTime(match.durationSeconds)) : null),
    match.ratingChange != null ? muted("Elo · winner +" + match.ratingChange + " / opponent −" + match.ratingChange) : null,
    match.winnerEloAfter != null && el("p", {class:"winner-elo"}, "Winner Elo · ", rating),
    upset && el("p", {class:"champion upset-badge"}, "Biggest upset this week"),
    !match.isLeagueMatch && opponent && button("Rematch", () => invite(opponent)));
  const panel = card(details);
  if (animate) {
    panel.classList.add("pocket-result");
    if (champion) panel.classList.add("final-celebration");
    panel.prepend(el("div", {class:"pocket-shot", "aria-hidden":"true"},
      poolBall(), el("span", {class:"pocket"})));
    if (match.winnerEloAfter != null && match.ratingChange != null) {
      const after = match.winnerEloAfter, before = after - match.ratingChange;
      rating.textContent = before;
      const start = performance.now() + 790;
      function count(now) {
        if (!rating.isConnected) return;
        const progress = Math.min(1,Math.max(0,(now-start)/420));
        rating.textContent = Math.round(before + (after-before)*progress);
        if (progress < 1) requestAnimationFrame(count);
      }
      requestAnimationFrame(count);
    }
  }
  return panel;
}
function results() {
  app.append(el("h1", {}, "Results"), muted(online ? "Confirmed matches from across the office." : "Showing results from your last sync."));
  const open = data.games.filter(game => own(game) && !league(game) && (activeStates.includes(game.status) || game.status === "invited"));
  if (open.length) app.append(el("h2", {}, "1v1 challenges"), ...open.map(gameCard));
  const options = ["All", "League", "1v1"];
  app.append(el("div", {class:"filters"}, options.map(option => button(option === "All" ? "All results" : option,
    () => {filter = option; render();}, false, {"aria-pressed":String(filter === option)}))));
  const rows = data.matches.filter(match => filter === "All" || !!match.isLeagueMatch === (filter === "League"));
  app.append(muted(rows.length + " confirmed " + (rows.length === 1 ? "result" : "results")));
  const list = el("div", {class:"cards"}, rows.map(resultCard));
  app.append(rows.length ? list : card(muted("No confirmed results yet.")));
}
function record(id) {
  const games = data.matches.filter(match => [match.playerAUid,match.playerBUid].includes(id));
  const wins = games.filter(match => match.winnerUid === id).length;
  return {wins, losses:games.length - wins};
}
function players() {
  app.append(el("h1", {}, "Players"), el("div", {class:"actions"}, button("Edit my profile", editProfile),
    button("Challenge a player to 1v1", challenge, true)));
  app.append(el("div", {class:"cards"}, data.members.map(member => {
    const stats = record(member.uid);
    return card(el("div", {class:"row"}, profilePhoto(member), el("div", {class:"fill"},
      el("h3", {}, member.displayName), el("small", {}, stats.wins + " wins · " + stats.losses + " losses")),
      member.uid !== uid && button("Challenge", () => invite(member))));
  })));
}
function table() {
  const roster = [...data.members].sort((a,b) => b.rating - a.rating || a.displayName.localeCompare(b.displayName));
  let rank = 0, previous;
  const rows = roster.map((member,index) => {
    if (member.rating !== previous) rank = index + 1;
    previous = member.rating;
    const stats = record(member.uid);
    return el("tr", {}, [rank,member.displayName,stats.wins,stats.losses,member.rating].map(value => el("td", {}, String(value))));
  });
  app.append(el("h1", {}, "Leaderboard"), muted("Ranked by Elo rating."), card(el("table", {},
    el("thead", {}, el("tr", {}, ["#","Player","W","L","Elo"].map(key => el("th", {scope:"col"}, key)))),
    el("tbody", {}, rows))));
}
function profile() {
  const mine = !viewedProfile;
  const profileUid = viewedProfile || uid;
  const me = byMember(profileUid);
  if (!mine && !me) {
    app.append(el("h1", {}, "Player profile"), muted("This profile is unavailable."),
      button("Back", () => { viewedProfile = ""; page = profileReturn; render(); }));
    return;
  }
  const ownMatches = data.matches.filter(match => [match.playerAUid, match.playerBUid].includes(profileUid));
  const wins = ownMatches.filter(match => match.winnerUid === profileUid).length;
  const rating = me?.rating ?? 1200;
  const history = mine ? data.ratingHistory || [] : me.ratingHistory || [];
  const stat = (label, value) => el("div", {class:"row between profile-stat"},
    el("span", {class:"muted"}, label), el("strong", {}, String(value)));
  app.append(el("h1", {}, mine ? "My profile" : "Player profile"), card(
    el("div", {class:"row profile-identity"}, avatar(me, me?.displayName), el("h2", {class:"fill"}, me?.displayName || "Your profile")),
    stat("Current Elo", rating), mine ? button("Edit profile", editProfile) :
      button("Back", () => { viewedProfile = ""; page = profileReturn; render(); })));
  const progress = card(el("h2", {}, "Elo progression"));
  if (history.length) progress.append(stat("Personal best", history.reduce((best, value) => Math.max(best,value))));
  if (history.length > 1) {
    const recent = Math.min(5, history.length - 1);
    const trend = history.at(-1) - history[history.length - 1 - recent];
    const points = history.slice(-21);
    progress.append(stat("Change over last " + recent + (recent === 1 ? " match" : " matches"),
      (trend >= 0 ? "+" : "−") + Math.abs(trend)), ratingChart(points),
      muted("Last " + (points.length - 1) + " confirmed " + (points.length === 2 ? "match" : "matches") + " · oldest to newest"));
  } else progress.append(muted("Rating history starts with the first confirmed match."));
  app.append(progress, card(el("h2", {}, "Match stats"), stat("Confirmed matches", ownMatches.length),
    stat("Wins · losses", wins + " · " + (ownMatches.length - wins)),
    stat("Win rate", ownMatches.length ? Math.round(wins * 100 / ownMatches.length) + "%" : "—"),
    ownMatches.length ? stat("Recent form · newest first", ownMatches.slice(0,5).map(match => match.winnerUid === profileUid ? "W" : "L").join("  ")) : null));
}
function ratingChart(points) {
  const firstRating = points[0], lastRating = points.at(-1);
  const canvas = el("canvas", {class:"rating-chart", width:560, height:155, role:"img",
    "aria-label":"Elo chart. Starts at " + firstRating + ", ends at " + lastRating + "."});
  const draw = () => {
    const ctx = canvas.getContext("2d");
    if (!ctx) return;
    const styles = getComputedStyle(document.documentElement);
    const min = Math.min(...points) - 4, max = Math.max(...points) + 4;
    const width = canvas.width, height = canvas.height, inset = 10;
    const x = i => inset + i * (width - 2*inset) / (points.length - 1);
    const y = rating => inset + (max - rating) * (height - 2*inset) / (max - min);
    ctx.clearRect(0,0,width,height);
    ctx.strokeStyle = styles.getPropertyValue("--line").trim();
    ctx.lineWidth = 1;
    for (const guide of [height/2,height-inset]) {
      ctx.beginPath(); ctx.moveTo(inset,guide); ctx.lineTo(width-inset,guide); ctx.stroke();
    }
    ctx.strokeStyle = styles.getPropertyValue("--chart").trim();
    ctx.fillStyle = ctx.strokeStyle;
    ctx.lineWidth = 3;
    ctx.lineJoin = "round";
    ctx.beginPath();
    points.forEach((rating, i) => i ? ctx.lineTo(x(i), y(rating)) : ctx.moveTo(x(i), y(rating)));
    ctx.stroke();
    ctx.beginPath(); ctx.arc(x(points.length-1), y(lastRating), 5, 0, Math.PI*2); ctx.fill();
  };
  requestAnimationFrame(draw);
  return canvas;
}
function editProfile() {
  const [nameField,name] = field("Display name", {value:self()?.displayName || "", maxlength:48, required:true});
  const [photoField,photo] = field("Profile photo · up to 12 MB", {type:"file", accept:"image/jpeg,image/png,image/webp"});
  const dialog = prompt("Your profile", nameField, photoField, button("Save profile", async () => {
    if (!name.reportValidity() || !name.value.trim()) return;
    const selected = photo.files[0];
    if (selected && (!["image/jpeg","image/png","image/webp"].includes(selected.type) || selected.size > 12*1024*1024)) {
      toast("Choose a JPEG, PNG, or WebP photo of 12 MB or less.");
      return;
    }
    if (name.value.trim() !== self()?.displayName && !await change("/profiles/me",{displayName:name.value.trim()}, "", "PUT")) return;
    if (selected && !await change("/profiles/me/photo", selected, "")) return;
    toast("Profile updated.");
    dialog.close();
  }, true));
}
function adminPanel() {
  if (!data.isAdmin) return;
  const season = data.season;
  const contents = [];
  if (season?.status === "active") {
    contents.push(el("h3", {}, season.name), button("End league now", () => {
      const confirmation = prompt("End league?", muted("Unfinished league matches will close."), button("End league", async () => {
        if (await change("/admin/seasons/" + season.id + "/end", {}, "League ended.")) { confirmation.close(); dialog.close(); }
      },true));
    }));
  } else {
    const [nameField,name] = field("League name", {value:"Office League", maxlength:48, required:true});
    const [endField,end] = field("End date", {type:"date", required:true});
    contents.push(nameField,endField,button("Start league", async () => {
      if (!name.reportValidity() || !end.reportValidity()) return;
      const [year, month, day] = end.value.split("-").map(Number);
      const deadlineMs = new Date(year, month-1, day+1).getTime();
      if (await change("/admin/seasons", {name:name.value.trim(),deadlineMs}, "League started.")) dialog.close();
    },true));
  }
  const disputes = data.games.filter(game => game.status === "disputed");
  if (disputes.length) contents.push(el("h3", {}, "Disputed results"), ...disputes.map(gameCard));
  const dialog = prompt("Manage office league", contents);
}
async function logout() {
  try {
    const registration = await pushRegistration;
    const subscription = await registration?.pushManager?.getSubscription();
    if (subscription) {
      try {
        await api("/web-push/unsubscribe", "POST", {endpoint:subscription.endpoint});
      } finally { await subscription.unsubscribe(); }
    }
    await api("/auth/logout", "POST", {});
  } catch { /* Local sign-out is still available when the server is offline. */ }
  clearSession();
}
const systemAppearance = matchMedia("(prefers-color-scheme: dark)");
function applyAppearance() {
  const choice = localStorage.getItem("cue-theme") || "dark";
  document.documentElement.dataset.theme = choice === "system" ? (systemAppearance.matches ? "dark" : "light") : choice;
}
function updateConnectionLabel() {
  if (settingsConnection?.isConnected) {
    settingsConnection.textContent = online ? "Server online" : connectionText();
    settingsConnection.className = online ? "pill" : "pill offline";
  }
  for (const label of app.querySelectorAll("[data-sync-label]")) label.textContent = connectionText();
}
function connectionText() {
  if (!lastSyncAt) return online ? "Connected" : "Connecting…";
  const time = new Date(lastSyncAt).toLocaleTimeString([], {hour:"numeric",minute:"2-digit"});
  return (online ? "Last synced at " : "Offline · last synced at ") + time;
}
async function checkConnection() {
  if (token) { await sync(); return; }
  try {
    online = !!(await api("/health")).online;
  } catch {
    online = false;
  }
  updateConnectionLabel();
}
function accountDeletion() {
  const [passwordField, password] = field("Current password", {
    type:"password", autocomplete:"current-password", required:true
  });
  const message = el("p", {role:"status"},
    "Your account, profile photo, and match records will be permanently deleted. This cannot be undone.");
  const submit = button("Delete account", async () => {
    if (!password.reportValidity()) return;
    submit.disabled = true;
    try {
      await api(accountDeletionPath, "DELETE", {password:password.value});
      dialog.close();
      clearSession();
      toast("Your account was deleted.");
      pushRegistration?.then(async registration => {
        const subscription = await registration?.pushManager?.getSubscription();
        await subscription?.unsubscribe();
      }).catch(() => {});
    } catch (error) {
      message.textContent = error.message;
      submit.disabled = false;
    } finally {
      password.value = "";
    }
  });
  const dialog = prompt("Delete account", message, passwordField, submit);
}
function settings() {
  settingsConnection = el("p", {class:online ? "pill" : "pill offline"}, online ? "Server online" : "Server offline");
  const currentTheme = localStorage.getItem("cue-theme") || "dark";
  const themes = el("select", {onchange:event => {
    localStorage.setItem("cue-theme", event.target.value);
    applyAppearance();
    render(true);
  }}, ["System", "Light", "Dark"].map(label => el("option", {value:label.toLowerCase(),
    selected:label.toLowerCase() === currentTheme}, label)));
  const dialog = prompt("Settings", settingsConnection, el("label", {}, "Theme", themes),
    el("div", {class:"actions"},
      token && button("My profile", () => { dialog.close(); viewedProfile = ""; page = "Profile"; timerId = ""; render(); }, true),
      button("Check connection", checkConnection),
      token && button("Delete account", () => { dialog.close(); accountDeletion(); }),
      token && button("Sign out", () => {
        dialog.close();
        const confirm = prompt("Sign out?", muted("You can sign in again with your office account."),
          button("Sign out", () => { confirm.close(); logout(); }, true));
      })));
  checkConnection();
  dialog.addEventListener("close", () => { settingsConnection = null; }, {once:true});
}
function render(preserve = false) {
  const y = preserve ? scrollY : 0;
  const horizontal = preserve ? document.querySelector(".tree")?.scrollLeft : 0;
  const me = self();
  const notified = notificationGameId && byGame(notificationGameId);
  if (notified?.startedAt && own(notified) && notified.status !== "declined") {
    timerId = notified.id;
    markTimerSeen(notified);
  }
  header.replaceChildren(el("div", {class:"shell"},
    el("span", {class:"brand"}, el("img", {src:base+"icon.jpg", alt:""}), "CUE CLUB"),
    el("div", {class:"row header-controls"},
      token && page === "Profile" && !viewedProfile && !timerId && button("Settings", settings),
      token && me && page === "Home" && !timerId && button("", () => { viewedProfile = ""; page = "Profile"; render(); }, false,
        {class:"profile-shortcut", "aria-label":"My profile, " + me.displayName}))));
  const shortcut = header.querySelector(".profile-shortcut");
  if (shortcut) shortcut.replaceChildren(el("span", {class:"shortcut-name"}, me.displayName), avatar(me));
  updateConnectionLabel();
  nav.replaceChildren(token && data && !timerId ? el("div",{class:"shell"},tabs.map(tab =>
    button(tab, () => { page = tab; viewedProfile = ""; notificationGameId = ""; render(); }, false, {"aria-current":tab === page ? "page" : null}))) : "");
  app.replaceChildren();
  if (!token) signInScreen();
  else if (!data) app.append(el("h1", {}, "Cue Club"), muted("Connecting to the server…"), button("Retry", sync));
  else if (notificationGameId) {
    if (notified) {
      const url = new URL(location.href);
      if (url.searchParams.has("match")) {
        url.searchParams.delete("match");
        history.replaceState(history.state, "", url);
      }
    }
    if (timerId && notified) timerScreen(notified);
    else {
      app.append(el("h1", {}, "Match"), notified ? gameCard(notified) : muted("This match is no longer available."),
        button("Back", () => { notificationGameId = ""; render(); }));
    }
  }
  else if (timerId && byGame(timerId)) timerScreen(byGame(timerId));
  else ({Home:home,Results:results,Players:players,Table:table,Profile:profile}[page] || home)();
  if (horizontal) {
    const tree = document.querySelector(".tree");
    if (tree) tree.scrollLeft = horizontal;
  }
  scrollTo(0,y);
}
let webPushKey;
const pushRegistration = "serviceWorker" in navigator ?
  navigator.serviceWorker.register(base + "sw.js").then(() => navigator.serviceWorker.ready).catch(() => null) : Promise.resolve(null);
function loadPushKey() {
  if (!token || !("PushManager" in window)) return Promise.resolve(null);
  if (!webPushKey) {
    webPushKey = api("/web-push/key").then(({publicKey}) => {
      const padded = (publicKey + "=".repeat((4 - publicKey.length % 4) % 4)).replace(/-/g,"+").replace(/_/g,"/");
      return Uint8Array.from(atob(padded),character => character.charCodeAt(0));
    }).catch(() => { webPushKey = null; return null; });
  }
  return webPushKey;
}
function notificationButton() {
  if (notificationState === "enabled") return muted("Match alerts enabled");
  return button("Enable match alerts", async () => {
    try {
      if (!matchMedia("(display-mode: standalone)").matches && !navigator.standalone) {
        toast("Add Cue Club to your Home Screen, then enable alerts there.");
        return;
      }
      if (!("Notification" in window) || Notification.permission === "denied") {
        toast("Allow Cue Club notifications in iPhone Settings.");
        return;
      }
      const permission = await Notification.requestPermission();
      if (permission !== "granted") return;
      const registration = await pushRegistration;
      if (!registration?.pushManager) throw new Error("Match alerts aren't available on this device.");
      let subscription = await registration.pushManager.getSubscription();
      if (!subscription) {
        const applicationServerKey = await loadPushKey();
        if (!applicationServerKey) throw new Error("Match alerts are unavailable on the server.");
        subscription = await registration.pushManager.subscribe({userVisibleOnly:true, applicationServerKey});
      }
      await api("/web-push/subscribe", "POST", subscription.toJSON());
      notificationState = "enabled";
      render(true);
    } catch (error) { toast(error.message); }
  });
}
pushRegistration.then(async registration => {
  if (token && registration?.pushManager && typeof Notification !== "undefined" && Notification.permission === "granted") {
    const subscription = await registration.pushManager.getSubscription().catch(() => null);
    if (subscription) {
      try {
        await api("/web-push/subscribe", "POST", subscription.toJSON());
        notificationState = "enabled";
        render(true);
      } catch { /* Keep the enable action available for retries. */ }
    }
  }
});
navigator.serviceWorker?.addEventListener("message", event => {
  if (event.data?.type === "match-notice") {
    notificationGameId = event.data.gameId || "";
    timerId = "";
    page = "Home";
    render();
    sync();
  }
});
function refreshWhenVisible() {
  if (document.hidden) return;
  sync();
  checkForWebUpdate();
}
function poolBall(extraClass = "") {
  return el("span", {class:"eight-ball " + extraClass, "aria-hidden":"true"},
    el("span", {class:"ball-number"}, "8"));
}
const pullBall = poolBall("pull-ball");
const pullShot = el("div", {class:"pull-shot", hidden:true, "aria-hidden":"true"},
  el("span", {class:"pull-cue"}), pullBall);
const poolColors = ["#e5ba39","#2469b1","#b73b37","#754093","#d97833","#368358","#792d38","#171c19"];
document.body.append(pullShot);
let pullStart, pullDistance = 0, pullLoading = false;
function canPull() {
  return token && !timerId && !notificationGameId &&
    ["Home","Results"].includes(page) && !document.querySelector("dialog[open]");
}
document.addEventListener("touchstart", event => {
  if (!canPull() || scrollY > 0 || pullLoading || event.touches.length !== 1) return;
  pullStart = [event.touches[0].clientX,event.touches[0].clientY];
  const number = 1 + Math.floor(Math.random() * 15);
  pullBall.querySelector(".ball-number").textContent = number;
  pullBall.style.setProperty("--pool-color", poolColors[(number - 1) % poolColors.length]);
  pullBall.classList.toggle("striped", number > 8);
}, {passive:true});
document.addEventListener("touchmove", event => {
  if (!pullStart || !canPull() || event.touches.length !== 1) return;
  const touch = event.touches[0], downward = touch.clientY - pullStart[1];
  if (downward < 0 || Math.abs(touch.clientX - pullStart[0]) > downward) {
    pullStart = undefined;
    pullDistance = 0;
    pullShot.hidden = true;
    return;
  }
  if (downward < 12) return;
  if (event.cancelable) event.preventDefault();
  pullDistance = Math.min(105,downward);
  pullShot.hidden = false;
  if (!reducedMotion.matches) {
    pullShot.style.setProperty("--pull-offset", (pullDistance * .55) + "px");
    pullShot.style.setProperty("--cue-draw", (-pullDistance * .55) + "px");
  }
}, {passive:false});
async function finishPull(refresh) {
  pullStart = undefined;
  if (pullLoading || pullShot.hidden) return;
  const ready = refresh && pullDistance >= 70 && canPull();
  pullDistance = 0;
  if (!ready) { pullShot.hidden = true; return; }
  pullLoading = true;
  const animate = !reducedMotion.matches;
  if (animate) pullShot.classList.add("refresh-strike");
  try {
    await Promise.all([sync(), animate && new Promise(resolve => setTimeout(resolve, 400))]);
  }
  finally {
    pullBall.classList.add("refresh-settle");
    setTimeout(() => {
      pullShot.hidden = true;
      pullShot.classList.remove("refresh-strike");
      pullBall.classList.remove("refresh-settle");
      pullLoading = false;
    }, animate ? 220 : 0);
  }
}
document.addEventListener("touchend", () => finishPull(true), {passive:true});
document.addEventListener("touchcancel", () => finishPull(false), {passive:true});
document.addEventListener("visibilitychange", refreshWhenVisible);
window.addEventListener("online", refreshWhenVisible);
systemAppearance.addEventListener("change", () => {
  if (localStorage.getItem("cue-theme") === "system") { applyAppearance(); render(true); }
});
applyAppearance();
render();
refreshWhenVisible();
setInterval(refreshWhenVisible, 4000);
setInterval(updateClock, 250);
