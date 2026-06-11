defmodule WigglebotServer.Scheduler do
  @moduledoc """
  Quantum cron scheduler. Jobs are defined in config under
  `config :wigglebot_server, WigglebotServer.Scheduler, jobs: [...]`.
  """

  use Quantum, otp_app: :wigglebot_server
end
