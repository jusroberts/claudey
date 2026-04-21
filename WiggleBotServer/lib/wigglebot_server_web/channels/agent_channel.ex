defmodule WigglebotServerWeb.AgentChannel do
  use Phoenix.Channel

  alias WigglebotServer.AgentSession

  require Logger

  @impl true
  def join("agent:" <> client_id, _params, socket) do
    Logger.info("Client joined: #{client_id}")

    case AgentSession.ensure_started(client_id) do
      :ok ->
        socket = assign(socket, :client_id, client_id)
        # Route server→channel push messages into this process
        {:ok, socket}

      err ->
        {:error, %{reason: inspect(err)}}
    end
  end

  @impl true
  def handle_in("user_message", %{"text" => text} = payload, socket) do
    location =
      case payload do
        %{"lat" => lat, "lon" => lon} when is_number(lat) and is_number(lon) -> {lat, lon}
        _ -> nil
      end

    AgentSession.send_message(socket.assigns.client_id, text, self(), location)
    {:noreply, socket}
  end

  @impl true
  def handle_in("tool_result", %{"tool_call_id" => id, "result" => result}, socket) do
    AgentSession.tool_result(socket.assigns.client_id, id, result)
    {:noreply, socket}
  end

  @impl true
  def handle_in("clear_history", _params, socket) do
    AgentSession.clear_history(socket.assigns.client_id)
    {:noreply, socket}
  end

  # AgentSession pushes events to the channel process via plain send/2
  @impl true
  def handle_info({:push, event, payload}, socket) do
    push(socket, event, payload)
    {:noreply, socket}
  end
end
