# This file is responsible for configuring your application
# and its dependencies with the aid of the Config module.
#
# This configuration file is loaded before any dependency and
# is restricted to this project.

# General application configuration
import Config

config :wigglebot_server,
  generators: [timestamp_type: :utc_datetime],
  ecto_repos: [WigglebotServer.Repo]

config :wigglebot_server, WigglebotServer.Repo,
  database: "data/wigglebot_#{config_env()}.db"

config :elixir, :time_zone_database, Tzdata.TimeZoneDatabase

# Cron jobs (server-initiated pushes, reports). Quantum crontab syntax,
# evaluated in the timezone below so wall-clock times survive DST.
config :wigglebot_server, WigglebotServer.Scheduler,
  timezone: "America/Toronto",
  jobs: [
    # Sunday 18:30 — weekly spending-anomaly report
    finance_anomaly_report: [
      schedule: "30 18 * * 0",
      task: {WigglebotServer.Finance.AnomalyReport, :run_weekly, []}
    ],
    # Daily 18:00 — wake the phone's run-reminder worker via FCM so the
    # nudge doesn't depend on Android's alarm/Doze behavior. The on-device
    # 6pm alarm stays as belt-and-braces; both post the same notification id.
    run_reminder_wake: [
      schedule: "0 18 * * *",
      task: {WigglebotServer.Push, :wake, ["run_reminder"]}
    ],
    # Daily 05:30 — pull yesterday's Garmin activities (no-op if unconfigured)
    garmin_sync: [
      schedule: "30 5 * * *",
      task: {WigglebotServer.Running.GarminClient, :sync_recent, []}
    ],
    # Sunday 19:00 — generate next week's training plan
    coach_weekly_plan: [
      schedule: "0 19 * * 0",
      task: {WigglebotServer.Running.Coach, :plan_next_week, []}
    ],
    # Daily 06:30 — unified morning digest (weather + plan + events + finance)
    morning_digest: [
      schedule: "30 6 * * *",
      task: {WigglebotServer.Digest, :send_morning_digest, []}
    ]
  ]

# Configures the endpoint
config :wigglebot_server, WigglebotServerWeb.Endpoint,
  url: [host: "localhost"],
  adapter: Bandit.PhoenixAdapter,
  render_errors: [
    formats: [json: WigglebotServerWeb.ErrorJSON],
    layout: false
  ],
  pubsub_server: WigglebotServer.PubSub,
  live_view: [signing_salt: "+cczqg+f"]

# Configures the mailer
#
# By default it uses the "Local" adapter which stores the emails
# locally. You can see the emails in your browser, at "/dev/mailbox".
#
# For production it's recommended to configure a different adapter
# at the `config/runtime.exs`.
config :wigglebot_server, WigglebotServer.Mailer, adapter: Swoosh.Adapters.Local

# Configures Elixir's Logger
config :logger, :console,
  format: "$time $metadata[$level] $message\n",
  metadata: [:request_id]

# Use Jason for JSON parsing in Phoenix
config :phoenix, :json_library, Jason

# Import environment specific config. This must remain at the bottom
# of this file so it overrides the configuration defined above.
import_config "#{config_env()}.exs"
