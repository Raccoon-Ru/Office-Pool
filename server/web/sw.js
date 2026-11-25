const SHELL = "cue-club-shell-v1";
const ASSETS = [
  "/cue-club/",
  "/cue-club/app.js",
  "/cue-club/style.css",
  "/cue-club/icon.jpg",
  "/cue-club/manifest.webmanifest"
];

self.addEventListener("install", event => {
  event.waitUntil(caches.open(SHELL)
    .then(cache => cache.addAll(ASSETS))
    .then(() => self.skipWaiting()));
});

self.addEventListener("activate", event => {
  event.waitUntil(caches.keys()
    .then(keys => {
      const oldShells = keys.filter(key => key.startsWith("cue-club-shell-") && key !== SHELL);
      return Promise.all(oldShells.map(key => caches.delete(key)));
    })
    .then(() => self.clients.claim()));
});

self.addEventListener("fetch", event => {
  const url = new URL(event.request.url);
  if (event.request.method !== "GET" || url.origin !== self.location.origin || !ASSETS.includes(url.pathname)) return;
  event.respondWith(fetch(event.request).catch(() => caches.match(event.request)));
});
self.addEventListener("push", event => {
  const message = event.data ? event.data.json() : {};
  event.waitUntil(self.registration.showNotification(message.title || "Cue Club", {
    body: message.body || "Open Cue Club for your match update.",
    tag: message.tag || "cue-club", icon: "/cue-club/icon.jpg"
  }));
});
self.addEventListener("notificationclick", event => {
  event.notification.close();
  event.waitUntil(self.clients.matchAll({type:"window", includeUncontrolled:true}).then(clients => {
    const existing = clients.find(client => new URL(client.url).pathname.startsWith("/cue-club/"));
    if (existing) {
      existing.postMessage({type:"match-notice"});
      return existing.focus();
    }
    return self.clients.openWindow("/cue-club/");
  }));
});
