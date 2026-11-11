# Independent preset sync

`node server/preset-sync/index.mjs` serves the local browser on HTTP8792 and native clients on HTTPS9447. Both bind127.0.0.1. The health relay9443 is independent. Native clients must validate TLS using the existing app CA; no TLS bypass is provided.

The TLS certificate and key have no default: set `PITIN_PRESET_TLS_CERT` and `PITIN_PRESET_TLS_KEY` (for local development, `npm run dev-tls` creates a pair in `server/.dev-tls/` and prints the two lines to set them). Preferences are kept outside this project in `../.setup/pitin-preset-sync/preferences.json`. Startup restricts the preference directory and existing files to the current Windows user. Port overrides: `PITIN_PRESET_HTTP_PORT`, `PITIN_PRESET_TLS_PORT`. No credential is printed.

`createPresetSyncService({storeFile=null,tls,now=Date.now,browserHandler})` synchronously returns `{httpServer,httpsServer,engine,close}` without listening. `close()` is asynchronous and releases the writer lock. `storeFile=null` is ephemeral for isolated tests. Default browser assets are `web/continuity` plus shared fonts; an optional handler replaces static routing only.

The exact persisted object is `{schemaVersion:1,presets,selectedId,defaultId,autoDefaultOnRest}`. `store.mjs` exports `preferences(state)` and `validatePreferences(data,defaults)` for migration. Obtain defaults from a fresh `ContinuityEngine().getState()`. Invalid data fails startup. Session secrets, feeling, plans, observations and events never enter this file. Every successful command commits preferences before replying; failure rolls engine state, clock, simulated physical values and request history back.

An exclusive `.lock` file prevents another writer. Normal SIGINT/SIGTERM shutdown releases it. Force-killing Node or rebooting can leave it behind. Inspect the startup failure, then explicitly run `node server/preset-sync/index.mjs --recover-lock`. Recovery requires a valid stored PID and an OS `ESRCH` result proving that process is absent; live, inaccessible or malformed PIDs are refused. It only removes the lock and never changes preferences. PID reuse safely refuses recovery. The next normal invocation starts the service.

Run `node --test tests/preset-sync.test.mjs` and `npm test`. TLS integration tests generate a fresh process-local key/certificate through `tests/fixtures/ephemeral-tls.mjs`, trust only that certificate, and bind ephemeral loopback ports. No private test key is bundled. Actual browser/native UI integration, runtime ACL inspection, and port8792/9447 startup are separate parent-owned validation.
