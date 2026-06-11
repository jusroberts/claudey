defmodule WigglebotServer.Repo do
  use Ecto.Repo,
    otp_app: :wigglebot_server,
    adapter: Ecto.Adapters.SQLite3
end
