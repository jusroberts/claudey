# Homelab setup steps

Manual steps needed on the server / your accounts to activate the features in
EXPANSION_PLAN.md. The code degrades gracefully (logs + no-ops) until each of
these is done.

## Push notifications (FCM)

1. Create a Firebase project (console.firebase.google.com), add an Android app
   with package `com.wiggletonabbey.wigglebot`.
2. Download `google-services.json` into `WiggleBot/app/`. The Gradle build
   applies the google-services plugin only when this file exists.
3. Firebase console → Project settings → Service accounts → Generate new
   private key. Put the JSON on the server and set:
   - `FCM_SERVICE_ACCOUNT_PATH=/path/to/service-account.json`
   (or `:fcm_service_account_path` in `config/local.exs`).
4. Rebuild/install the app, open it once (it registers its token via
   `POST /api/devices`), then verify with:
   `curl -X POST https://<server>/api/push/test`

## Actual Budget bridge

1. Run the REST bridge next to your existing Actual container:

   ```bash
   podman run -d --name actual-http-api \
     -p 5007:5007 \
     -e ACTUAL_SERVER_URL="http://<actual-container>:5006/" \
     -e ACTUAL_SERVER_PASSWORD="<your actual password>" \
     -e API_KEY="<generate a random secret>" \
     jhonderson/actual-http-api:<tag matching your Actual version, e.g. 26.6.1>
   ```

   Keep the image tag on the same calendar-version line as your Actual server
   and upgrade them together.

2. Find your budget's Sync ID: Actual web UI → Settings → Advanced → Sync ID.

3. Configure the Phoenix server (env vars or `config/local.exs`):
   - `ACTUAL_API_URL=http://127.0.0.1:5007`
   - `ACTUAL_API_KEY=<the API_KEY above>`
   - `ACTUAL_SYNC_ID=<sync id>`

4. Test from the phone: ask wigglebot "what's my net worth" or
   "what's my credit card balance".

The weekly anomaly report runs Sundays 18:30 (America/Toronto, configured in
`config/config.exs` under `WigglebotServer.Scheduler`). Trigger one manually
with: `WigglebotServer.Finance.AnomalyReport.run_weekly()` in `iex`.

## Server database

SQLite via Ecto; migrations run automatically at boot. Database file defaults
to `data/wigglebot_<env>.db` relative to the server working dir — override
with `DATABASE_PATH`. Back this file up if you care about report history /
coach data.
