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
      {Registry, keys: :unique, name: WigglebotServer.SessionRegistry},
      {DynamicSupervisor, name: WigglebotServer.SessionSupervisor, strategy: :one_for_one},
      WigglebotServer.BuildManager,
      WigglebotServerWeb.Endpoint
    ]

    # See https://hexdocs.pm/elixir/Supervisor.html
    # for other strategies and supported options
    opts = [strategy: :one_for_one, name: WigglebotServer.Supervisor]
    Supervisor.start_link(children, opts)
  end

  # Tell Phoenix to update the endpoint configuration
  # whenever the application is updated.
  @impl true
  def config_change(changed, _new, removed) do
    WigglebotServerWeb.Endpoint.config_change(changed, removed)
    :ok
  end
end
