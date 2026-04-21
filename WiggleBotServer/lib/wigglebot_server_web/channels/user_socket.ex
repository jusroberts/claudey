defmodule WigglebotServerWeb.UserSocket do
  use Phoenix.Socket

  channel "agent:*", WigglebotServerWeb.AgentChannel
  channel "tmux:*",  WigglebotServerWeb.TmuxChannel

  @impl true
  def connect(_params, socket, _connect_info) do
    {:ok, socket}
  end

  @impl true
  def id(_socket), do: nil
end
