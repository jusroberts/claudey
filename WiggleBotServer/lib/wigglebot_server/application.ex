defmodule WigglebotServer.Application do
  # See https://hexdocs.pm/elixir/Application.html
  # for more information on OTP Applications
  @moduledoc false

  use Application

  @impl true
  def start(_type, _args) do
    children = [
      WigglebotServerWeb.Telemetry,
      {DNSCluster, query: Application.get_env(:wigglebot_server, :dns_cluster_query) || :ignore},
      {Phoenix.PubSub, name: WigglebotServer.PubSub},
      {Finch, name: WigglebotServer.Finch},
      WigglebotServer.Repo,
      {Ecto.Migrator, repos: Application.fetch_env!(:wigglebot_server, :ecto_repos)},
      {Registry, keys: :unique, name: WigglebotServer.SessionRegistry},
      {DynamicSupervisor, name: WigglebotServer.SessionSupervisor, strategy: :one_for_one},
      WigglebotServer.BuildManager,
      WigglebotServer.Scheduler,
      WigglebotServerWeb.Endpoint
    ]

    children = goth_children() ++ children

    # See https://hexdocs.pm/elixir/Supervisor.html
    # for other strategies and supported options
    opts = [strategy: :one_for_one, name: WigglebotServer.Supervisor]
    Supervisor.start_link(children, opts)
  end

  # Goth (Google OAuth for FCM) only runs when a service account is configured,
  # so the server works without Firebase set up.
  defp goth_children do
    with path when is_binary(path) <-
           Application.get_env(:wigglebot_server, :fcm_service_account_path),
         {:ok, raw} <- File.read(path),
         {:ok, credentials} <- Jason.decode(raw) do
      [
        {Goth,
         name: WigglebotServer.Goth,
         source:
           {:service_account, credentials,
            scopes: ["https://www.googleapis.com/auth/firebase.messaging"]}}
      ]
    else
      nil ->
        []

      err ->
        require Logger
        Logger.warning("FCM service account unreadable, push disabled: #{inspect(err)}")
        []
    end
  end

  # Tell Phoenix to update the endpoint configuration
  # whenever the application is updated.
  @impl true
  def config_change(changed, _new, removed) do
    WigglebotServerWeb.Endpoint.config_change(changed, removed)
    :ok
  end
end
